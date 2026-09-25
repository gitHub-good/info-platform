# ADR-0041：FeedFetcher 端口返回 FetchResult 携带补抓截断信号

- 状态：已接受（2026-09-25，V2.0-M13 源接入框架 T101 实现偏差）
- 日期：2026-09-25
- 决策人：开发工程师（M13 方案 §4.3 端口草案的实现级精化，依据[技术方案-V2.0-M13](/docs/02-设计/技术方案-V2.0-M13-源接入框架.md) §3.3/§4.4）
- 关联：ADR-0039（资讯统一库与去重）、ADR-0040（SOURCE_POLL 调度——断流补抓与截断可观测）、ADR-0036（留痕明细段式惯例）

## 📌 背景

M13 方案 §4.3 的取数端口草案为 `List<RawFeedItem> fetch(InfoSource source, FetchContext ctx)`； 同时 §3.3/§4.4 要求：「断流深翻补抓触
maxBackfillPages 页上限仍有未见条目 → last_round_detail 记 `backfill=truncated`（漏抓风险显性化）」。 「是否触顶」只有引擎知道（页面循环在引擎内），条目列表本身无法承载该事实——摄取服务若从「条目数是否满页」推断， 是模糊启发式（源侧最后一页恰好半满时会误判）。

## 🎯 决策

端口返回值从 `List<RawFeedItem>` 精化为领域值对象：

```java
public record FetchResult(List<RawFeedItem> items, boolean truncated) {}
```

- `items`：newest-first、已按游标止步过滤的条目（语义与草案一致）；
- `truncated`：引擎深翻触页上限仍未确认追平（含 RSS 单文档无法深翻、游标早于文档最旧条目的场景）。
- 端口签名其余部分（`InfoSource` + `FetchContext(cursorValue, pages)`）与方案 §4.3 一致；`FeedFetcher.fetchFirstPage` 便捷重载保持「等价于取 List」的旧用法。

## ⚖️ 影响与理由

- 实现：`RssFeedFetcher` / `JsonApiFeedFetcher` / `PresetFeedAdapter`（含 T106 SinaZhiboAdapter）统一返回 `FetchResult`；`FeedIngestService` 以 `truncated` 写 `last_round_detail` 的 `backfill=active|truncated|none` 段。
- 为什么不等方案改稿再落地：该偏差不改变契约语义（仍是「取条目」），只补齐方案自身已经要求、但草案签名装不下的可观测信号； 属于实现级精化，单 ADR 留档即可（对齐既有 ADR-0023/0025 实现偏差系列）。
- 对 T105 前端契约无影响（端点仍为 §4.5 冻结的 REST 契约）。

## 🔍 后续验证方式

- T101/T102 单测：`fetch_backfillPagesNoSeenItem_marksTruncated` 等用例断言截断信号；
- T107 验收：断流恢复场景 `last_round_detail` 出现 `backfill=truncated` 且事件留痕。
