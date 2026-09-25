/**
 * 分析管道限界上下文（M15，ADR-0046）：L0 规则预筛 → L1 批量归类 → L2 事件提取 → L3 热度聚合。
 *
 * <p>领域层承载四实体、35 行业枚举目录（校验权威）、L0/L1/L2 纯函数域服务与仓储端口； 应用层（{@code application.analysis}）承载批窗口编排与 LLM
 * 批量调用；基础设施层（{@code infrastructure.analysis}）承载 SQLite 落地件。 {@code news_item} 与 feed 域零改动（裁决 1）。
 */
package com.info.platform.domain.analysis;
