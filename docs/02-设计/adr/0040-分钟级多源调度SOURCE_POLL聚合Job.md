# ADR-0040：分钟级多源调度——复用 ManagedJob 体系扩展 SOURCE_POLL 聚合 Job（否决每源一 Job 与新调度器）

- 状态：已接受（2026-09-22，V2.0-M13 源接入框架）
- 日期：2026-09-22
- 决策人：架构设计师（依据见[技术方案-V2.0-M13-源接入框架](/docs/02-设计/技术方案-V2.0-M13-源接入框架.md) §3.3/§4.4）
- 关联：ADR-0017（JobScheduler/JobExecutor/JobRegistry 调度中心，本批直接复用）、ADR-0036（JobRunStats 留痕计数通道，tick 明细复用）、ADR-0038（info_source 表，调度配置单点）、ADR-0039（游标与事务边界）、V2.0 蓝图裁决 2（「实时」= 分钟级轮询，秒级 Won't）

## 📌 背景

V2.0 要求分钟级不间断感知：快讯源 1~5 分钟可配轮询、错峰、退避恢复、断流补抓、7×24 不间断；30 源规模下单源失败不阻塞其他源。现状 ManagedJob 体系（ADR-0017）是「每 Job 一键」的静态注册模型（JobRegistry 收 Spring bean，调度 15s~小时级间隔），未评估过「30+ 动态源 × 1 分钟级」量级。架构三选一：每源一 ManagedJob / 聚合单 Job 内多源调度 / 引入新调度器。

## 🎯 决策

**一个 `SOURCE_POLL` 聚合 ManagedJob（FIXED_DELAY tick，默认 60s，`job.SOURCE_POLL` 可配 15s~300s，第 8 个 job 键），源维度调度内聚在应用层 `SourceSchedulingService`**：

1. **tick 循环**：每 tick 现读启用源（enabled=1 AND deleted=0——配置热生效由「现读」天然成立，无需 ConfigCenter）→ 到期判定 `now ≥ max(next_due_at, backoff_until)` → 有界线程池派发（**全局并发上限 4**，代码常量）→ 单源 inFlight 集合防重派 → 等待派发完成（预算 45s，超时不中断在飞轮）→ `JobRunStats` 上报 `dispatched=n; ok=n; fail=n`（ADR-0036 段式惯例）。
2. **每源独立频控与错峰**：interval_minutes（1~60）即频控（下限即礼貌抓取底线）；预置源种子 `next_due_at = now + hash(source_code) mod interval` 错峰；重启后 overdue 源以 `rand(0..min(interval, 5min))` 抖动重铺防停机后齐发。
3. **退避与恢复**（替代熔断/降级链——资讯源无「备源同容」语义，见 ADR-0038）：连续失败 n 次 → 延迟 = interval × 2^min(n,4)，封顶 60min（backoff_until）；到点即探活（退避截止本身就是探活时机）；成功清零。超时 5s/源、重试 0（轮询型下轮即重试）。首败与每 10 次旁路记 data_source_event（`info:{source_code}`，防刷屏节流）。
4. **增量游标与断流补抓**（与 ADR-0039 事务联动）：游标持久于 source_poll_state（类型由源声明：ID 数值 / TIME / NONE 纯靠去重）；正常轮取第 1 页 newest-first 遇已见止；`gap = now − last_success_at > 3 × interval` → 深翻至 maxBackfillPages（默认 3）；触页上限仍有未见条目 → `last_round_detail` 记 `backfill=truncated`（条目滚出源侧窗口物理不可补，显性标注不静默吞）。
5. **配套小改**：`JobScheduler` 池 5→8（tick 长驻占 1 线程，防与既有 7 Job 抢占；一个常量）。

## ⚖️ 候选方案对比

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| A 每源一 ManagedJob | 单源留痕/触发独立 | JobRegistry 需动态注册改造；interval 在 `job.{KEY}` 与 info_source **双写漂移**；任务中心 30+ 行噪音；job_execution_log 每 1min×30 源 ≈4.3 万行/日（现全量表 30d 才 ~11 万行，留痕表被单功能刷爆） | ❌ 否决 |
| B **聚合 SOURCE_POLL 单 Job + 域内多源调度（本 ADR）** | 调度中心/任务中心/留痕通道零改造（第 8 个 ManagedJob）；配置单点 info_source；留痕面 = 每 tick 一行（1440/日，可控且带 dispatched/ok/fail 明细）；并发/退避/游标域内聚可测 | tick 内多源并发调度逻辑需自研（~2 人天，含退避/错峰/防重派） | ✅ 采用 |
| C 引入 Quartz / db-scheduler | 持久化调度、misfire 策略成熟 | 新依赖 + 新表 + 新运维面；单机 SQLite 个人量级过设计；五维度：成熟度高但学习成本/运维成本/可替换性全面劣于扩展自有成熟件 | ❌ 否决 |
| D 绕过 JobScheduler 自建 @Scheduled 动态注册 | 灵活 | 重复造 JobScheduler 已有能力；失去任务中心可视/统一留痕/热切换 | ❌ 否决 |

## 💡 理由

- **五维度**（B vs C）：B 零新依赖（成熟度/社区用既有的账）、零学习成本、可替换性最好（域服务是纯 Java 可迁）、运维零新增；C 每项都要付钱，换来的 misfire 持久化对「丢一轮下轮补」的轮询语义无增量价值——游标 + 幂等入库已经覆盖。
- **「不重不漏」由构造保证**：tick 重入被 ManagedJob CAS 守卫 + inFlight 双层拦截；源轮重拉被唯一索引幂等吸收（ADR-0039）；游标只随条目事务推进。
- **留痕经济学**：聚合把留痕粒度从「源×轮」降为「tick」，30 源规模化后仍在线性可控面（1440 行/日），细节下沉 last_round_detail / data_source_event 两级。

## ⚠️ 影响

- 正面：分钟级调度能力（1 分钟粒度，受 tick 下限 15s 与源 interval 下限 1min 约束）零新依赖落地；任务中心第 8 行自动出现（启停/手动触发 tick 复用）；既有 7 Job 仅感知池 +3 线程。
- 负面/代价：tick 粒度附加延迟 = 平均 tick/2（60s tick 下 ≤30s，对 P50 ≤5min 目标占比 10%）；单 tick 预算 45s 内未完成的源轮靠 inFlight 兜底（不丢，下轮判定）；调度正确性测试面集中在域服务（T103 单测组覆盖）。
- 行为兼容：七 Job 调度/留痕/热切换字节级不变；`job.SOURCE_POLL` 走通用 job 域校验零特例（测试 profile 种子 false 零注册，同惯例）。

## 🔍 后续验证方式

- T103 单测：到期 max 语义 / 退避序列封顶与清零 / inFlight 防重派 / tick 超时不中断 / 重启抖动重铺 / 停用与软删下一 tick 摘除 / 游标三种类型与补抓深翻。
- T106 实测：金十/新浪 5min 间隔连续观察 ≥2h，出数间隔与源发布对齐；人为断网 30min 恢复后补抓不重不漏（蓝图故事 2 场景 2）。
- T107 回归：七 Job 零变化；`mvn verify` 1520 基线只增。
