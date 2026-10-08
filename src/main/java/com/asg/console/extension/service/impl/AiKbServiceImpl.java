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
import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.controller.exception.AuthException;
import com.asg.console.extension.kb.KbBundle;
import com.asg.console.extension.kb.KbCrypto;
import com.asg.console.extension.kb.KbGatewaySync;
import com.asg.console.extension.kb.KbOnlineClient;
import com.asg.console.extension.kb.WntBundleCipher;
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
    private KbOnlineClient onlineClient;
    private KbSettingService kbSettingService;
    private KbGatewaySync gatewaySync;
    private WntBundleCipher wntBundleCipher;
    private PlatformTransactionManager txManager;

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
    public void setOnlineClient(KbOnlineClient onlineClient) {
        this.onlineClient = onlineClient;
    }

    @Resource
    public void setKbSettingService(KbSettingService kbSettingService) {
        this.kbSettingService = kbSettingService;
    }

    @Resource
    public void setGatewaySync(KbGatewaySync gatewaySync) {
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
    public KbMeta importWntBundle(String base64Wnt, String source, String operator) {
        // 1) 授权门控：无有效授权即拒绝更新，保持最后一次有效库
        if (!licenseService.canUpdate()) {
            throw new AuthException("KB 更新未授权：无有效授权或授权已过期，仅可使用最后一次有效库");
        }
        if (StringUtils.isBlank(base64Wnt)) {
            throw new ValidationException("KB 更新包内容为空");
        }
        // 2) 拆信封：外层 signtool 头校签（厂商公钥，保真）+ 内层 AES-256-GCM 解密（对称密钥，保密）；
        //    任一失败即拒，保持最后一次有效库。
        WntBundleCipher.OpenBundle opened;
        try {
            opened = wntBundleCipher.openWnt(base64Wnt);
        } catch (Exception e) {
            log.warn("KB WNT update package rejected (verify/decrypt failed): {}", e.getMessage());
            throw new ValidationException("KB 更新包非法、验签或解密失败");
        }
        // 3) 真实性已在校签层完成，直接进入解析/去重/加密落库流水线
        return applyBundle(opened.bundleJson, opened.signatureBase64, opened.sigAlgorithm, source, operator);
    }

    /**
     * 应用已校签解密的 bundle：结构校验 → sha256 去重 → 加密落库 → 版本切换 → 网关同步。
     *
     * @param bundleJson   明文 bundle JSON（落库加密与去重哈希的原文）
     * @param signature    WNT 头部 raw 签名（Base64），仅供审计展示（nullable=false，已非空）
     * @param sigAlgorithm 签名算法标识（如 ecc-p256-sha256）
     */
    private KbMeta applyBundle(String bundleJson, String signature, String sigAlgorithm, String source,
        String operator) {
        // 解析 + 结构校验
        KbBundle bundle;
        try {
            bundle = JSON.parseObject(bundleJson, KbBundle.class);
        } catch (Exception e) {
            throw new ValidationException("KB 更新包 JSON 解析失败");
        }
        if (bundle == null || bundle.getCategories() == null || bundle.getCategories().isEmpty()) {
            throw new ValidationException("KB 更新包无有效分类");
        }
        // 加密落库 + 版本切换
        try {
            String hash = KbCrypto.sha256Hex(bundleJson);
            // #1 去重：内容与当前 active 完全相同则不产生新版本、不改 active、不重复同步
            AiKbVersion cur = versionRepository
                .findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE).orElse(null);
            if (cur != null && hash.equals(cur.getKbHash())) {
                log.info("KB import skipped: content hash unchanged (versionNo={}, hash={})",
                    cur.getVersionNo(), hash);
                KbMeta unchanged = KbMeta.from(cur);
                unchanged.setUnchanged(true);
                return unchanged;
            }
            String cipher = kbCrypto.encrypt(bundleJson);
            long versionNo = nextVersionNo(bundle.getKbVersion());
            demoteActive();
            AiKbVersion v = new AiKbVersion();
            v.setVersionNo(versionNo);
            v.setLabel("kb-" + versionNo);
            v.setBundleCipher(cipher);
            v.setSignature(signature);
            v.setSigAlgorithm(sigAlgorithm);
            v.setKbHash(hash);
            v.setCategoryCount(bundle.categoryTotal());
            v.setDomainCount(bundle.domainTotal());
            v.setStatus(AiKbVersion.STATUS_ACTIVE);
            v.setSource(source);
            v.setOperator(StringUtils.isBlank(operator) ? "unknown" : operator);
            v.setCreatedAt(LocalDateTime.now());
            // #4 网关 CR 同步移出事务提交前：
            //   事务活跃（生产）→ 先单次落库（同步态留空），提交后再尽力下发并另起事务回写；
            //   无活动事务（如单测直接 new 服务）→ 保持内联同步语义，同一单次落库。
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
            log.info("KB imported: versionNo={}, categories={}, domains={}, source={}, operator={}, gatewaySyncDeferred={}",
                v.getVersionNo(), v.getCategoryCount(), v.getDomainCount(), source, v.getOperator(), deferred);
            return KbMeta.from(v);
        } catch (ValidationException | AuthException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            // #3 并发下两请求算出同一 version_no：唯一键拒绝后者并整体回滚（demote 一并撤销），不产生双 active
            log.warn("KB import rejected by version_no uniqueness (likely concurrent import): {}", e.getMessage());
            throw new ValidationException("KB 版本并发冲突，请重试");
        } catch (Exception e) {
            log.error("KB import failed at encrypt/persist stage", e);
            throw new ValidationException("KB 更新包加密落库失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public KbMeta onlineUpdate(String operator) {
        // 生效地址：系统配置页持久化值优先，未设置回退部署 env（ASG_KB_ONLINE_URL）。
        // 地址解析每次即时进行，改后立即生效、无需重启。
        String onlineUrl = kbSettingService == null ? "" : kbSettingService.effectiveOnlineUrl();
        if (!onlineClient.isEnabled(onlineUrl)) {
            throw new ValidationException("在线更新未配置：请先在「系统配置 › KB 在线更新」设置更新服务器地址");
        }
        // 授权门控与校签/解密在 importWntBundle 内统一执行；在线分发的 bundle 即为 .wnt 的 Base64。
        // 在线服务器不可达/返回异常属可恢复的运营故障，转成用户可读业务错误，避免裸异常栈以 500 抛给前端。
        KbOnlineClient.OnlineKbPackage pkg;
        try {
            pkg = onlineClient.fetchLatestKb(onlineUrl);
        } catch (ValidationException | AuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("KB online update: fetch from online server failed (url={}): {}", onlineUrl, e.getMessage());
            throw new ValidationException("在线更新失败：无法连接 KB 在线更新服务器，请确认地址正确且服务可用，或改用离线导入");
        }
        return importWntBundle(pkg.getBundle(), AiKbVersion.SRC_ONLINE, operator);
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
        // #4 回滚与导入一致：先提交版本切换，再同步网关并回写同步态。事务活跃时把外部
        // CR 下发推迟到提交后；否则（单测）内联同步。解密/下发失败即视为未同步（清空旧同步态）。
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        final AiKbVersion rolled = target;
        final String targetHash = target.getKbHash();
        if (deferred) {
            target = versionRepository.save(target);
            scheduleGatewaySyncAfterCommit(target.getId(), targetHash,
                () -> JSON.parseObject(kbCrypto.decrypt(rolled.getBundleCipher()), KbBundle.class));
        } else {
            boolean synced = false;
            try {
                KbBundle bundle = JSON.parseObject(kbCrypto.decrypt(target.getBundleCipher()), KbBundle.class);
                synced = syncGateway(bundle);
            } catch (Exception e) {
                log.warn("KB rolled back but failed to decrypt target bundle for gateway sync (versionNo={})",
                    versionNo, e);
            }
            target.setGatewaySyncedHash(synced ? targetHash : null);
            target.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
            target = versionRepository.save(target);
        }
        log.info("KB rolled back to versionNo={}, operator={}", versionNo, target.getOperator());
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
    @Transactional
    public boolean syncActiveToGateway() {
        AiKbVersion active = versionRepository
            .findFirstByStatusOrderByCreatedAtDesc(AiKbVersion.STATUS_ACTIVE).orElse(null);
        if (active == null) {
            return false;
        }
        boolean synced = false;
        try {
            KbBundle bundle = JSON.parseObject(kbCrypto.decrypt(active.getBundleCipher()), KbBundle.class);
            synced = syncGateway(bundle);
        } catch (Exception e) {
            log.warn("Failed to decrypt active KB bundle for gateway sync (versionNo={})", active.getVersionNo(), e);
        }
        // 无论成功或解密/同步失败都回写同步态（失败置空），保证徽标与实际下发一致、失败时保留重试入口。
        active.setGatewaySyncedHash(synced ? active.getKbHash() : null);
        active.setGatewaySyncedAt(synced ? LocalDateTime.now() : null);
        versionRepository.save(active);
        return synced;
    }

    /** 同步到网关 CR；未装配同步器（如单测）视为未同步返回 false，失败仅告警不影响 KB 落库。 */
    private boolean syncGateway(KbBundle bundle) {
        if (gatewaySync == null) {
            return false;
        }
        try {
            return gatewaySync.syncCategories(bundle);
        } catch (Exception e) {
            log.warn("KB persisted but gateway CR sync failed; retry via POST /v1/ai-kb/sync-gateway", e);
            return false;
        }
    }

    /**
     * #4 事务提交后（afterCommit）才把 active 分类尽力下发网关 CR，并另起独立事务（REQUIRES_NEW）
     * 按 id 回写同步态——外部副作用与 DB 原子性解耦，避免「CR 已写但事务回滚」的不一致。
     * bundle 由 {@link ThrowingSupplier} 惰性提供（回滚路径需解密落库密文，decrypt 抛受检异常），
     * {@code afterCommit} 内已对 {@code get()} 做 try/catch 兜底。
     */
    private void scheduleGatewaySyncAfterCommit(long versionId, String hash, ThrowingSupplier<KbBundle> bundleSupplier) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                boolean synced;
                try {
                    synced = syncGateway(bundleSupplier.get());
                } catch (Exception e) {
                    log.warn("KB gateway sync skipped: failed to load/decrypt bundle (versionId={})", versionId, e);
                    synced = false;
                }
                // 回写是提交后的副作用状态，其失败不得污染主操作结果（事务已提交）；
                // 失败仅告警，同步态留空由 POST /v1/ai-kb/sync-gateway 对账重试入口补偿。
                try {
                    writebackSyncState(versionId, hash, synced);
                } catch (Exception e) {
                    log.warn("KB gateway sync-state writeback failed after commit (versionId={})", versionId, e);
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
            AiKbVersion row = versionRepository.findById(versionId).orElse(null);
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
