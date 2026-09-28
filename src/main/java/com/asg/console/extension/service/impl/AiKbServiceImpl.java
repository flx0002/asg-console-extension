/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.kb.KbBundle;
import com.asg.console.extension.kb.KbCrypto;
import com.asg.console.extension.kb.KbGatewaySync;
import com.asg.console.extension.kb.KbOnlineClient;
import com.asg.console.extension.kb.KbSignatureVerifier;
import com.asg.console.extension.model.AiKbVersion;
import com.asg.console.extension.repository.AiKbVersionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link AiKbService} 默认实现。整库以「加密 + 签名」的单一 bundle 存储，明文只在
 * {@link #getActiveBundle()} 的内存路径出现；对外接口只返回脱敏 {@link KbMeta}。
 *
 * <p>更新流水线：授权门控 → 验签 → 解析校验 → 加密 → 版本切换（旧 active 降级、新包置 active）。
 * 任一前置不过即整体拒绝，保持最后一次有效库不变。
 */
@Slf4j
@Service
public class AiKbServiceImpl implements AiKbService {

    private AiKbVersionRepository versionRepository;
    private KbLicenseService licenseService;
    private KbCrypto kbCrypto;
    private KbSignatureVerifier signatureVerifier;
    private KbOnlineClient onlineClient;
    private KbGatewaySync gatewaySync;

    @Resource
    public void setVersionRepository(AiKbVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    @Resource
    public void setLicenseService(KbLicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Resource
    public void setKbCrypto(KbCrypto kbCrypto) {
        this.kbCrypto = kbCrypto;
    }

    @Resource
    public void setSignatureVerifier(KbSignatureVerifier signatureVerifier) {
        this.signatureVerifier = signatureVerifier;
    }

    @Resource
    public void setOnlineClient(KbOnlineClient onlineClient) {
        this.onlineClient = onlineClient;
    }

    @Resource
    public void setGatewaySync(KbGatewaySync gatewaySync) {
        this.gatewaySync = gatewaySync;
    }

    @Override
    @Transactional
    public KbMeta importBundle(String bundleJson, String signature, String sigAlgorithm, String label,
        String changelog, String source, String operator) {
        // 1) 授权门控：无有效授权即拒绝更新，保持最后一次有效库
        if (!licenseService.canUpdate()) {
            throw new AuthException("KB 更新未授权：无有效授权或授权已过期，仅可使用最后一次有效库");
        }
        // 2) 基本入参校验
        if (StringUtils.isBlank(bundleJson)) {
            throw new ValidationException("KB 更新包内容为空");
        }
        if (StringUtils.isBlank(signature)) {
            throw new ValidationException("KB 更新包缺少签名");
        }
        // 3) 验签（算法须与内置验签器一致）
        String alg = StringUtils.isBlank(sigAlgorithm) ? signatureVerifier.algorithm() : sigAlgorithm.trim();
        if (!alg.equalsIgnoreCase(signatureVerifier.algorithm())) {
            throw new ValidationException("不支持的签名算法: " + alg + "（内置验签器为 " + signatureVerifier.algorithm() + "）");
        }
        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(signature.replaceAll("\\s", ""));
        } catch (Exception e) {
            throw new ValidationException("KB 更新包签名解码失败");
        }
        if (!signatureVerifier.verify(bundleJson.getBytes(StandardCharsets.UTF_8), sigBytes)) {
            throw new AuthException("KB 更新包验签失败，拒绝应用");
        }
        // 4) 解析 + 结构校验
        KbBundle bundle;
        try {
            bundle = JSON.parseObject(bundleJson, KbBundle.class);
        } catch (Exception e) {
            throw new ValidationException("KB 更新包 JSON 解析失败");
        }
        if (bundle == null || bundle.getCategories() == null || bundle.getCategories().isEmpty()) {
            throw new ValidationException("KB 更新包无有效分类");
        }
        // 5) 加密落库 + 版本切换
        try {
            String cipher = kbCrypto.encrypt(bundleJson);
            String hash = KbCrypto.sha256Hex(bundleJson);
            long versionNo = nextVersionNo(bundle.getKbVersion());
            demoteActive();
            AiKbVersion v = new AiKbVersion();
            v.setVersionNo(versionNo);
            v.setLabel(StringUtils.isBlank(label) ? ("kb-" + versionNo) : label.trim());
            v.setChangelog(changelog);
            v.setBundleCipher(cipher);
            v.setSignature(signature.trim());
            v.setSigAlgorithm(signatureVerifier.algorithm());
            v.setKbHash(hash);
            v.setCategoryCount(bundle.categoryTotal());
            v.setDomainCount(bundle.domainTotal());
            v.setStatus(AiKbVersion.STATUS_ACTIVE);
            v.setSource(source);
            v.setOperator(StringUtils.isBlank(operator) ? "unknown" : operator);
            v.setCreatedAt(LocalDateTime.now());
            v = versionRepository.save(v);
            log.info("KB imported: versionNo={}, categories={}, domains={}, source={}, operator={}",
                v.getVersionNo(), v.getCategoryCount(), v.getDomainCount(), source, v.getOperator());
            // 同步到网关 CR（尽力而为；KB 已落库为权威源，同步失败可手动重试）
            syncGateway(bundle);
            return KbMeta.from(v);
        } catch (ValidationException | AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("KB import failed at encrypt/persist stage", e);
            throw new ValidationException("KB 更新包加密落库失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public KbMeta onlineUpdate(String operator) {
        // 授权门控在 importBundle 内统一执行；此处先取包再走同一验签/加密路径
        KbOnlineClient.OnlineKbPackage pkg = onlineClient.fetchLatestKb();
        return importBundle(pkg.getBundle(), pkg.getSignature(), pkg.getSigAlgorithm(), pkg.getLabel(),
            pkg.getChangelog(), AiKbVersion.SRC_ONLINE, operator);
    }

    @Override
    public List<KbMeta> listVersions() {
        List<KbMeta> list = new ArrayList<>();
        for (AiKbVersion v : versionRepository.findAllByOrderByVersionNoDesc()) {
            list.add(KbMeta.from(v));
        }
        return list;
    }

    @Override
    public KbMeta getActiveMeta() {
        return versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE)
            .map(KbMeta::from).orElse(null);
    }

    @Override
    public KbBundle getActiveBundle() {
        AiKbVersion active = versionRepository.findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE)
            .orElse(null);
        if (active == null) {
            return null;
        }
        try {
            String plain = kbCrypto.decrypt(active.getBundleCipher());
            return JSON.parseObject(plain, KbBundle.class);
        } catch (Exception e) {
            log.error("Failed to decrypt active KB bundle (versionNo={})", active.getVersionNo(), e);
            return null;
        }
    }

    @Override
    @Transactional
    public KbMeta rollback(Long versionNo, String operator) {
        if (versionNo == null) {
            throw new ValidationException("versionNo 不能为空");
        }
        AiKbVersion target = versionRepository.findByVersionNo(versionNo)
            .orElseThrow(() -> new ValidationException("目标版本不存在: " + versionNo));
        if (AiKbVersion.STATUS_ACTIVE.equals(target.getStatus())) {
            return KbMeta.from(target);
        }
        demoteActive();
        target.setStatus(AiKbVersion.STATUS_ACTIVE);
        target.setSource(AiKbVersion.SRC_ROLLBACK);
        target.setOperator(StringUtils.isBlank(operator) ? "unknown" : operator);
        target = versionRepository.save(target);
        log.info("KB rolled back to versionNo={}, operator={}", versionNo, target.getOperator());
        // 回滚后把目标版本的明文库同步到网关 CR
        try {
            KbBundle bundle = JSON.parseObject(kbCrypto.decrypt(target.getBundleCipher()), KbBundle.class);
            syncGateway(bundle);
        } catch (Exception e) {
            log.warn("KB rolled back but failed to decrypt target bundle for gateway sync (versionNo={})",
                versionNo, e);
        }
        return KbMeta.from(target);
    }

    /** 把当前所有 active 降级为 inactive（保证同一时刻至多一行 active）。 */
    private void demoteActive() {
        List<AiKbVersion> actives = versionRepository.findAllByStatus(AiKbVersion.STATUS_ACTIVE);
        for (AiKbVersion v : actives) {
            v.setStatus(AiKbVersion.STATUS_INACTIVE);
        }
        if (!actives.isEmpty()) {
            versionRepository.saveAll(actives);
        }
    }

    /** 下一个版本号：取「现有最大 +1」与「包内 kbVersion」的较大者，保证单调且尊重厂商版本。 */
    private long nextVersionNo(long bundleKbVersion) {
        long max = versionRepository.findFirstByOrderByVersionNoDesc().map(AiKbVersion::getVersionNo).orElse(0L);
        long next = max + 1;
        return Math.max(next, bundleKbVersion);
    }

    @Override
    public boolean syncActiveToGateway() {
        KbBundle bundle = getActiveBundle();
        if (bundle == null) {
            return false;
        }
        return gatewaySync != null && gatewaySync.syncCategories(bundle);
    }

    /** 同步到网关 CR；未装配同步器（如单测）则静默跳过，失败仅告警不影响 KB 落库。 */
    private void syncGateway(KbBundle bundle) {
        if (gatewaySync == null) {
            return;
        }
        try {
            gatewaySync.syncCategories(bundle);
        } catch (Exception e) {
            log.warn("KB persisted but gateway CR sync failed; retry via POST /v1/ai-kb/sync-gateway", e);
        }
    }
}
