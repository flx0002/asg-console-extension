package com.asg.console.extension.config;

import com.asg.console.extension.service.AuditChainService;
import com.asg.console.extension.service.BehaviorAnalysisService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ASG scheduled tasks, extracted from AISecGw-console SdkConfig so that
 * the upstream config class stays untouched. Methods are no-arg as required
 * by Spring's {@link Scheduled} annotation.
 */
@Component
public class AsgScheduledTasks {

    private final AuditChainService auditChainService;
    private final BehaviorAnalysisService behaviorAnalysisService;

    public AsgScheduledTasks(AuditChainService auditChainService,
            BehaviorAnalysisService behaviorAnalysisService) {
        this.auditChainService = auditChainService;
        this.behaviorAnalysisService = behaviorAnalysisService;
    }

    /**
     * Run expired audit log cleanup every hour.
     */
    @Scheduled(fixedRate = 3600000)
    public void scheduledAuditCleanup() {
        auditChainService.cleanupExpiredLogs();
    }

    /**
     * 行为画像增量构建，每 60 秒触发一次（方案 5.7）。
     */
    @Scheduled(fixedRate = 60000)
    public void scheduledProfileUpdate() {
        try {
            behaviorAnalysisService.rebuildProfiles();
        } catch (Exception e) {
            // 静默吞掉异常，避免调度器因异常终止后续执行
        }
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
}
