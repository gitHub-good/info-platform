# ADR-0033：数据源降级链模型——fallbackChain DB 配置优先、代码内置默认链只做兜底 + backupSource 旧键兼容折算

- 状态：已接受（2026-09-24，M7 后降级策略页面可视化）
- 日期：2026-09-24
- 决策人：开发工程师（依用户决策「数据源配置降级策略也进行页面可视化，在逻辑中进行替换数据库中的，默认固定死的作为兜底」）
- 补充：ADR-0030（A 股列表源备选）、ADR-0031（行情/估值腾讯备选源）、ADR-0032（开关热化 + DataSourceDefaults 单一事实源）

## 📌 背景

ADR-0030/0031 落地后三个备选源开关均为**单值枚举**（`auto | 强制单源`）：只能表达「按默认顺序降级」或「锁死一个源」，无法表达「东财挂了先试腾讯、腾讯也挂不要兜底」「腾讯为主源、东财为备选」「清空备选仅主源」等顺序语义；且开关散在两处键空间（`datasource.{QUOTE,VALUATION}.params.backupSource` 与 `subject.sync.aShareSource`），页面只能透传 params 编辑，无可视化。用户点名：**DB 配置的降级策略优先，代码固定默认仅兜底**。

## 🎯 决策

**降级策略统一升级为有序 `fallbackChain` 数组模型**（页面可视化 + 引擎按链执行），旧单值开关保留读取兼容、逐步淘汰：

### 1. 模型与注册表

- `datasource.{CODE}` 增字段 `fallbackChain: string[]`（**有序**，首元素 = 主源；空数组 = 仅主源，缺省 = 折算/兜底）；A 股列表桶（`subject.sync` 文档）同款增 `fallbackChain`，缺省 `["eastmoney","sina"]`
- **每源可用 provider 注册表**（域层 `SourceProviders`，代码事实——新增 provider 必须先写接入代码）：QUOTE/VALUATION = `eastmoney|tencent`、A 股列表桶 = `eastmoney|sina`；其余源暂单 provider（FINANCE/ANNOUNCE=`eastmoney`、NEWS=`sina`、POLICY=`gov`、EVENT=`local`），页面呈现「暂无备选源」。首元素即默认主源，全链即 `auto` 语义的默认链

### 2. 读取折算与兜底（用户拍板的优先级）

`FallbackChains.resolve(rawChain, legacyValue, registry)` 每次取数现算（热生效，与 ADR-0032 同口径）：

1. 显式 `fallbackChain` 非空 → 按序使用（可换主源/裁剪备选）；空链 → 仅默认主源；非法链（成员越界/重复）→ WARN 后回落全链
2. 链缺省 → 按旧键折算：`backupSource`/`aShareSource` 为 `auto`（或空）→ 注册表全链；单值 → 单元素链（旧强制单源语义）
3. 链与旧键均缺 → **代码内置默认链兜底**（`DataSourceDefaults.fallbackChain` = 注册表全链：QUOTE/VALUATION `[eastmoney,tencent]`、列表 `[eastmoney,sina]`）

### 3. 引擎与契约

- 三消费点按链执行（`FallbackChainRunner`）：主源失败**或空响应** → WARN 留痕（provider 名 + 链位）→ 下一备选**整轮/整桶重试**；全链失败抛末级异常（前级挂 `suppressed`，沿既有弹性降级 MISSING）；`RawFetch.source` 标注实际命中 provider（主源位「东方财富行情」/备选位「东方财富行情→腾讯备选」，与 ADR-0031 标注同文案，健康徽章与连通性测试可区分兜底轮）
- **契约增量**：`SourceCardView` 增 `fallbackChain`（折算后有效链，所见即下一次取数所行）与 `availableProviders`（注册表，编辑依据）；`PATCH` 增 `fallbackChain` 字段（空数组合法 = 仅主源）；校验器：链非空时成员 ∈ 注册表且无重复（单 provider 源多成员自然被拒）
- **写路径统一 fallbackChain**：页面保存链时顺带删除 `params.backupSource` 旧键（防双键矛盾态）；旧键**读取兼容保留**（存量 DB 行不动，seed-if-absent 不迁移），淘汰节奏随各环境首次页面保存自然完成

### 4. 页面可视化（数据源配置页）

每源卡片增「降级策略」区块：主源徽章（`东方财富·主源`）→ 箭头 → 备选链 chips（如 `东方财富·主源 → 腾讯`）；单 provider 源显示「暂无备选源」muted 文案。编辑 Dialog（仅多 provider 源）：一排 provider 按钮，**点亮=启用、点击顺序即降级顺序、首个为主源**，全部熄灭 = 仅默认主源；保存走 PATCH `fallbackChain`，反馈「降级链已更新，下一次取数生效」，三态与既有卡片一致。

## ⚖️ 候选方案对比

| 方案 | 结果 |
| --- | --- |
| 保留 backupSource 单值枚举不动 | ❌ 无法表达链序/换主源/清备选；两处键空间分裂持续 |
| 扩枚举（如 auto2/auto3 编码顺序） | ❌ 组合爆炸，语义晦涩，不可视化 |
| **有序 fallbackChain 数组 + 注册表 + 折算兼容（本 ADR）** | ✅ 顺序语义完整表达；页面可视化自然；存量行零迁移读取兼容；代码默认链只做兜底（用户拍板语义） |
| fallbackChain 一步到位删旧键（禁迁移例外批） | ❌ 存量 DB 行需迁移才不坏读；与「本批禁迁移」约束冲突 |

## ⚠️ 影响

- 正面：降级策略从「枚举开关」升级为「可编排的链」且页面可视；引擎三消费点共用一套链执行器（WARN 留痕/suppressed 诊断链/来源标注统一）；DB 值权威、代码默认只兜底。
- 负面 / 代价：链成员合法性依赖注册表同步维护（新增 provider 漏登记则页面不可选——但引擎也不可达，一致安全）；`backupSource` 双键过渡期以 fallbackChain 为准（写入即淘汰旧键）。
- 行为兼容：存量库无感（旧键折算语义与 ADR-0031/0032 完全等价：auto→东财优先降级腾讯/新浪、单值→强制单源）；全新库种子直落链模型（QUOTE/VALUATION/subject.sync 种入默认链）。
- 测试影响：新增 `FallbackChainsTest`（折算 13 用例）与 `FallbackChainEngineTest`（三消费点链行为 10 用例）；既有 Quote/Valuation Fallback、HotSwitch、Routing 列表源测试零改动通过（兼容语义锁定）。

## 🔍 后续验证方式

- 全量 `mvn verify`：1255 全绿（基线 1211 只增），jacoco 门禁过（新类行覆盖 82%~100%）
- 前端 `vitest`：236 全绿（基线 229 只增），oxlint 0 error
- 重启后手工冒烟：页面 7 卡降级链区块渲染（QUOTE/VALUATION 两链、其余「暂无备选源」）；编辑降级链保存 → 连通性测试来源标注随链变化

## ♻️ 替代记录

- 修订 ADR-0031/0032 的「`backupSource`/`aShareSource` 为降级策略唯一配置口径」表述——开关语义（auto 降级/强制单源）经折算完整保留，写路径统一 `fallbackChain`，旧键降级为读取兼容口径。
