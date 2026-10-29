/*
 * Copyright (c) 2022-2025 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.asg.console.extension.model;

import org.springframework.context.ApplicationEvent;

import lombok.Getter;

/**
 * 已登记 AI 通道的输入发生变化，需要重新派生网关白名单。
 *
 * <p>触发源有两类，缺一不可：
 *
 * <ul>
 *   <li><b>显式事件</b>（本事件）：授权域名被写入。授权域名是 S1b 的唯一输入，两条写入口
 *       （{@code PUT /v1/ai-shadow/authorized-domains} 与 {@code PUT /v1/ai-shadow/detect/dns-policy}）
 *       都要发一次，否则界面上的授权要等到 Console 重启才在网关生效。</li>
 *   <li><b>Ingress 轮询</b>：AI Route 的增删改最终都同步落地为 Ingress，Console 无法在禁改的上游
 *       controller 里埋点，因此由 {@code AiChannelDerivationService} 定时比对 Ingress 指纹。</li>
 * </ul>
 *
 * <p>事件只是「有变化」的信号，不携带数据：派生一律重新全量计算，避免把事件里的增量快照当作事实
 * （并发写入时事件顺序不可靠，只有重读才是准的）。
 */
@Getter
public class AiChannelChangeEvent extends ApplicationEvent {

    /** 触发原因，仅用于日志定位，不参与派生逻辑。 */
    private final String reason;

    public AiChannelChangeEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "AiChannelChangeEvent{reason=" + reason + '}';
    }
}
