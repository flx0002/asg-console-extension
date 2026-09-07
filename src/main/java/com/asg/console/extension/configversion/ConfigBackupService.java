/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.configversion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.WasmPluginInstanceScope;
import com.alibaba.higress.sdk.model.Domain;
import com.alibaba.higress.sdk.model.Route;
import com.alibaba.higress.sdk.model.ServiceSource;
import com.alibaba.higress.sdk.service.DomainService;
import com.alibaba.higress.sdk.service.ProxyServerService;
import com.alibaba.higress.sdk.service.RouteService;
import com.alibaba.higress.sdk.service.ServiceSourceService;
import com.alibaba.higress.sdk.service.TlsCertificateService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.alibaba.higress.sdk.service.ai.LlmProviderService;
import com.alibaba.higress.sdk.service.consumer.ConsumerService;
import com.asg.console.extension.controller.AuditChainController;
import com.asg.console.extension.controller.BehaviorAnalysisController;
import com.asg.console.extension.model.ConfigBackupLog;
import com.asg.console.extension.model.ConfigVersionHistory;
import com.asg.console.extension.repository.ConfigBackupLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Whole-machine config backup service (IR-028): exports every config domain as
 * an encrypted envelope set and restores it by replaying the recorded
 * controller methods (same mechanism as version rollback).
 *
 * <p>Fork-console classes are referenced by name + reflection only: this module
 * must not have a compile-time dependency on the fork console.
 *
 * <p>Backup file format: Base64( "ASGBK1" magic + 12-byte IV + AES-256-GCM
 * ciphertext of the backup JSON ). The key material comes from the
 * {@code ASG_BACKUP_KEY} env var; when absent the built-in default key is used
 * (per product decision: system built-in key, no user-managed passphrase).
 */
@Slf4j
@Service
public class ConfigBackupService {

    static final String MAGIC = "ASGBK1";
    private static final String DEFAULT_KEY = "WntASG-IR028-BuiltInBackupKey-v1";
    private static final String KEY_ENV = "ASG_BACKUP_KEY";

    /** Whole-machine snapshots live in the version table under this fixed key. */
    static final String SNAPSHOT_CATEGORY = "snapshot";
    static final String SNAPSHOT_KEY = "whole-machine";
    /**
     * Auto snapshots (source=save) are debounced to one per window. Checked via
     * the latest stored snapshot's createdAt (not JVM memory) so it works
     * correctly with multiple console replicas.
     */
    private static final long AUTO_DEBOUNCE_MS = 10_000;

    /** Restore order matters: certificates/services first, consumers last. */
    private static final String[] DOMAIN_ORDER = {"tls-certificate", "service-source", "proxy-server", "domain",
        "route", "consumer", "ai-provider", "ai-route", "plugin-instance", "system", "audit", "behavior"};

    private static final String FORK_CTRLS = "com.alibaba.higress.console.controller.";
    private static final String PLUGIN_CTRLS = FORK_CTRLS + "WasmPluginInstancesController";
    private static final String SYSTEM_CTRL = FORK_CTRLS + "SystemController";
    private static final String SYSTEM_SERVICE = FORK_CTRLS.replace("controller.", "service.") + "SystemService";
    private static final String UPDATE_HIGRESS_CONFIG_REQUEST = FORK_CTRLS + "dto.UpdateHigressConfigRequest";

    private final ConfigVersionService versionService;
    private final ConfigBackupLogRepository backupLogRepository;
    private final ApplicationContext applicationContext;

    public ConfigBackupService(ConfigVersionService versionService, ConfigBackupLogRepository backupLogRepository,
        ApplicationContext applicationContext) {
        this.versionService = versionService;
        this.backupLogRepository = backupLogRepository;
        this.applicationContext = applicationContext;
    }

    // ------------------------------------------------------------------ export

    /** Export current whole-machine config as an encrypted backup string. */
    public String export() {
        String fileName = "config-backup-" + System.currentTimeMillis() + ".asgbak";
        try {
            JSONObject root = new JSONObject(true);
            root.put("magic", MAGIC);
            root.put("schemaVersion", 1);
            root.put("createdAt", LocalDateTime.now().toString());
            root.put("domains", collectAllDomains());

            JSONArray domains = root.getJSONArray("domains");
            String encrypted = encrypt(root.toJSONString());
            logBackup("export", fileName, domains.size(), "success", null);
            return encrypted;
        } catch (Exception e) {
            logBackup("export", fileName, 0, "failed", e.getMessage());
            throw new RuntimeException("Config backup export failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------- whole-machine snapshots

    /**
     * Take a whole-machine config snapshot (plain JSON envelope set, at most 5
     * kept per machine). Auto snapshots (source=save, triggered by the write
     * aspect) are debounced; manual / restore sources always snapshot.
     *
     * @return the number of config objects captured, or -1 if debounced away
     */
    public int takeSnapshot(String source, String operator) {
        if ("save".equals(source) && isDebounced()) {
            return -1;
        }
        try {
            JSONObject root = new JSONObject(true);
            root.put("createdAt", LocalDateTime.now().toString());
            root.put("domains", collectAllDomains());
            int count = countItems(root);
            versionService.record(SNAPSHOT_CATEGORY, SNAPSHOT_KEY, root.toJSONString(), operator, source);
            logBackup("snapshot", "snapshot-" + source, count, "success", null);
            return count;
        } catch (Exception e) {
            logBackup("snapshot", "snapshot-" + source, 0, "failed", e.getMessage());
            throw new RuntimeException("Config snapshot failed: " + e.getMessage(), e);
        }
    }

    /** List whole-machine snapshots (newest first, at most 5). */
    public List<ConfigVersionHistory> listSnapshots() {
        return versionService.listVersions(SNAPSHOT_CATEGORY, SNAPSHOT_KEY);
    }

    private boolean isDebounced() {
        List<ConfigVersionHistory> latest = versionService.listVersions(SNAPSHOT_CATEGORY, SNAPSHOT_KEY);
        if (latest.isEmpty()) {
            return false;
        }
        ConfigVersionHistory newest = latest.get(0);
        long since = java.time.Duration.between(newest.getCreatedAt(), LocalDateTime.now()).toMillis();
        return since >= 0 && since < AUTO_DEBOUNCE_MS;
    }

    /**
     * Restore the whole-machine config from a stored snapshot. Before
     * restoring, the current config is snapshotted as pre-restore so the
     * restore itself is revertible.
     *
     * @return number of restored objects
     */
    public int restoreSnapshot(Long id) {
        ConfigVersionHistory snapshot = versionService.get(id);
        if (snapshot == null || !SNAPSHOT_KEY.equals(snapshot.getObjectKey())) {
            throw new IllegalArgumentException("Snapshot not found: " + id);
        }
        JSONObject root = JSON.parseObject(snapshot.getPayloadJson());
        // anchor: whole-machine snapshot of the current config so the restore can be reverted
        takeSnapshot("pre-restore", "admin");
        return restoreDomains(root);
    }

    private int restoreDomains(JSONObject root) {
        int count = 0;
        JSONArray domains = root.getJSONArray("domains");
        for (String expectedCategory : DOMAIN_ORDER) {
            for (int i = 0; i < domains.size(); i++) {
                JSONObject domain = domains.getJSONObject(i);
                if (!expectedCategory.equals(domain.getString("category"))) {
                    continue;
                }
                JSONArray items = domain.getJSONArray("items");
                for (int j = 0; j < items.size(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    versionService.replay(item.getString("envelope"));
                    count++;
                }
            }
        }
        return count;
    }

    private int countItems(JSONObject root) {
        int total = 0;
        JSONArray domains = root.getJSONArray("domains");
        for (int i = 0; i < domains.size(); i++) {
            total += domains.getJSONObject(i).getJSONArray("items").size();
        }
        return total;
    }

    // ------------------------------------------------------------------ import

    /**
     * Decrypt and restore a backup file. Before restoring, the current config
     * is snapshotted as pre-import versions so the import itself is revertible.
     *
     * @return number of restored objects
     */
    public int importBackup(String content) {
        try {
            JSONObject root = JSON.parseObject(decrypt(content));
            if (!MAGIC.equals(root.getString("magic"))) {
                throw new IllegalArgumentException("Invalid backup file: magic mismatch");
            }
            takeSnapshot("pre-import", "admin");

            int count = restoreDomains(root);
            logBackup("import", "imported-" + System.currentTimeMillis() + ".asgbak", count, "success", null);
            return count;
        } catch (Exception e) {
            logBackup("import", "imported-" + System.currentTimeMillis() + ".asgbak", 0, "failed", e.getMessage());
            throw new RuntimeException("Config backup import failed: " + e.getMessage(), e);
        }
    }

    private JSONArray collectAllDomains() throws Exception {
        JSONArray domains = new JSONArray();
        domains.add(collectSimple("tls-certificate", TlsCertificateService.class,
            FORK_CTRLS + "TlsCertificatesController", "add"));
        domains.add(collectSimple("service-source", ServiceSourceService.class,
            FORK_CTRLS + "ServiceSourceController", "add"));
        domains.add(collectSimple("proxy-server", ProxyServerService.class, FORK_CTRLS + "ProxyServerController",
            "add"));
        domains.add(collectSimple("domain", DomainService.class, FORK_CTRLS + "DomainsController", "add"));
        domains.add(collectSimple("route", RouteService.class, FORK_CTRLS + "RoutesController", "add"));
        domains.add(collectSimple("consumer", ConsumerService.class, FORK_CTRLS + "ConsumersController", "add"));
        domains.add(collectSimple("ai-provider", LlmProviderService.class,
            "com.alibaba.higress.console.controller.ai.LlmProvidersController", "add"));
        domains.add(collectSimple("ai-route", AiRouteService.class,
            "com.alibaba.higress.console.controller.ai.AiRoutesController", "add"));
        domains.add(collectPluginInstances());
        domains.add(collectSystem());
        domains.add(collectMapConfig("audit", AuditChainController.class, "getAuditConfig", "updateAuditConfig"));
        domains.add(collectMapConfig("behavior", BehaviorAnalysisController.class, "getConfig", "updateConfig"));
        return domains;
    }

    // ------------------------------------------------------------------ collectors

    private JSONObject newDomain(String category) {
        JSONObject d = new JSONObject(true);
        d.put("category", category);
        d.put("items", new JSONArray());
        return d;
    }

    @SuppressWarnings("unchecked")
    private JSONObject collectSimple(String category, Class<?> serviceClass, String controllerClass,
        String method) throws Exception {
        JSONObject domain = newDomain(category);
        Object service = applicationContext.getBean(serviceClass);
        Object page = invokeList(service);
        List<?> items = (List<?>)page.getClass().getMethod("getData").invoke(page);
        for (Object item : items) {
            String name = String.valueOf(item.getClass().getMethod("getName").invoke(item));
            addInstance(domain, name, buildEnvelope(controllerClass, method, item));
        }
        return domain;
    }

    private JSONObject collectPluginInstances() throws Exception {
        JSONObject domain = newDomain("plugin-instance");
        WasmPluginInstanceService service = applicationContext.getBean(WasmPluginInstanceService.class);

        for (WasmPluginInstance inst : service.list(WasmPluginInstanceScope.GLOBAL, null)) {
            addInstance(domain, "global/" + inst.getPluginName(),
                buildEnvelope(PLUGIN_CTRLS, "addOrUpdateGlobalInstance", inst.getPluginName(), inst));
        }
        for (Domain d : listAll(DomainService.class, Domain.class)) {
            for (WasmPluginInstance inst : service.list(WasmPluginInstanceScope.DOMAIN, d.getName())) {
                addInstance(domain, "domain/" + d.getName() + "/" + inst.getPluginName(),
                    buildEnvelope(PLUGIN_CTRLS, "addOrUpdateDomainInstance", d.getName(), inst.getPluginName(), inst));
            }
        }
        for (Route r : listAll(RouteService.class, Route.class)) {
            for (WasmPluginInstance inst : service.list(WasmPluginInstanceScope.ROUTE, r.getName())) {
                addInstance(domain, "route/" + r.getName() + "/" + inst.getPluginName(),
                    buildEnvelope(PLUGIN_CTRLS, "addOrUpdateRouteInstance", r.getName(), inst.getPluginName(), inst));
            }
        }
        for (ServiceSource s : listAll(ServiceSourceService.class, ServiceSource.class)) {
            for (WasmPluginInstance inst : service.list(WasmPluginInstanceScope.SERVICE, s.getName())) {
                addInstance(domain, "service/" + s.getName() + "/" + inst.getPluginName(),
                    buildEnvelope(PLUGIN_CTRLS, "addOrUpdateServiceInstance", s.getName(), inst.getPluginName(), inst));
            }
        }
        return domain;
    }

    private void addInstance(JSONObject domain, String objectKey, String envelope) {
        JSONObject one = new JSONObject(true);
        one.put("objectKey", objectKey);
        one.put("envelope", envelope);
        domain.getJSONArray("items").add(one);
    }

    private JSONObject collectSystem() throws Exception {
        JSONObject domain = newDomain("system");
        Class<?> serviceClass = Class.forName(SYSTEM_SERVICE);
        Object systemService = applicationContext.getBean(serviceClass);
        String config = (String)serviceClass.getMethod("getHigressConfig").invoke(systemService);
        if (config != null) {
            // UpdateHigressConfigRequest has a single "config" field; the
            // replay path deserializes the JSON value into that class.
            JSONObject value = new JSONObject(true);
            value.put("config", config);
            addInstance(domain, "higress-config", buildEnvelopeTyped(SYSTEM_CTRL, "updateHigressConfig",
                new String[] {UPDATE_HIGRESS_CONFIG_REQUEST}, value));
        }
        return domain;
    }

    @SuppressWarnings("unchecked")
    private JSONObject collectMapConfig(String category, Class<?> controllerClass, String queryMethod,
        String updateMethod) throws Exception {
        JSONObject domain = newDomain(category);
        Object controller = applicationContext.getBean(controllerClass);
        java.lang.reflect.Method qMethod = controller.getClass().getMethod(queryMethod);
        qMethod.setAccessible(true);
        Object response = qMethod.invoke(controller);
        Map<String, Object> config = (Map<String, Object>)unwrapData(response);
        if (config != null && !config.isEmpty()) {
            addInstance(domain, "config", buildEnvelope(controllerClass.getName(), updateMethod, config));
        }
        return domain;
    }

    /** Strip ResponseEntity/Response wrappers down to the business payload. */
    private Object unwrapData(Object value) throws Exception {
        Object v = value;
        while (v != null && v.getClass().getName().endsWith("ResponseEntity")) {
            java.lang.reflect.Method body = v.getClass().getMethod("getBody");
            body.setAccessible(true);
            v = body.invoke(v);
        }
        if (v != null) {
            try {
                java.lang.reflect.Method getData = v.getClass().getMethod("getData");
                getData.setAccessible(true);
                Object data = getData.invoke(v);
                if (data != null) {
                    v = data;
                }
            } catch (NoSuchMethodException ignored) {
                // not a Response wrapper, use as-is
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private <T> List<T> listAll(Class<?> serviceClass, Class<T> modelClass) throws Exception {
        Object service = applicationContext.getBean(serviceClass);
        Object page = invokeList(service);
        return (List<T>)page.getClass().getMethod("getData").invoke(page);
    }

    /**
     * Invoke the service's single-argument list(...) method with a freshly
     * built page query. The query type varies per service (CommonPageQuery vs
     * RoutePageQuery), so both the method and the query object are resolved
     * reflectively by naming convention instead of a fixed signature.
     */
    private Object invokeList(Object service) throws Exception {
        for (java.lang.reflect.Method m : service.getClass().getMethods()) {
            if (!"list".equals(m.getName())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1 || !pts[0].getName().endsWith("PageQuery")) {
                continue;
            }
            Object query = pts[0].newInstance();
            setIntIfPresent(query, "setPageNum", 1);
            setIntIfPresent(query, "setPageSize", 1000);
            m.setAccessible(true);
            return m.invoke(service, query);
        }
        throw new NoSuchMethodException("no list(PageQuery) method on " + service.getClass().getName());
    }

    private void setIntIfPresent(Object target, String setter, int value) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (setter.equals(m.getName()) && m.getParameterTypes().length == 1) {
                Class<?> p = m.getParameterTypes()[0];
                if (p == int.class || p == Integer.class) {
                    try {
                        m.invoke(target, value);
                    } catch (Exception ignored) {
                        // keep query defaults on failure
                    }
                }
                return;
            }
        }
    }

    private String buildEnvelope(String controllerClass, String method, Object... args) {
        String[] typeNames = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                typeNames[i] = String.class.getName();
            } else if (args[i] instanceof Map) {
                // controller signatures declare Map (e.g. @RequestBody Map),
                // never the concrete runtime class (JSONObject/LinkedHashMap)
                typeNames[i] = Map.class.getName();
            } else {
                typeNames[i] = args[i].getClass().getName();
            }
        }
        return buildEnvelopeTyped(controllerClass, method, typeNames, args);
    }

    private String buildEnvelopeTyped(String controllerClass, String method, String[] typeNames, Object... args) {
        JSONObject env = new JSONObject(true);
        env.put("controller", controllerClass);
        env.put("method", method);
        JSONArray pt = new JSONArray();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < args.length; i++) {
            pt.add(typeNames[i]);
            JSONObject arg = new JSONObject(true);
            arg.put("type", typeNames[i]);
            arg.put("value", args[i] instanceof String ? args[i] : JSON.parseObject(JSON.toJSONString(args[i])));
            arr.add(arg);
        }
        env.put("paramTypes", pt);
        env.put("args", arr);
        return env.toJSONString();
    }

    private void logBackup(String action, String fileName, int domainCount, String result, String message) {
        try {
            ConfigBackupLog entity = new ConfigBackupLog();
            entity.setAction(action);
            entity.setFileName(fileName);
            entity.setDomainCount(domainCount);
            entity.setOperator("admin");
            entity.setResult(result);
            entity.setMessage(message);
            entity.setCreatedAt(LocalDateTime.now());
            backupLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("ConfigBackup log failed: {}", e.getMessage());
        }
    }

    /** Backup operation log for the console (newest first). */
    public List<ConfigBackupLog> listLogs() {
        return backupLogRepository.findTop50ByOrderByCreatedAtDesc();
    }

    // ------------------------------------------------------------------ crypto

    private SecretKey key() throws Exception {
        String material = System.getenv(KEY_ENV);
        if (material == null || material.isEmpty()) {
            material = DEFAULT_KEY;
        }
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    String encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC.getBytes(StandardCharsets.UTF_8));
        out.write(iv);
        out.write(cipherText);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    String decrypt(String content) throws Exception {
        byte[] all = Base64.getMimeDecoder().decode(content.replaceAll("\\s", ""));
        byte[] magic = MAGIC.getBytes(StandardCharsets.UTF_8);
        if (all.length <= magic.length + 12 || !Arrays.equals(Arrays.copyOfRange(all, 0, magic.length), magic)) {
            throw new IllegalArgumentException("Invalid backup file format");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, magic.length, 12));
        byte[] plain = cipher.doFinal(Arrays.copyOfRange(all, magic.length + 12, all.length));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
