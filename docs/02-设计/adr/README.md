# 🧾 ADR · 架构决策记录

> 每条"做过选择"的架构决策记一条 ADR，编号递增、**只追加不修改**；决策变了新增一条并标记替代关系。

## 📐 约定

- 文件名：`NNNN-简短标题.md`（NNNN 四位编号，如 `0001-模块化单体架构.md`）
- 字段：背景 / 决策 / 被否方案与理由 / 后果
- 模板见个人知识库 [ADR 模板](/project-development/02-design/adr-template.md)

## 📋 已记录决策（摘自《技术方案-信息整合平台.md》）

> 完整 ADR 待开发启动后从技术方案拆出独立文件落本目录。

| 编号 | 标题 | 状态 |
| --- | --- | --- |
| ADR-0001 | 架构形态：模块化单体 | 已决 |
| ADR-0002 | 技术栈与存储：取基线 + 裁剪 RBAC/分库分表 | 已决 |
| ADR-0003 | 统一数据访问层：SourceAdapter + 主数据映射（先 Spike-1） | Spike-1 已完成（假设验证通过，选型待用户拍板） |
| ADR-0004 | LLM 网关：自建薄抽象（先 Spike-2） | Spike-2 已完成（假设验证通过：自建薄抽象可行，两家 OpenAI 兼容 Java 直调；选型待用户拍板） |
| ADR-0005 | 缓存与限频：Caffeine Cache-Aside + 令牌桶 | 已决 |
| ADR-0006 | 实时推送：SSE + Spring 事件驱动 | 已决 |
| ADR-0007 | 分层方式：DDD 四层（补充 ADR-0001） | 已决 |
| ADR-0008 | LLM 网关强化：配置驱动多 provider + fallback（补充 ADR-0004） | Spike-2 已完成（配置驱动多 provider + fallback 可行，DeepSeek 默认 + GLM 免费档 fallback；选型待用户拍板） |
| ADR-0009 | Flyway SQLite 迁移依赖实现偏差（补充 ADR-0002） | 已决 |
| ADR-0010 | 数据源弹性实现选型（自建超时+重试 / 熔断留接口位） | 已决 |
| ADR-0011 | 公告源弹性 spec 取舍（noRetry 2s 而非 1s 重试 1） | 已决 |
| ADR-0012 | 前端 React 版本选型偏离基线（React 18 → 19） | 已决 |
| ADR-0013 | 事件源实现偏差：event-monitor 不存在，改读本地 anomaly_event 表（T08） | 已决 |
| ADR-0014 | 标的类型扩展位实现方式：预留标记 + 领域构建守卫 + adapter 类型支持自声明（T31） | 已决 |
| ADR-0015 | LLM 成本治理数据源与预算计数：独立留痕表 + 微元整数成本 + 日界键预算（T30） | 已决 |
| ADR-0016 | 推荐相关性个性化：阅读留痕表 + 指数衰减评分 + 提示词 v1.1 随迁移播种（T29） | 已决 |
| ADR-0017 | 运行时配置热生效机制：配置中心快照 + 用时读取 + 任务调度集中化（REQ-20260922-02 / M4） | 已决 |
| ADR-0018 | API key 存储安全：DB AES-256-GCM 加密 + 脱敏回显 + DB 优先/环境变量回落（REQ-20260922-02 / M4） | 已决 |
| ADR-0019 | 个人信息流阅读埋点延后：readingEvent 契约无信息流条目承接面（T43） | 已决 |
| ADR-0020 | LLM 配置种子来源改代码内置默认：删 yml `llm:` 段，页面配置即唯一真相（修订 ADR-0017 种子来源） | 已决 |
| ADR-0021 | 提示词版本号受控生成与排序：零 DDL + 服务端 bump 生成 + Java 数值排序（REQ-20260922-03 / M5） | 已决 |
| ADR-0022 | 占位符注册表单一事实源：装配器自带描述符 + 注册表聚合 + 同源单测（REQ-20260922-03 / M5） | 已决 |
| ADR-0023 | 提示词模板治理实现偏差：Provider 多场景返回 + sqlite UNIQUE 翻译下沉 + 实体时间戳补齐 + create 响应泛型（T45/T46） | 已决 |
| ADR-0024 | 提示词模板治理前端实现偏差：仅未知不弹确认层 + SaveFeedbackBar successLabel + ApiError data 透传 + 草稿绑定底稿 + 空底稿骨架 + 注册表懒加载时机（T47/T48） | 已决 |
| ADR-0025 | P1 可用性修复批次实现偏差：feed 只读每页读缓存（位置型游标稳定）+ 负缓存 TTL 可选校验回落（禁迁移）+ 简报 FAILED 状态回置重试（P1-5/P1-6，M6） | 已决 |
| ADR-0026 | 订阅页与概览用户视角化实现偏差：订阅卡无创建时间（契约缺口 UI 吸收）+ 推荐卡挂载走 feed 只读判读（触发式端点仅用户 CTA 调用）+ 未生成/生成中合并展示（P1-3/P1-4，M6） | 已决 |
| ADR-0027 | 标的池同步数据源选型：东财 push2 clist 三桶（实测勘定港股 fs=m:116+t:3,t:4）+ 限速对策与备选（REQ-20260924-04 / M7） | 已决 |
| ADR-0028 | 消失确认存储设计：subject_master 内嵌 missing_streak 列（V18，计数/清零/单向停用 SQL 级表达） | 已决 |
| ADR-0029 | 标的池同步 T50/T51 实现偏差：列表源端口化 + 写库单元拆分 + 快照更新三列口径与独立清零（REQ-20260924-04 / M7） | 已决 |
| ADR-0030 | A 股列表源备选与自动切换：新浪 SinaSubjectListClient（仅 A 股桶/bj 跳过/无行业）+ RoutingSubjectListSource auto 降级（REQ-20260924-04 / M7） | 已决 |
| ADR-0031 | 行情/估值备选源与自动降级：腾讯 TencentQuoteClient（东财 f 键中间结构）+ adapter 内组合 auto 降级 + 东财 '-' 缺失值修复（M7 封禁补强） | 已决 |
| ADR-0032 | 数据源配置种子来源改代码内置默认：删 yml `adapter:` 段，DataSourceDefaults 单一事实源 + 备选源开关热化（修订 ADR-0017 种子来源，ADR-0020 同系列） | 已决 |
| ADR-0033 | 数据源降级链模型：fallbackChain DB 配置优先、代码默认链只兜底 + 页面可视化 + backupSource 旧键兼容折算（修订 ADR-0031/0032 配置口径） | 已决 |
| ADR-0034 | 财务备选源新浪两页组合 + 公告备选源巨潮 cninfo：第四/第五降级链消费点（腾讯/网易实测不合格，5 样本交叉核对零偏差；REQ-20260924-05 / M8） | 已决 |
| ADR-0035 | 列表页码分页取同端点双模式（page 出现即页码模式，游标不动）+ LIMIT/OFFSET 深分页从简与护栏（实测 12,385 行毫秒级，50 万行/P95 100ms 触发清理优先；REQ-20260925-06 / M9） | 已决 |
| ADR-0036 | 留痕清理键空间与留痕明细载体：retention.global 独立域文档 + SUCCESS 行 error_message 复用为留痕明细（JobRunStats 可选通道扩展，零 DDL）+ 枚举白名单删除端口与子查询分批（REQ-20260925-07 / M10） | 已决 |
| ADR-0037 | 详情分区分页取分区子端点契约（/announcements /events /news 三端点异构语义，聚合零改动）+ 翻页绕快照缓存直调源（fetchPage 默认方法共用三态骨架）+ 聚合附加 sectionPagination 键 + 新闻停止条件前端判定 + V19/V20 条件迁移（三源深翻实测留档；REQ-20260925-09 / M12） | 已决 |
| ADR-0038 | 资讯源注册表 DB 化：info_source 独立表（预置/通用统一模型，seed-if-absent）+ 六源业务域体系边界冻结（请求驱动降级链 vs 调度驱动退避摘除，datasource.{CODE} 键空间不再增长；REQ-20260925-10 / V2.0-M13） | 已决 |
| ADR-0039 | 资讯统一库独立建表 news_item（不与 policy_item 合并）+ 跨源指纹去重 sha256(归一化标题+日期) 全局唯一 + 双唯一索引 INSERT OR IGNORE 入库幂等（游标随条目事务推进不重不漏；REQ-20260925-10 / V2.0-M13） | 已决 |
| ADR-0040 | 分钟级多源调度：SOURCE_POLL 聚合 ManagedJob（tick 现读启用源热生效/全局并发上限 4/指数退避封顶 60min/断流补抓深翻 truncated 显性化；否决每源一 Job 与 Quartz 新调度器；REQ-20260925-10 / V2.0-M13） | 已决 |
| ADR-0041 | FeedFetcher 端口返回 FetchResult 携带补抓截断信号（truncated 只有引擎知道——条目列表无法推断触顶；实现级精化不改变契约语义；REQ-20260925-10 / V2.0-M13 T101） | 已决 |
| ADR-0042 | 试点源实测字段口径与引擎容错三则（金十 data 嵌套映射点分导航 + 标题回落 + 包装空格漂移首边界截取；目录常量按 2026-09-25 实测对齐；REQ-20260925-10 / V2.0-M13 T106） | 已决 |
| ADR-0043 | 批次一一级 JSON 四源实测修正与引擎微扩展两则（epoch_millis_to_iso + urlTemplate；东财 7×24 参数演进对齐/同花顺 UTF-8 判定/澎湃频道端点 404 按「要闻·含财经」接 hotNews/东财宏观 preset 序列条目化 cursorType=NONE；REQ-20260925-11 / V2.0-M14 T110） | 已决 |
| ADR-0044 | 批次一官方与报纸六 HTML 源实测修正与 NONE 游标裁量（证监会普查端点冻结→首页要闻块、证券时报现行栏目 yw.html、时间格式碎片化统一墙钟折算、六源 cursorType=NONE 唯一索引幂等；21 财经 AI 条款 M15 前复核注记；REQ-20260925-11 / V2.0-M14 T111/T112） | 已决 |
| ADR-0045 | 大盘聚合端点形态与感知延迟增量轮口径及源告警双阈值裁量（新建 GET /feed-dashboard 三区块一端点不动 stats；增量轮 v1 = 排除每源首日回灌 + 排除日粒度源，basis 版本串随响应下发；告警 = 连续 5 轮或退避窗 ≥30 分钟取先到、告警态内存节流重启重告一次可容忍；PushType 扩 6/7 无需迁移；REQ-20260925-11 / V2.0-M14 T114~T116） | 已决 |
| ADR-0046 | 管道产物独立建表与批量归类调用形态及护栏派生（news_analysis/event_item/快照/日报四表 + news_item 零改动；L1 批 20 条对象包裹数组 + 对半拆批，20 条真实资讯一次 deepseek-flash 实测 20/20 格式可靠 8.1s ¥0.000456/条；BriefType 扩 5/6/7 入治理页 + 代码侧 35 枚举权威；护栏 = llm_call_log scene 口径无表派生 60% 降级保 L1+日报/90% 熔断、换日自动恢复补跑；K1 缺省 10 勘定 REQ 建议值 3 的验收算术；4 新 ManagedJob 池 12；REQ-20260926-12 / V2.0-M15） | 已决 |
| ADR-0047 | 近重复海明预筛阈值实测勘定（simhashDistanceMax 缺省 3 → 18：20~60 字 CJK 标题 bigram simhash 实测改 1 字即海明 5、无关对 21+，阈值 3 召回失效；海明预筛定位勘定为「同稿系 vs 无关对」召回门，精细边界仍由编辑距离 ≤0.25 确认段承担；算法零改动；REQ-20260926-12 / V2.0-M15 T120） | 已决 |
| ADR-0048 | T123/T125 实现裁量对齐（任务文本与方案原文四处出入以方案为准——aiExclusion 走 info_source.config 三值档不加列零 DDL、30076 单码承载 heat 查询参数非法而 30077 保留 T124 日报 409、下钻 beforeId 游标不引入页码双模式、T125 先于 T123 落库避免占位供应商死代码；V25 迁移号留给 T124；REQ-20260926-12 / V2.0-M15 T123/T125） | 已决 |
| ADR-0049 | T124/T128 实现裁量对齐（日报重试端点 /retry 与 30077 已成功 409 / 30078 不存在 404 按方案冻结——任务文本 regenerate 与两码对调不落地；任意 FAILED 日可重试经 JobCenterFacade.trigger 202 受理 armRetry/disarm 回滚；FUSED 留痕次日补 = 定时窗口 [前日, 昨日]；空数据日不调 LLM；T128 停更标记载体 config.staleSince 零 DDL + PATCH 编辑保留 + 月频源 em_macro 35 天窗 + Should 通知裁剪；REQ-20260926-12 / V2.0-M15 T124/T128） | 已决 |
| ADR-0050 | T127 实现裁量对齐（事件流卡片主键字段名 id 按方案 §4.8 冻结——任务文本 eventId 不落地；/events 全参数错误统一 30079 EVENT_FILTER_INVALID/400 单码承载而 30076 留 heat 域；newsTitle/newsUrl 核实在 news_item 列走 JOIN 取数而 event_item 无该两列；REQ-20260926-12 / V2.0-M15 T127） | 已决 |
| ADR-0051 | 高价值快速通道与事件轮询消费及事实白名单与 P2 行业命中双通道（PIPELINE_EXPRESS 2min 纯规则预筛分 ≥4.0 直通复用 L0/L1/L2、NEWS_PIPELINE 零改动；事件消费 = RECOMMENDATION_FEED 60s LEFT JOIN 轮询非进程内挂钩〔重启丢事件/事务边界/双机制复杂度〕；FactWhitelistValidator 标的/行业/数字/方向四类白名单集合比对拒即模板兜底；P2 硬阻塞解法 = 行业关注集双通道〔订阅主题经 IndustryDirectory 31 行业别名目录映射为主力 + 标的池行业 best-effort 东财主链恢复自动增强，不以回填为前置〕+ P1 判定集并入 SUBJECT 订阅；基建扩位 PushType RECOMMENDATION(10)/PushStatus SILENT(3) 静默留痕态/BriefType 8 scene 入护栏/recscore-v1 画像系数 [1.0,1.25]；REQ-20260926-13 / V2.0-M16） | 已决 |
| ADR-0052 | T134/T136 实现裁量对齐（任务文本与方案原文三处出入以方案为准——retention 两表共享 recommendationCardDays 单键 180/30 同窗同清页面五→六字段而非 90/180 两键七字段；SSE 推荐载荷 type=recommend 沿 T133 eventName 四点同源〔事件名=载荷 type=history type=前端键〕而任务文本示例 type:"recommendation" 不落地视为方案笔误；采纳当日口径 = ACT 埋点 distinct 卡与 adopted 条件首置同点写入恒等而非 card.updated_at 近似〔重复 USEFUL 不重复落 ACT〕；REQ-20260926-13 / V2.0-M16 T134/T136） | 已决 |
| ADR-0053 | 批次二九源实测修正与三首页块窗口及检索 API 裁量（REQ 记端点五处结构差异——工信部 zcwj 列表客户端渲染改走页面自身公开检索 API〔search-front-server/api/search/info，preset 适配非逆向〕、界面 lists/2 实为商业频道修正 lists/800、上证报 news 子域 302 进新站取首页要闻卡块〔CSS-module 哈希类前缀匹配〕、中证网栏目列表 JS 模板取首页 7×24 块〔em 时分 + URL 日期拼合分钟精度〕、人民网首页无显式时间取 URL 内嵌日期；七源 cursorType=NONE 裁量沿 ADR-0044〔工信部检索序非严格时间序实测/日粒度/编辑序/相对时间〕，东财要闻 ID 游标、国际 RSS 双源 TIME 游标沿 MW 先例；robots 全留档——人民网 Crawl-delay 120s → 60min=30 倍裕量、Nasdaq Crawl-delay 30 → 30min=60 倍、WSJ 首测连接重置重试即 200 瞬时抖动留档；Barron 双端点 404 + Yahoo 403 排除留档未走补位；目录 13→22 精确命中蓝图达标线；REQ-20260926-14 / V2.0-M17 T140~T142） | 已决 |
| ADR-0054 | M17 行业深化三件实现裁量对齐（影响链 v1 纯规则零 LLM——12 类模板 = 9 事件类型键 + 政策宏观三分货币/财政/产业，治理页模板 8→9 而非 REQ 条目 8 的 8→10〔影响链生成提示词类不落地，LLM 语言组织面收敛到周报一处，幻觉风险最保守实现〕；迁移分号 V28 影响链表 / V29 周报表+brief_type=9 播种——单 V28 跨两笔提交破坏 Flyway 已应用不可改约定；周报热度 = 周窗现算而非快照序列〔快照表 62 行常驻无时序，沿 HeatSnapshotService findWindowItems 双窗现算先例，不新建时序表 YAGNI〕；走向判断对象 = 升温 Δ 降序 Top3 ∪ 降温 Δ 升序 Top3 缺省不可配、STABLE 不叙述、AI confidence 字段与规则层不一致即该行业整体模板兜底、行业越界条目丢弃；GAP-03 = 后端测试直读前端 notification.ts 双向断言 + TYPE_LABELS 覆盖不引入共享常量文件；GAP-02 = L2TraceRepair 顺挂 SOURCE_STALE_CHECK 末段每日 04:10 幂等留痕不新增端点；REQ-20260926-14 / V2.0-M17 T144~T147） | 已决 |
| ADR-0055 | M18 批次三八席预检窗口与专项判据终局及条件席裁量（格隆汇四验全过占竞争席——robots 404/payload 13 条三字段完整〔Nuxt IIFE 尾参表绑定解 epoch 秒 25/25〕/试抓 ≥5/三采样结构一致，成本远低 1 人天上限；腾讯三验失败永久关闭——已知公开端点 24hours/finance 双探 data:null + robots 501 WAF JS 盾，不再复议；Nasdaq 复核 http=000 同 M17 环境网络面 → 终局停用〔软删归编排者〕22→21 + 条件席第 9 席启用；纵深席锁东财 column=352〔同宿主同构零新解析〕；条件席=中国经济网〔和讯瑞数 JS 盾 FAIL 顶替，ce.cn robots Allow:/ 首页 182 带题锚点〕；通道修正三处——金融界现行窗口为 www 根〔列表路径 404/静态精选滞后 12 天，无参数根路径合规注记落地〕、央视走 jsonp 数据端点 json_api〔首页客户端渲染壳，沿工信部先例〕、新华取首页新华快讯块〔URL 日期 + 锚点 HH:mm 分钟精度〕；示例包 ×2 = WSJ WorldNews + IT之家默认停用〔BBC 000/Investing 403 排除〕；目录 22→31〔+2 停用示例 = 33 行〕；REQ-20260926-15 / V2.0-M18 T153） | 已决 |
| ADR-0056 | cost-v1 预算校准升版 cost-v2 与 30 源负载重估（M18 T157，M17 遗留 ⑥ 时序红线——批次三回灌前完成：scene5-8 近 3 日日均 ¥0.4293 × 30/22 = 常态外推 ¥0.5854/日 22.5% 水位、期内最高日外推 ¥1.2250 < 新降级线 ¥1.56〔27% 裕量〕 → dailyBudgetMicros 2,000,000→2,600,000；两级比例 0.6/0.9 零变更护栏语义不动、单条成本 248 微元/条远低于 20,000 红线；costBasis 升 cost-v2:m18-30src 与 Guard 每日校准 cost-v2:calibrated:{date} 同族衔接；运行库 runtime_config 单行 PATCH 已执行〔守卫幂等〕+ Settings 缺省与种子同步；校验器无既有上限零改动；修前红 3 FAIL→缺省升版后 37/37 绿；REQ-20260926-15 / V2.0-M18 T157） | 已决 |
| ADR-0057 | M18 前端测量批实现裁量（T154~T156/T158：北极星新建 GET /api/v1/north-star 不并入 feed-dashboard——六指标失败域不污染三区块、感知延迟/入库/启停复用 dashboard 聚合构造上同值、采纳率沿 adopt-v1 当前用户、成本沿 cost-v2、覆盖率为 news_analysis L1 日分布现算、稳定源 7 天窗现算，状态判定恰等值语义单测锁定；快照机制 = 现算+留档零新表零 Job，实时态当日口径走端点、窗口首测按 ns-v1 公式实算进收口报告；信息流行业筛选 = 后端 /feed/personal?industry= 宽松校验〔政策 relatedIndustries 含二级行业标签 + 标的 industry 基本为空的数据现实核实〕，推荐条目不入筛选视图、筛选前置于分页，前端下拉仍申万 31 项，V2.1 候选 L1 口径收敛；A 层间距阶 = 具名变量+文档基准不回改存量页〔布局红线〕；>30 源形态取纵向滚动+表头吸附阈值 20；概览工作台四块复用既有 API 单块独立三态；REQ-20260926-15 / V2.0-M18 T154~T156+T158） | 已决 |
| ADR-0058 | M20 因子集与快照结构/权重模型/双 Spike 裁决（快照形态=日频表 UNIQUE(subject_id, snapshot_date) 当日重跑同键覆盖非最新态——M21 变动/M22 统计/按当时权重复现审计都要序列；行业传导绕行 subject_master.industry〔5221 只 100% NULL 且随源轮换漂移入因子即破坏复算幂等〕→ IndustryAssociator 双路派生〔事件 subjects 回联 affected_industries + news matched_subjects main/sub〕30 天窗半衰期消退；因子分=绝对饱和映射纯函数锚定 snapshot_date、百分位仅展示层查询层算〔横截面百分位使分数耦合全集合，M22 增量重评无法局部重算被否〕；权重模型=runtime_config score.weight 单键五维权重+窗口+三标签阈值+ValuationConfigValidator 30087，weight_basis 指纹串入快照行，编辑面挂任务中心 FACTOR_SNAPSHOT Dialog 零新页〔保 M21 第 20 页序号〕，缺省 w=0.40\|0.20\|0.20\|0.20\|0.00；Spike-A 实测 2026-09-22——腾讯 600 只/请求〔URL 5399B〕稳定 650+ 拒、全轮 5221=9 请求×500ms 8.8s 零失败、PE@52/PB@46 与东财 ADR-0031 交叉核对值一致〔填充率 76%/99.2%〕、push2 单只 stock/get 至今空响应但批量 ulist.np 可用留 v2 备选 → 估值水平维 F5 纳入 v1 默认权重 0〔横截面 PE→PB 回退链缺数中性 50，时序分位待序列 ≥250 交易日 v1.x〕、量价异动只落原始值；Spike-B 三源可用留档 v2——龙虎榜 RPT_DAILYBILLBOARD_DETAILSNEW/增减持 RPT_SHARE_HOLDER_INCREASE/EPS 一致预期 RPT_WEB_RESPREDICT〔EPS1~4+覆盖机构数+INDUSTRY_BOARD〕，大宗 6 候选报表名全 9501 待抓包；方案库 03/08/09 采用 04/12 否决留痕；REQ-20260926-17 / V2.2-M20 T170~T176） | 已决 |
| ADR-0059 | M21 回联提升路径/粗筛切分/深析降级/合成公式（回联 98.7% 稀疏根因量化 = 行业→标的边缺失〔L1 申万归类 885 行无标的反哺 + subject_master.industry 100% NULL〕——提升路径 = SubjectRepositoryImpl COALESCE 写路径修复〔防新浪降级轮再抹〕+ 双通道行业回填〔push2 clist f100 机会主义/datacenter RPT_WEB_RESPREDICT.INDUSTRY_BOARD 2933 只确定性兜底只补 NULL 幂等〕+ IndustryDirectory.swPrimaryOf 东财板块→申万 31 映射〔~104 实测值未收录返 null 安全侧〕+ IndustryAssociator 路 C 成员边 weight 0.3 age 0〔纯成员 F2 ≤30 封顶守「突破需点名传导」〕，否决 prompt 增强〔null-code 15 名池内 0 命中全非 A 股实体〕与事件灌成员〔污染 F1 分维语义〕，部分替代 ADR-0058 裁决 2〔幂等口径扩六输入〕；粗筛四键可复现切分 total→F1→last_event_date→id〔V31 ALTER 列双用途〕+ ST/无信号排除留痕 + 层数断言常驻〔全量 LLM 逐股永不发生的机制化〕+ deepDiveLimit 30~50 硬校验 30091；深析单标的单次 briefType=10 scene"10"〔9 已被周报占用〕五步校验链 + 引用对账结构化 id 集合比对〔M16 白名单先例裁剪〕+ 违禁黑名单正则 + 模板兜底数字全结构化；成本护栏 scene-10 占管道日预算 ≤30% 触顶降级三档〔单标兜底/连续 5 失败中止/成本触顶停〕榜单恒产出因子分兜底；合成 final=max(总分,0.8×总分+0.2×diveScore) max 包络深析只加分〔diveScore 确定性结构分 25+10×3+10×2+5×5 合格区间 75~100 不掺观点分〕；榜单 market_top_rank/batch (rank_date,version) 追加式版本化 + 变动 diff 留痕不建第三表；页面 #/market-top 第 20 页 + 方法论子路由不占导航位；MARKET_TOP_JOB 第 17 键独立 CRON 18:00 + 快照日守卫非依赖编排；方案库 03/07/08/09 采用 04/06/12 否决留痕；REQ-20260926-17 / V2.2-M21 T180~T187） | 已决 |
| ADR-0060 | M21 T180/T181 实现裁量与 V31 回填 SQL 修正（V31 last_event_date 存量回填的三段 UNION 标量子查询在 SQLite 取去重排序首行=最小日期〔迁移单测实证 expected 09-21 but was 09-15〕——外层包 MAX(d) 修正为方案文字语义「三维护据事件最大日期」，V31 未应用于任何库直接改文件不违 Flyway 约定；swPrimaryOf 映射值域开发期 curl 级预检实证 127 全量值〔方案无附录、样本估 ~104，按申万 2021 分类树映射 31 全覆盖〕；回填双通道执行序 datacenter 确定性先行 + clist 机会主义仅仍低于阈值才尝试；industry 存东财板块原文 SW 映射收口读侧单一事实源；market.top 配置基础件随 T180 提交〔回填预检消费 memberCoverageFloor〕端点随 T181；层数断言分层——结构性校验常驻 PoolBuilder〔§4.3.2 退化态允许池=快照行〕严格四值链 assertFunnelLayers 归 Job 阶段 3〔§4.3.4 落库前中止面，topSize 彼时已知〕；REQ-20260926-17 / V2.2-M21 T180/T181） | 已决 |

## 🔍 关联调研

| 编号 | 标题 | 状态 | 关联 ADR |
| --- | --- | --- | --- |
| Spike-1 | [数据源调研](/docs/02-设计/spike-1-数据源调研.md) | 已完成 | ADR-0003 |
| Spike-2 | [LLM 网关调研](/docs/02-设计/spike-2-llm-调研.md) | 已完成 | ADR-0004 / ADR-0008 |
