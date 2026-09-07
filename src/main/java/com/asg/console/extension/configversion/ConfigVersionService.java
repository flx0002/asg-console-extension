/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.configversion;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.asg.console.extension.model.ConfigVersionHistory;
import com.asg.console.extension.repository.ConfigVersionHistoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Config snapshot storage & replay service (IR-028): stores whole-machine
 * config snapshots (rolling window of {@link ConfigVersionHistory#MAX_VERSIONS}
 * per machine) and replays recorded controller-method envelopes for snapshot
 * restore / backup import.
 *
 * <p>Snapshot persistence is user-triggered only (manual snapshot button on
 * the config snapshot page); no automatic AOP snapshotting.
 */
@Slf4j
@Service
public class ConfigVersionService {

    private final ConfigVersionHistoryRepository repository;
    private final ApplicationContext applicationContext;

    public ConfigVersionService(ConfigVersionHistoryRepository repository, ApplicationContext applicationContext) {
        this.repository = repository;
        this.applicationContext = applicationContext;
    }

    /**
     * Record a new version after a successful write.
     *
     * @param category  config domain (plugin-instance / ai-route / route / ...)
     * @param objectKey object identity within the domain
     * @param payload   controller-method-replay envelope JSON
     * @param operator  operator name (may be "unknown")
     * @param source    save / delete / rollback / import / restore
     */
    public void record(String category, String objectKey, String payload, String operator, String source) {
        try {
            Long maxId = repository.findMaxVersionId(category, objectKey);
            long next = (maxId == null ? 0 : maxId) + 1;

            ConfigVersionHistory entity = new ConfigVersionHistory();
            entity.setCategory(category);
            entity.setObjectKey(objectKey);
            entity.setVersionId(next);
            entity.setPayloadJson(payload);
            entity.setOperator(operator == null ? "unknown" : operator);
            entity.setSource(source);
            entity.setCreatedAt(LocalDateTime.now());
            repository.save(entity);

            purgeOldVersions(category, objectKey);
            log.info("ConfigVersion recorded: {}/{} v{} ({})", category, objectKey, next, source);
        } catch (Exception e) {
            // Version recording must never break the main write path.
            log.warn("ConfigVersion record failed for {}/{}: {}", category, objectKey, e.getMessage());
        }
    }

    /** Keep only the newest MAX_VERSIONS entries per object. */
    private void purgeOldVersions(String category, String objectKey) {
        List<ConfigVersionHistory> all =
            repository.findByCategoryAndObjectKeyOrderByVersionIdDesc(category, objectKey);
        if (all.size() > ConfigVersionHistory.MAX_VERSIONS) {
            for (ConfigVersionHistory stale : all.subList(ConfigVersionHistory.MAX_VERSIONS, all.size())) {
                repository.delete(stale);
            }
        }
    }

    /** List versions of one object (newest first, at most MAX_VERSIONS). */
    public List<ConfigVersionHistory> listVersions(String category, String objectKey) {
        return repository.findByCategoryAndObjectKeyOrderByVersionIdDesc(category, objectKey);
    }

    public ConfigVersionHistory get(Long id) {
        return repository.findById(id).orElse(null);
    }

    /** Clear the server-managed version field of a replayable model, if any. */
    private void stripVersion(Object model) {
        if (model == null) {
            return;
        }
        try {
            Method m = model.getClass().getMethod("setVersion", String.class);
            m.invoke(model, (Object)null);
        } catch (NoSuchMethodException ignored) {
            // model has no version field
        } catch (Exception e) {
            log.warn("ConfigVersion stripVersion failed for {}: {}", model.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Replay a controller-method envelope via reflection. */
    public void replay(String payloadJson) {
        JSONObject envelope = JSON.parseObject(payloadJson);
        String controllerClass = envelope.getString("controller");
        String methodName = envelope.getString("method");
        JSONArray paramTypes = envelope.getJSONArray("paramTypes");
        JSONArray args = envelope.getJSONArray("args");
        try {
            Class<?> clazz = Class.forName(controllerClass);
            Object bean = applicationContext.getBean(clazz);
            Class<?>[] types = new Class<?>[paramTypes.size()];
            Object[] values = new Object[paramTypes.size()];
            for (int i = 0; i < paramTypes.size(); i++) {
                types[i] = Class.forName(paramTypes.getString(i));
                JSONObject arg = args.getJSONObject(i);
                String typeName = arg.getString("type");
                if (String.class.getName().equals(typeName)) {
                    values[i] = arg.getString("value");
                } else {
                    values[i] = JSON.parseObject(JSON.toJSONString(arg.get("value")),
                        Class.forName(typeName));
                    // version fields hold K8s resourceVersions of the moment the
                    // version was taken; replaying them makes create calls fail
                    // with CONFLICT (silently swallowed by the caller), so they
                    // are server-managed state and must be stripped on replay.
                    stripVersion(values[i]);
                }
            }
            Method method = clazz.getMethod(methodName, types);
            method.invoke(bean, values);
            log.info("ConfigVersion replayed: {}.{}", clazz.getSimpleName(), methodName);
        } catch (Exception e) {
            log.error("ConfigVersion replay failed for {}.{}: {}", controllerClass, methodName, e.getMessage());
            throw new RuntimeException("Config replay failed: " + e.getMessage(), e);
        }
    }
}
