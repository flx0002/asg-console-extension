package com.asg.console.extension.config;

import com.asg.console.extension.service.AiShadowService;
import com.asg.console.extension.service.AuditChainService;
import com.asg.console.extension.service.AuditLogCollectorService;
import com.asg.console.extension.service.BehaviorAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ASG scheduled tasks, extracted from AISecGw-console SdkConfig so that
 * the upstream config class stays untouched. Methods are no-arg as required
 * by Spring's {@link Scheduled} annotation.
 */
@Slf4j
@Component
public class AsgScheduledTasks {

    /**
     * 补采窗口（秒）：fixedDelay 的真实周期 = 60s + 三步耗时，且与小时级任务共享默认调度线程，
     * 排队时间隔可能明显超过 60s，故按 5 倍周期留余量。重复读取的日志由 event_id 去重，不会重复入库。
     */
    private static final int AUDIT_COLLECT_SINCE_SECONDS = 300;

    private final AuditChainService auditChainService;
    private final BehaviorAnalysisService behaviorAnalysisService;
    private final AuditLogCollectorService auditLogCollectorService;
    private final AiShadowService aiShadowService;

    public AsgScheduledTasks(AuditChainService auditChainService,
            BehaviorAnalysisService behaviorAnalysisService,
            AuditLogCollectorService auditLogCollectorService,
            AiShadowService aiShadowService) {
        this.auditChainService = auditChainService;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.auditLogCollectorService = auditLogCollectorService;
        this.aiShadowService = aiShadowService;
    }

    /**
     * Run expired audit log cleanup every hour.
     */
    @Scheduled(fixedRate = 3600000)
    public void scheduledAuditCleanup() {
        auditChainService.cleanupExpiredLogs();
    }

    /**
     * 行为基线 EMA 重算，每 1 小时触发一次（方案 5.7）。
     */
    @Scheduled(fixedRate = 3600000)
    public void scheduledBaselineRebuild() {
        try {
            behaviorAnalysisService.rebuildBaselines();
        } catch (Exception e) {
            // 静默吞掉异常，避免调度器因异常终止后续执行
        }
    }

    /**
     * 审计采集 -> 画像增量构建 -> 风险检测，每 60 秒兜底执行一次（检测窗口 120s）。
     * 原先挂在多个 GET 接口上，导致读接口带写副作用、首屏阻塞且同屏数据快照不一致。
     * 三步顺序与旧 catch-up 保持一致：先补日志、再更新画像、最后判定，避免检测读到滞后画像；
     * 原独立的 60s 画像构建任务已合并到此，避免同一周期内全量画像扫描做两遍。
     * 注：当前编译期 Spring 版本不支持 @Scheduled(scheduler=...)，与既有任务共享默认调度线程；
     * 检测水位取「上次处理时刻 - 120s」而非墙钟，偶发排队只会增加时延，不会漏判。
     */
    @Scheduled(fixedDelay = 60000)
    public void scheduledBehaviorDetection() {
        try {
            auditLogCollectorService.collect(AUDIT_COLLECT_SINCE_SECONDS);
        } catch (Exception e) {
            log.warn("scheduled behavior detection: audit collect failed", e);
        }
        try {
            behaviorAnalysisService.rebuildProfiles();
        } catch (Exception e) {
            log.warn("scheduled behavior detection: profile rebuild failed", e);
        }
        try {
            behaviorAnalysisService.runRiskDetection();
        } catch (Exception e) {
            log.warn("scheduled behavior detection: risk detection failed", e);
        }
    }

    /**
     * 告警索引回收，每 1 小时执行一次：清理哈希已过期但索引仍在的成员。
     * 单轮预算 INDEX_REAP_MAX 已使耗时有界，fixedDelay 避免积压后补跑；
     * 该 Spring 版本不支持 @Scheduled(scheduler=...)，故与检测共享默认线程。
     */
    @Scheduled(fixedDelay = 3600000)
    public void scheduledAlertIndexCleanup() {
        try {
            behaviorAnalysisService.cleanupStaleAlertIndexes();
        } catch (Exception e) {
            log.warn("scheduled alert index cleanup failed", e);
        }
    }

    /**
     * identify_only 档位收敛，每 30 秒兜底一次：把 AI 路由 key-auth 实例的 identify_only
     * 持续收敛到全局处置方式（检测模式→只记录，管控模式→拦截），不动名单/开关/模式。
     * 覆盖「授权写入直接建实例」与开关切换前存量偏差留下的缺口——控制台没开时，
     * 档位与全局脱节（如管控模式下非法 key 仍放行）会一直存在。
     * 单次成本 = 每条 AI 路由一次读，值不一致才写；与检测任务共享默认调度线程，排队只增时延。
     */
    @Scheduled(fixedDelay = 30000)
    public void scheduledIdentifyOnlyReconcile() {
        try {
            aiShadowService.reconcileIdentifyOnlyFlags();
        } catch (Exception e) {
            log.warn("scheduled identify_only reconcile failed", e);
        }
    }
}
