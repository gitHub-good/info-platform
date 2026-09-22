# ADR-0013：事件源实现偏差（event-monitor 不存在，改读本地 anomaly_event 表）

- 状态：已接受（2026-09-22，T08）
- 日期：2026-09-22
- 决策人：开发工程师（依编排者核实结论 + T13 落地事实 + Spike-1 §6.7 契约要点）
- 补充：ADR-0003（统一数据访问层：SourceAdapter + 主数据映射）、技术方案 §4.3 流程 3、Spike-1 §6.7

## 📌 背景

技术方案任务表 T08 与 Spike-1 §6.7 原方案为「事件源 adapter（复用 event-monitor）」：复用用户环境既有事件监控能力（watch-positions event-monitor 技能），不直连外部源，`doFetch` 从既有事件入库表/接口取某标的相关重大事件；契约要点为本地调用 `noRetry(500ms)`、`sourceLabel = "事件监控"`。

约束与事实：

- **event-monitor 在当前环境不存在**（编排者已全局搜索确认），原方案的取数前提不成立。
- T13 异动检测引擎已落地：`@Scheduled` 每 10s 轮询自选标的行情 → 阈值触发 → 去重写入本地 `anomaly_event` 表（V5 迁移）→ 发 Spring 事件驱动 T14 推送。标的维度的异动/事件记录已在本地持续积累。
- `anomaly_event` 表已有 `idx_anomaly_subject_time(subject_id, trigger_time)` 索引，按标的查近期记录走索引。
- 详情页聚合契约（§4.1.1）原为 6 分区（quote/finance/valuation/announce/news/policy），事件数据此前在聚合接口中缺位。

## 🎯 决策

**T08 `EventSourceAdapter` 改读本地 `anomaly_event` 表**（仍不直连外部源，与原方案「本地取数」语义一致）：

- 经 push 域 `AnomalyRepository` 端口按标的查异动历史（trigger_time 降序），在 `doFetch` 内过滤「近期」7 天窗（与 PRD 场景 1「最近 7 天相关新闻」窗口口径一致）并截取 ≤10 条，映射为 `data.items` 列表（同公告/新闻/政策列表型分区契约）；窗口内无记录 → MISSING（不阻断）。
- 契约要点照旧：`ResilienceSpec.noRetry(500ms)`、`sourceLabel = "事件监控"`；降级走模板默认 MISSING。
- **聚合契约扩展 EVENT 第七分区**（追加式，不改既有 6 分区语义）：`SourceCode` 增 `EVENT`、`SubjectDetail` 增 `events` 数组、`sourceStatus` 增 `event` 三态、`sections` 参数支持 `event`；`SourceCache` 增 EVENT 分区（TTL 30s，介于异动检测 10s 轮询与详情页查询频次之间）。
- **不改既有表结构**：无新增迁移（V13/U13 不需要）；`data_source_event.source_code` 为 TEXT 列，EVENT 直接可记。

## ⚖️ 候选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 读本地 anomaly_event 表 | 前提成立（T13 已落地）；本地调用零外部依赖；索引支撑按标的查；与「事件分区有真实数据」目标直接对齐 | 事件覆盖面=异动引擎检测范围（首期 PRICE_CHANGE），非全量「重大事件」 | ✅ 采用 |
| 引入/自建外部事件源（财联社快讯等） | 事件面更全 | Spike-1 §8 已确认 cls.cn 端点变更停用（🔴）；新增外部依赖与限频风险，超出 T08 1 人天预算 | ❌ 否决，留待后续需求 |
| 事件分区继续缺位 | 零改动 | 详情页事件分区无数据、T15 事件入库链路上游缺失，任务目标未达成 | ❌ 否决 |

## 💡 理由

原方案的取数前提（环境既有 event-monitor）不存在，属实现偏差而非需求变更——PRD 对事件的能力要求（异动/事件可见、推送 ≤15s/≤30s）不变。T13 已把标的维度异动事件持续写入本地表，读取该表即可让详情页事件分区获得真实数据，且保持「本地调用、不直连外部源」的原设计精神与 §6.7 契约要点（noRetry 500ms、sourceLabel「事件监控」）。跨域取数走 push 域 `AnomalyRepository` 端口（领域层公开接口，非内部类），与既有 `infrastructure/subscription → domain.push.SubscriptionResolver` 先例一致，不破坏 DDD 分层约定。聚合契约按追加式扩展 EVENT 分区，既有 6 分区语义与前端兜底行为不变。

对照选型五维度：成熟度（本地读表 + 既有端口）、社区（无新增依赖）、学习成本（与既有六源 adapter 同构）、可替换性（后续接入真实事件源仅换 doFetch 取数路径，模板/映射契约不变）、运维成本（零，无外部 QPS）。

## ⚠️ 影响

- 正面影响：详情页事件分区（第七分区）获得真实数据；mock 模式补 `MockEventSourceAdapter` 与其余六源对齐；T15 事件入库→推送链路的「事件可见」上游补齐。
- 负面影响 / 代价：事件覆盖面首期限于异动引擎检测范围（PRICE_CHANGE；VOLUME/EVENT 类型为 T13 预留枚举，检测规则落地后自动进入分区）；`AnomalyType.EVENT` 记录暂无生产来源。
- 需要跟进的事项：若后续引入真实外部事件源（重大公告/快讯入库，Spike-1 §8 财联社端点重定位后再评估），新增 ADR 标记替代关系，`EventSourceAdapter` 仅换 `doFetch` 取数路径。
- 接口契约影响：`GET /api/v1/subjects/{id}/detail` 响应新增 `events` 数组与 `sourceStatus.event`，`sections` 支持 `event`——均为追加字段，向后兼容。

## 🔍 后续验证方式

- `EventSourceAdapterTest` 覆盖主路径（近期有记录→OK 字段逐项映射）/ 边界（无记录→MISSING、全过期→MISSING、窗口含边界、>10 条截断）/ 异常（仓储抛异常→降级 MISSING 不外抛）。
- `AggregationServiceTest`/`SubjectControllerTest` 锁定 events 分区与 sourceStatus 三态契约；前端 `SubjectDetail.test.tsx` 锁定事件分区渲染。
- 上线后观察 `data_source_event` 中 EVENT 分区 MISSING/ERROR 频次（T16 旁路已自动覆盖 EVENT）。

## ♻️ 替代记录

（无；待引入真实外部事件源时以新 ADR 替代「读本地 anomaly_event」的取数路径，事件分区契约不变。）
