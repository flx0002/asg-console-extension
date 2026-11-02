/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.asg.console.extension.model.AiShadowDetectEvent;

@Repository
public interface AiShadowDetectEventRepository extends JpaRepository<AiShadowDetectEvent, Long>,
    JpaSpecificationExecutor<AiShadowDetectEvent> {

    Page<AiShadowDetectEvent> findByDomainContaining(String domain, Pageable pageable);

    Page<AiShadowDetectEvent> findByStatus(String status, Pageable pageable);

    Page<AiShadowDetectEvent> findByCategory(String category, Pageable pageable);

    Page<AiShadowDetectEvent> findByRiskLevel(String riskLevel, Pageable pageable);

    Page<AiShadowDetectEvent> findBySource(String source, Pageable pageable);

    List<AiShadowDetectEvent> findByEventTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 检测趋势聚合：按「距窗口起点多少个桶」分组。桶序号只取决于两个 epoch 之差，
     * 因此 JVM 时区不影响分桶结果（容器现为 Asia/Shanghai，此前为 UTC，两种都不影响）。
     * <p>
     * 注意：{@code UNIX_TIMESTAMP(event_time)} 的取值依赖「MySQL 会话时区」与
     * 「event_time 写入时的时区字面值」一致（现网 MySQL 容器 TZ=Asia/Shanghai，
     * 实测 11:40:38 存储值 ↔ 1792726838 绝对秒一致）。若将来这两者不一致，
     * 整条曲线会整体偏移，需要改为在应用层显式按业务时区换算。
     * 返回列：bucketIdx（相对 start 的桶序号）、status、事件数。
     */
    @Query(value = "SELECT FLOOR((UNIX_TIMESTAMP(event_time) - :startSec) / :stepSec) AS bucket_idx,"
        + " status, COUNT(*) AS cnt"
        + " FROM ai_shadow_detect_event"
        + " WHERE event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        // 空串表示「不按 surface 过滤」。用空串而不是 null：`null = ''` 得到 NULL，
        // 整条 WHERE 会静默变成空集——那会表现成「窗口内没有检测到」，比报错更难查。
        + " AND (:surface = '' OR surface = :surface)"
        + " GROUP BY bucket_idx, status", nativeQuery = true)
    List<Object[]> countByBucketAndStatus(@Param("startSec") long startSec, @Param("endSec") long endSec,
        @Param("stepSec") long stepSec, @Param("surface") String surface);

    /**
     * 未授权访问按域名聚合。风险等级与处置状态取「最严重的一档」：FIELD 返回 1..n（1 最严重），
     * 因此必须用 MIN(NULLIF(...,0))——用 MAX 会取到最不严重的一档，把 critical 显示成 low。
     * NULLIF 用于剔除 FIELD 返回的 0（值不在已知枚举内），避免未知值把结果拉成 0。
     * 这样也就不依赖 Prometheus 指标里的 risk 标签——该标签在网关侧实际并不存在。
     * 同时带出 critical / high / blocked 的事件数，KPI 用同一份结果累加，
     * 保证「统计卡片」与「下方列表」永远同源同口径。
     * 返回列：domain、事件数、riskRank、statusRank、最近发生时间（epoch 秒）、category、
     *        critical 数、high 数、blocked 数。
     */
    @Query(value = "SELECT domain, COUNT(*) AS cnt,"
        + " MIN(NULLIF(FIELD(risk_level, 'critical', 'high', 'medium', 'low'), 0)) AS risk_rank,"
        + " MIN(NULLIF(FIELD(status, 'blocked', 'alerted', 'monitored', 'masked', 'allowed'), 0)) AS status_rank,"
        // category 优先取「有真实分类的那一条」：同一域名可能同时被 ai-shadow-detect
        // 上报为 saas_ai、被 key-auth 上报为写死的 unknown，而 MAX('unknown') 在字典序上
        // 大于 MAX('saas_ai')，会让页面把有分类的域名显示成 unknown。全部为 unknown 时兜底。
        + " UNIX_TIMESTAMP(MAX(event_time)) AS last_seen,"
        + " COALESCE(MAX(CASE WHEN category IS NOT NULL AND category <> 'unknown' THEN category END),"
        + " MAX(category)) AS category,"
        + " SUM(CASE WHEN risk_level = 'critical' THEN 1 ELSE 0 END) AS critical_cnt,"
        + " SUM(CASE WHEN risk_level = 'high' THEN 1 ELSE 0 END) AS high_cnt,"
        + " SUM(CASE WHEN status = 'blocked' THEN 1 ELSE 0 END) AS blocked_cnt"
        + " FROM ai_shadow_detect_event"
        + " WHERE event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        + " AND (:surface = '' OR surface = :surface)"
        + " GROUP BY domain ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> aggregateByDomain(@Param("startSec") long startSec, @Param("endSec") long endSec,
        @Param("surface") String surface);

    /**
     * 准入违规明细：受管通道上被拦 / 被监控 / 被放行的准入事件，按「域名 + 原因 + 处置」聚合。
     * <p>
     * 这里刻意不按 AI Route 分组：事件表没有 route 归属（{@code route_hint} 至今全为 NULL），
     * AI Route 的 Ingress 本身也没有 host，两者无法据实对齐。按域名分组是数据能支撑的最小谎言。
     * <p>
     * 返回列：domain、reason（NULL 转空串）、status、事件数、最近发生时间（epoch 秒）。
     * LIMIT 500 是防爆保护：页面按分组展示，不需要全量逐条。
     */
    @Query(value = "SELECT domain, COALESCE(reason, '') AS reason, COALESCE(status, '') AS status,"
        + " COUNT(*) AS cnt, UNIX_TIMESTAMP(MAX(event_time)) AS last_seen"
        + " FROM ai_shadow_detect_event"
        + " WHERE surface = 'route'"
        + " AND event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        + " GROUP BY domain, reason, status ORDER BY cnt DESC LIMIT 500", nativeQuery = true)
    List<Object[]> aggregateAdmission(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /**
     * 两个 tab 各自有多少条（不受调用方 surface 过滤影响）。
     * 检测页要用它显示「另有准入违规 N 条」，否则用户会以为影子AI 只有绕道这一种。
     * 返回列：surface、事件数、域名数。
     */
    @Query(value = "SELECT COALESCE(surface, '') AS surface, COUNT(*) AS cnt,"
        + " COUNT(DISTINCT domain) AS domain_cnt"
        + " FROM ai_shadow_detect_event"
        + " WHERE event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        + " GROUP BY surface", nativeQuery = true)
    List<Object[]> countBySurface(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /**
     * 两条通道各自阻断了几条。KPI 的「已拦截」与「拦截率」是跨通道的合计，
     * 不能只读调用方传进来的 surface —— 那样在只看基础流量时会显示 0，
     * 而路由准入侧明明拦下了 11 条。
     * 返回列：surface、blocked 事件数。
     */
    @Query(value = "SELECT COALESCE(surface, '') AS surface, COUNT(*) AS cnt"
        + " FROM ai_shadow_detect_event"
        + " WHERE status = 'blocked'"
        + " AND event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        + " GROUP BY surface", nativeQuery = true)
    List<Object[]> countBlockedBySurface(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /**
     * 准入事件按「原因 + 处置」分组，供消费者用量表把拦截数归属到人。
     * <p>
     * 不能用 {@link #aggregateAdmission} 顶替：那个按 domain 分组，而消费者只存在于
     * reason（{@code not_in_allow:<名>}）里，域名维度上没有身份信息。
     * 返回列：reason（NULL 转空串）、status（NULL 转空串）、事件数。
     */
    @Query(value = "SELECT COALESCE(reason, '') AS reason, COALESCE(status, '') AS status,"
        + " COUNT(*) AS cnt"
        + " FROM ai_shadow_detect_event"
        + " WHERE surface = 'route'"
        + " AND event_time >= FROM_UNIXTIME(:startSec) AND event_time <= FROM_UNIXTIME(:endSec)"
        + " GROUP BY reason, status", nativeQuery = true)
    List<Object[]> countAdmissionByReason(@Param("startSec") long startSec, @Param("endSec") long endSec);
}
