package com.asg.console.extension.config;

import com.asg.console.extension.service.AgentGuardService;
import com.asg.console.extension.service.AgentGuardServiceImpl;
import com.asg.console.extension.service.AuditChainService;
import com.asg.console.extension.service.AuditChainServiceImpl;
import com.asg.console.extension.service.AuditLogCollectorService;
import com.asg.console.extension.service.BehaviorAnalysisService;
import com.asg.console.extension.service.BehaviorAnalysisServiceImpl;
import com.asg.console.extension.service.RedisAuditSyncService;
import com.asg.console.extension.service.ShadowAiService;
import com.asg.console.extension.service.ShadowAiServiceImpl;
import com.asg.console.extension.service.impl.AgentAuditPersistenceService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.alibaba.higress.sdk.service.consumer.ConsumerService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;
import com.asg.console.extension.support.UpstreamApiClientAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ASG service beans formerly hand-wired in AISecGw-console SdkConfig.
 * Migrated to this extension module so the upstream SdkConfig stays untouched.
 *
 * <p>All services are constructed directly (no dependency on the upstream
 * HigressServiceProvider extension points), consuming the standard beans
 * exposed by the upstream SdkConfig.
 */
@Configuration
@EnableScheduling
public class AsgServiceBeansConfiguration {

    @Bean
    public ShadowAiService shadowAiService(WasmPluginInstanceService wasmPluginInstanceService,
            ConsumerService consumerService, AiRouteService aiRouteService) {
        return new ShadowAiServiceImpl(wasmPluginInstanceService, consumerService, aiRouteService, null);
    }

    @Bean
    public AgentGuardService agentGuardService() {
        return new AgentGuardServiceImpl(null, 0);
    }

    @Bean
    public AuditChainService auditChainService(AgentAuditPersistenceService auditSink) {
        AuditChainServiceImpl service = new AuditChainServiceImpl(null, 0);
        service.setAuditLogSink(auditSink);
        return service;
    }

    @Bean
    public BehaviorAnalysisService behaviorAnalysisService() {
        return new BehaviorAnalysisServiceImpl(null, 0);
    }

    @Bean
    public AuditLogCollectorService auditLogCollectorService(KubernetesClientService kubernetesClientService,
            AuditChainService auditChainService) {
        return new AuditLogCollectorService(UpstreamApiClientAccessor.getApiClient(kubernetesClientService), auditChainService);
    }

    /**
     * Incremental Redis -> MySQL audit sync (IR-015): covers entries written
     * directly by Wasm plugins which bypass the stdout collector path.
     */
    @Bean
    public RedisAuditSyncService redisAuditSyncService(AgentAuditPersistenceService auditSink) {
        return new RedisAuditSyncService(null, 0, auditSink);
    }

    @Bean
    public ThreadPoolTaskScheduler auditCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("audit-cleanup-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler behaviorAnalysisTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("behavior-analysis-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        return scheduler;
    }
}
