/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.higress.sdk.exception.ValidationException;
import com.asg.console.extension.controller.dto.ComplianceKbContent;
import com.asg.console.extension.controller.dto.ComplianceKbLicense;
import com.asg.console.extension.controller.dto.ComplianceKbVersionView;
import com.asg.console.extension.controller.dto.KbLicenseStatus;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.kb.ComplianceKbBundle;
import com.asg.console.extension.kb.KbComplianceGatewaySync;
import com.asg.console.extension.kb.KbCrypto;
import com.asg.console.extension.kb.KbFeatureCatalog;
import com.asg.console.extension.kb.KbOnlineClient;
import com.asg.console.extension.kb.LicenseInfo;
import com.asg.console.extension.kb.WntBundleCipher;
import com.asg.console.extension.model.AiKbLicense;
import com.asg.console.extension.model.ComplianceKbVersion;
import com.asg.console.extension.repository.ComplianceKbVersionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link ComplianceKbService} 默认实现（BE-011）。整体镜像 {@code AiKbServiceImpl} 的加密落库 +
 * 版本切换 + 提交后网关同步流水线；差异有三：授权门控用 {@code content_compliance_kb} 功能位、
 * bundle 为附录 A 词库、数据面下发走 {@code ai-prompt-guard}（{@link KbComplianceGatewaySync}）。
 */
@Slf4j
@Service
public class ComplianceKbServiceImpl implements ComplianceKbService {

    private ComplianceKbVersionRepository versionRepository;
    private KbLicenseService licenseService;
    private KbCrypto kbCrypto;
    private KbOnlineClient onlineClient;
    private KbSettingService kbSettingService;
    private KbComplianceGatewaySync gatewaySync;
    private WntBundleCipher wntBundleCipher;
    private PlatformTransactionManager txManager;

    @Resource
    public void setVersionRepository(ComplianceKbVersionRepository versionRepository) {
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
    public void setOnlineClient(KbOnlineClient onlineClient) {
        this.onlineClient = onlineClient;
    }

    @Resource
    public void setKbSettingService(KbSettingService kbSettingService) {
        this.kbSettingService = kbSettingService;
    }

    @Resource
    public void setGatewaySync(KbComplianceGatewaySync gatewaySync) {
        this.gatewaySync = gatewaySync;
    }

    @Resource
    public void setWntBundleCipher(WntBundleCipher wntBundleCipher) {
        this.wntBundleCipher = wntBundleCipher;
    }

    @Resource
    public void setTxManager(PlatformTransactionManager txManager) {
        this.txManager = txManager;
    }

    @Override
    @Transactional
    public ComplianceKbVersionView importWntBundle(String base64Wnt, String source, String operator) {
        // 1) 授权门控（content_compliance_kb）：无有效授权即拒绝更新，保持最后一次有效词库
        if (!licenseService.canUpdateCompliance()) {
            throw new AuthException("内容合规词库更新未授权：无有效授权或授权已过期，仅可使用最后一次有效词库");
        }
        if (StringUtils.isBlank(base64Wnt)) {
            throw new ValidationException("词库更新包内容为空");
        }
        // 2) 拆信封：外层 signtool 头校签（厂商公钥，保真）+ 内层 AES-256-GCM 解密（对称密钥，保密）
        WntBundleCipher.OpenBundle opened;
        try {
            opened = wntBundleCipher.openWnt(base64Wnt);
        } catch (Exception e) {
            log.warn("Compliance KB WNT update package rejected (verify/decrypt failed): {}", e.getMessage());
            throw new ValidationException("词库更新包非法、验签或解密失败");
        }
        // 3) 真实性已在校签层完成，进入解析/去重/加密落库流水线
        return applyBundle(opened.bundleJson, opened.signatureBase64, opened.sigAlgorithm, source, operator);
    }

    /**
     * 应用已校签解密的 bundle：结构校验 → sha256 去重 → 加密落库 → 版本切换 → 网关同步。
     */
    private ComplianceKbVersionView applyBundle(String bundleJson, String signature, String sigAlgorithm,
        String source, String operator) {
        ComplianceKbBundle bundle;
        try {
            bundle = JSON.parseObject(bundleJson, ComplianceKbBundle.class);
        } catch (Exception e) {
            throw new ValidationException("词库更新包 JSON 解析失败");
        }
        if (bundle == null || bundle.isEmpty()) {
            throw new ValidationException("词库更新包无有效分类");
        }
        try {
            String hash = KbCrypto.sha256Hex(bundleJson);
            // 去重：内容与当前 active 完全相同则不产生新版本、不改 active、不重复同步
            ComplianceKbVersion cur = versionRepository
                .findFirstByStatusOrderByCreatedAtDesc(ComplianceKbVersion.STATUS_ACTIVE).orElse(null);
            if (cur != null && hash.equals(cur.getKbHash())) {
                log.info("Compliance KB import skipped: content hash unchanged (versionNo={}, hash={})",
                    cur.getVersionNo(), hash);
                ComplianceKbVersionView unchanged = ComplianceKbVersionView.from(cur);
                unchanged.setUnchanged(true);
                return unchanged;
            }
            String cipher = kbCrypto.encrypt(bundleJson);
            long versionNo = nextVersionNo();
            demoteActive();
            ComplianceKbVersion v = new ComplianceKbVersion();
            v.setVersionNo(versionNo);
            v.setLabel("compliance-kb-" + versionNo);
            v.setBundleCipher(cipher);
            v.setSignature(signature);
            v.setSigAlgorithm(sigAlgorithm);
            v.setKbHash(hash);
            v.setCategoryCount(bundle.categoryCount());
            v.setKeywordCount(bundle.keywordCount());
            v.setStatus(ComplianceKbVersion.STATUS_ACTIVE);
            v.setSource(source);
            v.setOperator(StringUtils.isBlank(operator) ? "unknown" : operator);
            v.setCreatedAt(LocalDateTime.now());
            // 网关 CR 同步移出事务提交前：事务活跃（生产）→ 先落库（同步态留空），提交后另起事务尽力下发；
            // 无活动事务（如单测直接 new 服务）→ 内联同步，同一单次落库。
            boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
            if (deferred) {
                v = versionRepository.save(v);
                scheduleGatewaySyncAfterCommit(v.getId(), hash, () -> bundle);
            } else {
                boolean synced = syncGateway(bundle);
                v.setGatewaySyncedHash(synced ? hash : null);
                v.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
                v = versionRepository.save(v);
            }
            log.info("Compliance KB imported: versionNo={}, categories={}, keywords={}, source={}, operator={}, deferred={}",
                v.getVersionNo(), v.getCategoryCount(), v.getKeywordCount(), source, v.getOperator(), deferred);
            return ComplianceKbVersionView.from(v);
        } catch (ValidationException | AuthException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            log.warn("Compliance KB import rejected by version_no uniqueness (likely concurrent import): {}",
                e.getMessage());
            throw new ValidationException("词库版本并发冲突，请重试");
        } catch (Exception e) {
            log.error("Compliance KB import failed at encrypt/persist stage", e);
            throw new ValidationException("词库更新包加密落库失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ComplianceKbVersionView onlineUpdate(String operator) {
        String onlineUrl = kbSettingService == null ? "" : kbSettingService.effectiveOnlineUrl();
        if (!onlineClient.isEnabled(onlineUrl)) {
            throw new ValidationException("在线更新未配置：请先在「系统配置 › KB 在线更新」设置更新服务器地址");
        }
        // 在线分发的 bundle 即为 .wnt 的 Base64；授权门控与校签/解密在 importWntBundle 内统一执行。
        // 拉取失败（服务器不可达/返回异常）属可恢复运营故障，转成用户可读业务错误，避免裸栈 500。
        KbOnlineClient.OnlineKbPackage pkg;
        try {
            pkg = onlineClient.fetchLatestComplianceKb(onlineUrl);
        } catch (ValidationException | AuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Compliance KB online update: fetch failed (url={}): {}", onlineUrl, e.getMessage());
            throw new ValidationException("在线更新失败：无法连接词库在线更新服务器，请确认地址正确且服务可用，或改用离线导入");
        }
        return importWntBundle(pkg.getBundle(), ComplianceKbVersion.SRC_ONLINE, operator);
    }

    @Override
    public List<ComplianceKbVersionView> listVersions() {
        List<ComplianceKbVersionView> list = new ArrayList<>();
        for (ComplianceKbVersion v : versionRepository.findAllByOrderByVersionNoDesc()) {
            list.add(ComplianceKbVersionView.from(v));
        }
        return list;
    }

    @Override
    public ComplianceKbVersionView getActiveVersion() {
        return versionRepository.findFirstByStatusOrderByCreatedAtDesc(ComplianceKbVersion.STATUS_ACTIVE)
            .map(ComplianceKbVersionView::from).orElse(null);
    }

    @Override
    public ComplianceKbContent getContent() {
        ComplianceKbVersion active = versionRepository
            .findFirstByStatusOrderByCreatedAtDesc(ComplianceKbVersion.STATUS_ACTIVE).orElse(null);
        if (active == null) {
            return null;
        }
        try {
            String plain = kbCrypto.decrypt(active.getBundleCipher());
            ComplianceKbBundle bundle = JSON.parseObject(plain, ComplianceKbBundle.class);
            return ComplianceKbContent.of(bundle, active.getVersionNo(), active.getKbHash());
        } catch (Exception e) {
            log.error("Failed to decrypt active compliance KB bundle for content (versionNo={})",
                active.getVersionNo(), e);
            return null;
        }
    }

    @Override
    @Transactional
    public ComplianceKbVersionView rollback(Long versionNo, String operator) {
        if (versionNo == null) {
            throw new ValidationException("versionNo 不能为空");
        }
        ComplianceKbVersion target = versionRepository.findByVersionNo(versionNo)
            .orElseThrow(() -> new ValidationException("目标版本不存在: " + versionNo));
        if (ComplianceKbVersion.STATUS_ACTIVE.equals(target.getStatus())) {
            return ComplianceKbVersionView.from(target);
        }
        demoteActive();
        target.setStatus(ComplianceKbVersion.STATUS_ACTIVE);
        target.setSource(ComplianceKbVersion.SRC_ROLLBACK);
        target.setOperator(StringUtils.isBlank(operator) ? "unknown" : operator);
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        final ComplianceKbVersion rolled = target;
        final String targetHash = target.getKbHash();
        if (deferred) {
            target = versionRepository.save(target);
            scheduleGatewaySyncAfterCommit(target.getId(), targetHash,
                () -> JSON.parseObject(kbCrypto.decrypt(rolled.getBundleCipher()), ComplianceKbBundle.class));
        } else {
            boolean synced = false;
            try {
                ComplianceKbBundle bundle =
                    JSON.parseObject(kbCrypto.decrypt(target.getBundleCipher()), ComplianceKbBundle.class);
                synced = syncGateway(bundle);
            } catch (Exception e) {
                log.warn("Compliance KB rolled back but failed to decrypt target bundle for gateway sync (versionNo={})",
                    versionNo, e);
            }
            target.setGatewaySyncedHash(synced ? targetHash : null);
            target.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
            target = versionRepository.save(target);
        }
        log.info("Compliance KB rolled back to versionNo={}, operator={}", versionNo, target.getOperator());
        return ComplianceKbVersionView.from(target);
    }

    @Override
    @Transactional
    public boolean syncActiveToGateway() {
        ComplianceKbVersion active = versionRepository
            .findFirstByStatusOrderByCreatedAtDesc(ComplianceKbVersion.STATUS_ACTIVE).orElse(null);
        if (active == null) {
            return false;
        }
        boolean synced = false;
        try {
            ComplianceKbBundle bundle =
                JSON.parseObject(kbCrypto.decrypt(active.getBundleCipher()), ComplianceKbBundle.class);
            synced = syncGateway(bundle);
        } catch (Exception e) {
            log.warn("Failed to decrypt active compliance KB bundle for gateway sync (versionNo={})",
                active.getVersionNo(), e);
        }
        active.setGatewaySyncedHash(synced ? active.getKbHash() : null);
        active.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
        versionRepository.save(active);
        return synced;
    }

    @Override
    public ComplianceKbLicense licenseStatus() {
        ComplianceKbLicense out = new ComplianceKbLicense();
        out.setFeatureName(KbFeatureCatalog.displayName(LicenseInfo.FEATURE_COMPLIANCE_KB));
        boolean can = licenseService.canUpdateCompliance();
        out.setCanUpdate(can);
        KbLicenseStatus st = licenseService.getStatus();
        boolean present = false;
        if (st != null && st.getFunctions() != null) {
            for (KbLicenseStatus.FunctionView fv : st.getFunctions()) {
                if (fv != null && LicenseInfo.FEATURE_COMPLIANCE_KB.equals(fv.getId())) {
                    present = true;
                    out.setExpiresAt(fv.getExpiresAt());
                    break;
                }
            }
        }
        if (!can) {
            if (!present) {
                boolean noLicense = st == null || AiKbLicense.STATUS_ABSENT.equalsIgnoreCase(st.getStatus());
                out.setReason(noLicense ? "尚未导入授权" : "授权未包含内容合规词库更新功能位");
            } else {
                out.setReason("内容合规词库更新授权已过期");
            }
        }
        return out;
    }

    /** 把当前所有 active 降级为 inactive（保证同一时刻至多一行 active）。 */
    private void demoteActive() {
        List<ComplianceKbVersion> actives = versionRepository.findAllByStatus(ComplianceKbVersion.STATUS_ACTIVE);
        for (ComplianceKbVersion v : actives) {
            v.setStatus(ComplianceKbVersion.STATUS_INACTIVE);
        }
        if (!actives.isEmpty()) {
            versionRepository.saveAll(actives);
        }
    }

    /** 下一个版本号：现有最大 +1（词库 bundle 不含厂商版本号，纯本地单调递增）。 */
    private long nextVersionNo() {
        long max = versionRepository.findFirstByOrderByVersionNoDesc()
            .map(ComplianceKbVersion::getVersionNo).orElse(0L);
        return max + 1;
    }

    /** 同步到网关 CR；未装配同步器（如单测）视为未同步返回 false，失败仅告警不影响落库。 */
    private boolean syncGateway(ComplianceKbBundle bundle) {
        if (gatewaySync == null) {
            return false;
        }
        try {
            return gatewaySync.sync(bundle);
        } catch (Exception e) {
            log.warn("Compliance KB persisted but gateway CR sync failed; retry via POST /v1/compliance-kb/sync-gateway", e);
            return false;
        }
    }

    /** 事务提交后尽力下发网关 CR，并另起独立事务（REQUIRES_NEW）按 id 回写同步态。 */
    private void scheduleGatewaySyncAfterCommit(long versionId, String hash,
        ThrowingSupplier<ComplianceKbBundle> bundleSupplier) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean synced;
                try {
                    synced = syncGateway(bundleSupplier.get());
                } catch (Exception e) {
                    log.warn("Compliance KB gateway sync skipped: failed to load/decrypt bundle (versionId={})",
                        versionId, e);
                    synced = false;
                }
                try {
                    writebackSyncState(versionId, hash, synced);
                } catch (Exception e) {
                    log.warn("Compliance KB gateway sync-state writeback failed after commit (versionId={})",
                        versionId, e);
                }
            }
        });
    }

    /** 提交后独立事务回写同步态；txManager 缺失（未装配）则跳过。 */
    private void writebackSyncState(long versionId, String hash, boolean synced) {
        if (txManager == null) {
            return;
        }
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(status -> {
            ComplianceKbVersion row = versionRepository.findById(versionId).orElse(null);
            if (row != null) {
                row.setGatewaySyncedHash(synced ? hash : null);
                row.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
                versionRepository.save(row);
            }
            return null;
        });
    }

    /** 可抛受检异常的惰性供给器（afterCommit 内需解密 bundle，decrypt 声明 throws Exception）。 */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
