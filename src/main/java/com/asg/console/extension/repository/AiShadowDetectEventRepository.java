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
     * 会话折叠视图（别名 {@code q}）：一行 = 一个会话 = 一条告警，流水表一行不删。
     *
     * <p>会话键就是上报的 {@code session_id}，不再叠加时间桶。旁路 {@code b5:} 是 5 元组
     * （L4 协议 / 源 IP / 源端口 / 目的 IP / 目的端口）的确定性 hash，5 元组完全相同即同一
     * 会话；网关侧 {@code cx:} 兜底自带 30min 桶，其会话边界由数据面决定。阶段 2 之前写入的
     * 历史行没有 session_id，用 {@code row:<id>} 让每条自成一组，避免误合并不相干的历史事件。
     *
     * <p>刻意不用 {@code LAG(event_time)} 做空闲切分：现网实测四层派生表 + 三组窗口函数会把
     * MySQL 内存 cgroup 打满、服务拒连（单次查询 6 分钟未完成）。这里只有普通 GROUP BY 聚合，
     * 实测 1.5 秒内返回，不依赖窗口函数。
     *
     * <p>{@code session_domain / reason / source / src_ip} 取会话内最后一条或确定性的聚合值，
     * 保证每个会话只归属一个维度值；否则按域名等分组时同一会话会被重复计数，KPI 与列表对不上。
     * {@code session_status / session_risk} 取会话内最严重一档（FIELD 序号最小者）。
     *
     * <p>{@code session_reason} 取会话内最近一条非空 reason：拼成「12 位整数秒 + 12 位 id
     * + reason」后取 MAX，再用 SUBSTRING(...,25) 剥掉 24 字符定长前缀。用 MAX 而非
     * GROUP_CONCAT 是为了避开 group_concat_max_len（单会话已有 3676 行）和逗号截断；
     * 时间戳必须 CAST 成 UNSIGNED，否则带微秒的值被 LPAD 截断成 "1794276816.8"、
     * 无微秒的值补成 "001794276816"，字典序会错乱。
     *
     * <p>内层只扫描查询窗口，{@code COALESCE(:startSec, 0)} / {@code COALESCE(:endSec, ...)}
     * 让「未传时间窗」等价于不限上下界，与旧查询契约一致；外层仍按会话首见时间收口。
     */
    String SESSION_FOLD =
        " FROM (SELECT s.session_ref,"
        + "   MAX(s.id) AS rep_id, COUNT(*) AS hit_cnt,"
        + "   MIN(s.event_time) AS first_seen, MAX(s.event_time) AS last_seen,"
        + "   SUBSTRING_INDEX(GROUP_CONCAT(s.domain ORDER BY s.event_time DESC, s.id DESC"
        + "       SEPARATOR ','), ',', 1) AS session_domain,"
        + "   NULLIF(SUBSTRING(MAX(CASE WHEN s.reason IS NOT NULL AND s.reason <> ''"
        + "       THEN CONCAT(LPAD(CAST(UNIX_TIMESTAMP(s.event_time) AS UNSIGNED), 12, '0'),"
        + "                  LPAD(s.id, 12, '0'), s.reason) END), 25), '') AS session_reason,"
        + "   MAX(s.source) AS session_source, MAX(s.src_ip) AS session_src_ip,"
        + "   MIN(s.surface) AS session_surface,"
        + "   COALESCE(MAX(CASE WHEN s.category IS NOT NULL AND s.category <> 'unknown'"
        + "       THEN s.category END), MAX(s.category)) AS session_category,"
        + "   COALESCE(ELT(MIN(NULLIF(FIELD(s.status,"
        + "       'blocked', 'alerted', 'monitored', 'masked', 'allowed'), 0)),"
        + "       'blocked', 'alerted', 'monitored', 'masked', 'allowed'), MAX(s.status)) AS session_status,"
        + "   COALESCE(ELT(MIN(NULLIF(FIELD(s.risk_level, 'critical', 'high', 'medium', 'low'), 0)),"
        + "       'critical', 'high', 'medium', 'low'), MAX(s.risk_level)) AS session_risk"
        + " FROM (SELECT e.*,"
        + "     COALESCE(NULLIF(e.session_id, ''), CONCAT('row:', e.id)) AS session_ref"
        + "   FROM ai_shadow_detect_event e"
        + "   WHERE e.event_time >= FROM_UNIXTIME(COALESCE(:startSec, 0))"
        + "     AND e.event_time <= FROM_UNIXTIME(COALESCE(:endSec, 4102444800))) s"
        + " GROUP BY s.session_ref) q";

    /** 会话窗口的公共收口：按会话首见时间归属，趋势分桶不会出现负桶。空串参数 = 不过滤。 */
    String SESSION_WINDOW =
        " WHERE q.first_seen >= FROM_UNIXTIME(COALESCE(:startSec, 0))"
        + " AND q.first_seen <= FROM_UNIXTIME(COALESCE(:endSec, 4102444800))";

    /**
     * 检测趋势聚合：按「距窗口起点多少个桶」分组。桶序号只取决于两个 epoch 之差，
     * 因此 JVM 时区不影响分桶结果（容器现为 Asia/Shanghai，此前为 UTC，两种都不影响）。
     * <p>
     * 注意：{@code UNIX_TIMESTAMP(event_time)} 的取值依赖「MySQL 会话时区」与
     * 「event_time 写入时的时区字面值」一致（现网 MySQL 容器 TZ=Asia/Shanghai，
     * 实测 11:40:38 存储值 ↔ 1792726838 绝对秒一致）。若将来这两者不一致，
     * 整条曲线会整体偏移，需要改为在应用层显式按业务时区换算。
     * <p>
     * 会话口径：一个会话按其首见时间落一个桶、按会话内最严重处置着色，跨桶不重复计数。
     * 返回列：bucketIdx（相对 start 的桶序号）、status、会话数。
     */
    @Query(value = "SELECT FLOOR((UNIX_TIMESTAMP(q.first_seen) - :startSec) / :stepSec) AS bucket_idx,"
        + " q.session_status AS status, COUNT(*) AS cnt"
        + SESSION_FOLD + SESSION_WINDOW
        + " AND (:surface = '' OR q.session_surface = :surface)"
        + " GROUP BY bucket_idx, status", nativeQuery = true)
    List<Object[]> countByBucketAndStatus(@Param("startSec") long startSec, @Param("endSec") long endSec,
        @Param("stepSec") long stepSec, @Param("surface") String surface);

    /**
     * 未授权访问按域名聚合。风险等级与处置状态取「最严重的一档」：FIELD 返回 1..n（1 最严重），
     * 因此必须用 MIN(NULLIF(...,0))——用 MAX 会取到最不严重的一档，把 critical 显示成 low。
     * NULLIF 用于剔除 FIELD 返回的 0（值不在已知枚举内），避免未知值把结果拉成 0。
     * 这样也就不依赖 Prometheus 指标里的 risk 标签——该标签在网关侧实际并不存在。
     * <p>
     * 会话口径：按 {@code session_domain} 分组（每个会话只归属一个域名），直接 COUNT(*)，
     * KPI 与下方列表永远同源同口径，不会出现同一会话在多个域名下重复计数。
     * 返回列：domain、会话数、riskRank、statusRank、最近发生时间（epoch 秒）、category、
     *        critical 会话数、high 会话数、blocked 会话数。
     */
    @Query(value = "SELECT q.session_domain AS domain, COUNT(*) AS cnt,"
        + " MIN(NULLIF(FIELD(q.session_risk, 'critical', 'high', 'medium', 'low'), 0)) AS risk_rank,"
        + " MIN(NULLIF(FIELD(q.session_status, 'blocked', 'alerted', 'monitored', 'masked', 'allowed'), 0))"
        + "     AS status_rank,"
        + " UNIX_TIMESTAMP(MAX(q.last_seen)) AS last_seen,"
        + " MAX(q.session_category) AS category,"
        + " SUM(CASE WHEN q.session_risk = 'critical' THEN 1 ELSE 0 END) AS critical_cnt,"
        + " SUM(CASE WHEN q.session_risk = 'high' THEN 1 ELSE 0 END) AS high_cnt,"
        + " SUM(CASE WHEN q.session_status = 'blocked' THEN 1 ELSE 0 END) AS blocked_cnt"
        + SESSION_FOLD + SESSION_WINDOW
        + " AND (:surface = '' OR q.session_surface = :surface)"
        + " GROUP BY q.session_domain ORDER BY cnt DESC", nativeQuery = true)
    List<Object[]> aggregateByDomain(@Param("startSec") long startSec, @Param("endSec") long endSec,
        @Param("surface") String surface);

    /**
     * 准入违规明细：受管通道上被拦 / 被监控 / 被放行的准入事件，按「域名 + 原因 + 处置」聚合。
     * <p>
     * 这里刻意不按 AI Route 分组：事件表没有 route 归属（{@code route_hint} 至今全为 NULL），
     * AI Route 的 Ingress 本身也没有 host，两者无法据实对齐。按域名分组是数据能支撑的最小谎言。
     * <p>
     * 口径说明：此接口服务独立的「准入违规」页面，不在影子AI 事件页的会话折叠范围内，
     * 因此保留逐条流水计数；事件页的 KPI 分面走 {@link #countAdmissionByReason} 的会话口径。
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
     * 两个 tab 各自有多少个会话（不受调用方 surface 过滤影响）。
     * 检测页要用它显示「另有准入违规 N 条」，否则用户会以为影子AI 只有绕道这一种。
     * 返回列：surface、会话数、会话归属域名数。
     */
    @Query(value = "SELECT COALESCE(q.session_surface, '') AS surface, COUNT(*) AS cnt,"
        + " COUNT(DISTINCT q.session_domain) AS domain_cnt"
        + SESSION_FOLD + SESSION_WINDOW
        + " GROUP BY surface", nativeQuery = true)
    List<Object[]> countBySurface(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /**
     * 两条通道各拦下了几个会话。KPI 的「已拦截」与「拦截率」是跨通道的合计，
     * 不能只读调用方传进来的 surface —— 那样在只看基础流量时会显示 0，
     * 而路由准入侧明明拦下了 11 条。
     * 返回列：surface、blocked 会话数。
     */
    @Query(value = "SELECT COALESCE(q.session_surface, '') AS surface,"
        + " SUM(CASE WHEN q.session_status = 'blocked' THEN 1 ELSE 0 END) AS cnt"
        + SESSION_FOLD + SESSION_WINDOW
        + " GROUP BY surface", nativeQuery = true)
    List<Object[]> countBlockedBySurface(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /**
     * 准入事件按「原因 + 处置」分组，供消费者用量表把拦截数归属到人。
     * <p>
     * 不能用 {@link #aggregateAdmission} 顶替：那个按 domain 分组，而消费者只存在于
     * reason（{@code not_in_allow:<名>}）里，域名维度上没有身份信息。
     * 会话口径：按会话代表 reason / 最严重处置归属，COUNT(*) 即会话数。
     * 返回列：reason（NULL 转空串）、status（NULL 转空串）、会话数。
     */
    @Query(value = "SELECT COALESCE(q.session_reason, '') AS reason,"
        + " COALESCE(q.session_status, '') AS status, COUNT(*) AS cnt"
        + SESSION_FOLD + SESSION_WINDOW
        + " AND q.session_surface = 'route'"
        + " GROUP BY reason, status", nativeQuery = true)
    List<Object[]> countAdmissionByReason(@Param("startSec") long startSec, @Param("endSec") long endSec);

    /** 事件明细列表的会话维度筛选（空串 = 不过滤，避免动态拼 SQL 引入注入面）。 */
    String SESSION_FILTERS =
        " AND (:domain = '' OR q.session_domain LIKE CONCAT('%', :domain, '%'))"
        + " AND (:status = '' OR FIND_IN_SET(q.session_status, :status) > 0)"
        + " AND (:category = '' OR q.session_category = :category)"
        + " AND (:riskLevel = '' OR q.session_risk = :riskLevel)"
        + " AND (:source = '' OR q.session_source = :source)"
        + " AND (:surface = '' OR q.session_surface = :surface)"
        + " AND (:reason = '' OR q.session_reason LIKE CONCAT(:reason, '%'))"
        + " AND (:srcIp = '' OR q.session_src_ip LIKE CONCAT(:srcIp, '%'))";

    /**
     * 事件明细列表按会话折叠后的分页。筛选作用在「会话行」上（折叠后过滤），
     * 因此 {@code hit_cnt} 是会话内的全部流水条数，不随筛选变化；流水表本身一行不删。
     * 代表行取会话内 id 最大的一条，Service 据此回填实体与 transient 聚合字段。
     * 返回列：代表行 id、命中数、首见/末见（epoch 秒）、会话最高风险、会话最严重处置、
     * 会话内最近一条非空 reason（与筛选同口径，避免「筛得出却显示 -」）。
     */
    @Query(value = "SELECT q.rep_id, q.hit_cnt,"
        + " UNIX_TIMESTAMP(q.first_seen), UNIX_TIMESTAMP(q.last_seen),"
        + " q.session_risk, q.session_status, COALESCE(q.session_reason, '')"
        + SESSION_FOLD + SESSION_WINDOW + SESSION_FILTERS
        + " ORDER BY q.last_seen DESC, q.rep_id DESC"
        + " LIMIT :rowOffset, :rowLimit", nativeQuery = true)
    List<Object[]> aggregateSessions(@Param("startSec") Long startSec, @Param("endSec") Long endSec,
        @Param("domain") String domain, @Param("status") String status, @Param("category") String category,
        @Param("riskLevel") String riskLevel, @Param("source") String source, @Param("surface") String surface,
        @Param("reason") String reason, @Param("srcIp") String srcIp, @Param("rowOffset") int rowOffset,
        @Param("rowLimit") int rowLimit);

    /** 与 {@link #aggregateSessions} 同筛选条件下的会话总数（分页 total）。 */
    @Query(value = "SELECT COUNT(*) AS cnt"
        + SESSION_FOLD + SESSION_WINDOW + SESSION_FILTERS, nativeQuery = true)
    Number countSessions(@Param("startSec") Long startSec, @Param("endSec") Long endSec,
        @Param("domain") String domain, @Param("status") String status, @Param("category") String category,
        @Param("riskLevel") String riskLevel, @Param("source") String source, @Param("surface") String surface,
        @Param("reason") String reason, @Param("srcIp") String srcIp);
}
