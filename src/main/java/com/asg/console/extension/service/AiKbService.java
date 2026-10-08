/*
 * Copyright (c) 2026 WntASG Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asg.console.extension.service;

import java.util.List;

import com.asg.console.extension.controller.dto.KbMeta;
import com.asg.console.extension.kb.KbBundle;

/**
 * AI 分类知识库（KB）版本服务：导入（离线/在线）、版本查询、回滚。
 *
 * <p>安全约束：
 * <ul>
 *   <li>导入/在线更新受授权门控（{@link KbLicenseService#canUpdate()}），无授权即拒绝；</li>
 *   <li>导入包必须通过厂商签名验签，验签失败即拒绝；</li>
 *   <li>整库加密落盘，明文只在内存匹配路径出现；对外只返回脱敏 {@link KbMeta}；</li>
 *   <li>回滚只在既有已验签版本间切换 active，不引入新内容，故不受授权门控限制
 *       （授权过期时的安全阀）。</li>
 * </ul>
 */
public interface AiKbService {

    /**
     * 导入并应用一个 {@code .wnt} 加密签名的 KB 更新包（离线上传与在线拉取共用的唯一入口）。
     *
     * <p>流水线：授权门控 → 外层 signtool 头校签（厂商公钥）+ 内层 AES-256-GCM 解密（对称密钥）
     * → 解析校验 → 加密落库 → 版本切换 → 网关同步。真实性由校签层完成，任一前置不过即整体
     * 拒绝，保持最后一次有效库不变。
     *
     * @param base64Wnt Base64 的 {@code .wnt} 容器字节（链路只传输密文，不含域名明文）
     * @param source    offline / online
     * @return 生效（或去重命中的当前 active）版本的脱敏视图
     */
    KbMeta importWntBundle(String base64Wnt, String source, String operator);

    /** 在线拉取最新 KB 并导入（受授权门控 + 验签）。 */
    KbMeta onlineUpdate(String operator);

    /** 版本历史（新→旧，脱敏）。 */
    List<KbMeta> listVersions();

    /** 当前生效版本脱敏视图；无则 null。 */
    KbMeta getActiveMeta();

    /** 当前生效版本的明文 bundle（仅供内部下发/匹配，绝不外泄接口）；无则 null。 */
    KbBundle getActiveBundle();

    /** 回滚到指定版本号（既有已验签版本间切换 active）。 */
    KbMeta rollback(Long versionNo, String operator);

    /**
     * 把当前 active KB 的分类库同步到 ai-shadow-detect 网关 WasmPlugin CR。
     * 导入/回滚后会自动调用；本方法供同步失败时手动重试。
     *
     * @return true 表示已写入 CR
     */
    boolean syncActiveToGateway();
}
