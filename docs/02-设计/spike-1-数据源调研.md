# 🔍 Spike-1 · 数据源调研

> 摸清 6 类金融数据源（行情/财务/估值/公告/新闻/政策事件）的实际可用 API，产出候选对比、选型建议、字段映射矩阵与各源 `SourceAdapter` 契约要点，供 T03~T08 真实接入与用户拍板选型。

- 调研性质：时间盒 ≤2 人天，**不写实现代码**，结论以 API 文档/实跑响应为据。
- 作者 / 日期：架构设计师 / 2026-09-21
- 关联：[技术方案-信息整合平台.md](/docs/02-设计/技术方案-信息整合平台.md) §4.4 统一数据访问层、§4.3 流程 1、§8 Spike-1、ADR-0003
- 上游需求：[REQ-20260920-01-信息整合平台.md](/docs/01-需求/REQ-20260920-01-信息整合平台.md)（6 类数据源）

## 🎯 0. 结论速览（一句话选型）

| 源 | 默认选型 | 备选 | 形态 / 调用路径 | 免费付费 |
| --- | --- | --- | --- | --- |
| 行情 QUOTE | **东方财富 push2 `stock/get`** | tushare `daily` / 新浪 `hq.sinajs.cn` | HTTP JSON，Java 直连 | 免费 |
| 财务 FINANCE | **东方财富 datacenter `RPT_F10_FINANCE_MAINFINADATA`** | tushare `fina_indicator` / akshare | HTTP JSON，Java 直连 | 免费 |
| 估值 VALUATION | **东方财富 push2 `stock/get`（f 字段）** | tushare `daily_basic` / akshare | HTTP JSON，Java 直连 | 免费（字段语义待 T03 验） |
| 公告 ANNOUNCE | **东方财富 `np-anotice-stock/api/security/ann`** | tushare `anns_d` / 巨潮 cninfo | HTTP JSON，Java 直连 | 免费 |
| 新闻 NEWS | **新浪滚动新闻 `feed.mix.sina.com.cn`** | tushare `news` / 财联社 | HTTP JSON，Java 直连 | 免费 |
| 政策事件 POLICY | **国务院 `gov.cn/zhengce` 政策库 HTML** | 证监会/央行发布页 / sina 政策分类 | HTML 抓取（Jsoup），Java 直连 | 免费（无统一 API） |

**核心结论**：6 类源全部存在**免费 + Java 可直接 HTTP 调用**的候选，默认栈**不需要 Python sidecar**（即不必引入 akshare/Python 进程）；tushare 作为"结构化、字段干净"的付费升级项，是否买积分由用户拍板（见 §6）。唯一无统一 API 的是政策源，靠官方发布页 HTML 抓取，已对齐技术方案 §8 风险与"爬虫合规 Spike"留口。

## 📐 1. 调研方法与置信度标注

- **方法**：用 WebFetch 抓取候选源的官方文档页 / 实跑响应，按字段、限频、代码体系、Java 可调用性记录。
- **置信度**：
  - 🟢 高 = WebFetch 到官方文档或实跑 JSON 响应，字段/限频可直接落地。
  - 🟡 中 = 文档间接确认或实跑返回但字段语义需实现期二次校验；或端点对 WebFetch 封禁（403/重定向）但格式来自公开文档。
  - 🔴 低 = 端点已停用/变更或需进一步验证，列跟进。
- **评分维度**（对齐 [02 设计架构 · 选型五维度](/project-development/02-design/index.md#tech-evaluation)）：成熟度 / 社区生态 / 学习成本 / 可替换性 / 运维成本，1-5 分高优；另加 **免费/付费** 与 **Java 可调用性** 两列。

## 📊 2. 六类源候选对比表

### 2.1 行情源 QUOTE（开高低收量额换手）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **东方财富 push2 `stock/get`** 🥇 | HTTP GET JSON | 免费 | f43 价/f44 高/f45 低/f46 开/f60 昨收/f47 量/f48 额/f170 涨跌幅/f57 代码/f58 名称 | 无明示限频（按 IP 软限） | secid `1.600519`（1=沪 0=深 116=港） | ✅ 直连 HTTP | 5 | 4 | 5 | 5 | 5 | 🟢 实跑确认 |
| tushare `daily` | HTTP POST JSON（api.tushare.pro） | token，120 积分起（注册即赠） | ts_code/trade_date/open/high/low/close/pre_close/change/pct_chg/vol/amount | 500 次/分，6000 条/次 | `600519.SH` | ✅ 直连 HTTP（POST `{api_name,token,params}`） | 5 | 5 | 4 | 4 | 4 | 🟢 官方文档确认 |
| 新浪 `hq.sinajs.cn` | HTTP GET 文本（`var hq_str_...="...";`） | 免费 | name/open/pre_close/price/high/low/bid/ask/vol/amount（逗号分隔） | 无明示，高频封 IP | `sh600519`/`hk00700` | ✅ 直连 HTTP（**需 Referer 头**，否则 403） | 5 | 4 | 5 | 5 | 3 | 🟡 实跑 403（格式来自公开文档） |
| akshare `stock_zh_a_hist` | Python SDK | 免费 | 开高低收量额 | 无统一限频；新浪后端易封 IP | `600519`/`sh600519`（混） | ⚠️ 需 Python sidecar | 4 | 5 | 4 | 3 | 2 | 🟢 官方文档确认（无 HTTP API） |

### 2.2 财务源 FINANCE（净利润/营收/ROE/毛利率）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **东方财富 datacenter `RPT_F10_FINANCE_MAINFINADATA`** 🥇 | HTTP GET JSON | 免费 | TOTALOPERATEREVE 营收/PARENTNETPROFIT 归母净利/XSJLL 净利率/XSMLL 毛利率/ROEJQ 加权 ROE/REPORT_DATE 报告期 | 无明示（按 IP 软限） | `SECUCODE=600519.SH` / `SECURITY_CODE=600519` | ✅ 直连 HTTP | 4 | 4 | 4 | 5 | 4 | 🟢 实跑确认 |
| tushare `fina_indicator` | HTTP POST JSON | token，**2000 积分**（单股）/ 5000 积分 vip（全市场） | netprofit_margin/grossprofit_margin/roe/roe_waaa/q_profit_yoy | ≤100 条/次，按积分限频 | `600519.SH` | ✅ 直连 HTTP | 5 | 5 | 4 | 4 | 4 | 🟢 官方文档确认 |
| akshare `stock_financial_em` | Python SDK | 免费 | 营收/净利/毛利率/ROE（封装东财） | 同东财 | `600519` | ⚠️ 需 Python sidecar | 4 | 5 | 4 | 3 | 2 | 🟢 文档确认 |

### 2.3 估值源 VALUATION（PE/PB/PS）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **东方财富 push2 `stock/get`（f 字段）** 🥇 | HTTP GET JSON | 免费 | f162/f163/f167/f173（估值列，**语义需 T03 二次校验**：PE(TTM)/PB/PE(静)） | 同行情源 | secid `1.600519` | ✅ 直连 HTTP（与行情源**同端点合并**，省一次调用） | 4 | 4 | 3 | 5 | 4 | 🟡 实跑返回值，字段语义待定 |
| tushare `daily_basic` | HTTP POST JSON | token，**2000 积分** | pe/pe_ttm/pb/ps/ps_ttm/total_mv/circ_mv/turnover_rate/dv_ratio | 50 次/分（2000 积分） | `600519.SH` | ✅ 直连 HTTP | 5 | 5 | 4 | 4 | 4 | 🟢 官方文档确认 |
| akshare `stock_a_lg_indicator` | Python SDK | 免费 | PE/PB/PS/股息率（乐咕乐股后端） | 同乐咕 | `600519` | ⚠️ 需 Python sidecar | 4 | 5 | 4 | 3 | 2 | 🟡 文档间接确认 |

### 2.4 公告源 ANNOUNCE（标题/时间/正文 URL）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **东方财富 `np-anotice-stock/api/security/ann`** 🥇 | HTTP GET JSON | 免费 | art_code/title/title_ch/notice_date/display_time/ann_type/stock_code/short_name/column_name | 无明示 | `stock_list=600519`（6 位无后缀） | ✅ 直连 HTTP | 5 | 4 | 5 | 5 | 4 | 🟢 实跑确认（样本标题见 §5.4） |
| tushare `anns_d` | HTTP POST JSON | token，积分（高于基础） | ts_code/ann_date/title/ann_type/art_code | 按积分限频 | `600519.SH` | ✅ 直连 HTTP | 4 | 5 | 4 | 4 | 4 | 🟡 文档间接确认 |
| 巨潮 cninfo `hisAnnouncement/query` | HTTP POST 表单 | 免费 | announcementTitle/announcementTime/adjunctUrl | 无明示 | `600519` + orgId | ✅ 直连 HTTP | 5 | 3 | 3 | 4 | 4 | 🔴 文档页 JS 渲染，端点格式需 T05 验 |

### 2.5 新闻源 NEWS（标题/时间/摘要/URL）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **新浪滚动新闻 `feed.mix.sina.com.cn/api/roll/get`** 🥇 | HTTP GET JSON | 免费 | title/stitle/url/wapurl/ctime/mtime/intime/intro/summary/keywords/media_name | 按 lid 翻页 | lid 分类 ID（非个股代码） | ✅ 直连 HTTP | 5 | 4 | 5 | 5 | 4 | 🟢 实跑确认 |
| tushare `news`（7x24 快讯） | HTTP POST JSON | token，积分 | datetime/content/title/src/keywords | 按积分限频 | 无代码（快讯流） | ✅ 直连 HTTP | 4 | 4 | 4 | 4 | 4 | 🟡 文档 doc_id 未直接命中 |
| 财联社 cls.cn `nodeapi` | HTTP GET JSON | 免费 | title/content/ctime/shareurl | 翻页 last_time | 无代码 | ✅ 直连 HTTP | 4 | 3 | 3 | 3 | 3 | 🔴 实跑端点已变更/停用，需 T06 重新定位 |

> 个股相关新闻：新浪滚动新闻为全市场流，**个股关联靠标题关键词匹配**（stock name）；若需个股专属资讯，T06 可改用东方财富个股资讯 API（search-api-web.eastmoney.com）作二级候选，标记为 🟡 待验。

### 2.6 政策事件源 POLICY（标题/发文单位/时间/关联行业）

| 候选 | API 形态 | 认证 | 关键字段 | 限频 | 代码体系 | Java 调用 | 成熟 | 社区 | 学习 | 可替 | 运维 | 置信 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **国务院 `gov.cn/zhengce` 政策库** 🥇 | **HTML 抓取**（无 RSS/JSON） | 免费 | 标题（含发文单位+文号）/发布日期/详情链接 `content/YYYYMM/content_XXXXXXX.htm` | 无明示 | 无代码 | ✅ Java HTTP + Jsoup 解析 | 5 | 3 | 3 | 3 | 2 | 🟢 实跑确认（HTML 结构） |
| 证监会 csrc 发布页 | HTML 抓取（302 重定向需跟跳） | 免费 | 标题/日期/部门/详情链接 | 无明示 | 无代码 | ✅ Jsoup | 4 | 2 | 3 | 3 | 2 | 🟡 实跑重定向 |
| 央行 pbc 发布页 | HTML 抓取 | 免费 | 标题/日期/正文 | 无明示 | 无代码 | ✅ Jsoup | 4 | 2 | 3 | 3 | 2 | 🟡 文档间接 |
| sina 滚动新闻"政策"分类（lid） | HTTP GET JSON | 免费 | 同新闻源 | 同新闻源 | lid 政策分类 | ✅ 直连 HTTP | 5 | 4 | 5 | 4 | 4 | 🟡 复用新闻源分类 |

> 政策源是唯一**无统一 API** 的源：官方 RSS 普遍停用（gov.cn `rss/govall.xml`、cls.cn nodeapi 实跑均 404/停用）。技术方案 §8 已预见（"政策源无统一 API"→首期接公开 RSS/官方发布页；爬虫合规单列 Spike"）。建议 T07 政策 adapter **多源聚合**：gov.cn 政策库（国务院/国办文件）+ 央行/证监会发布页 + sina 政策分类，HTML 抓取+ Jsoup，关联行业靠标题关键词字典。

## ✅ 3. 选型建议（默认 + 备选 + 理由）

### 3.1 选型原则（对齐 PRD 与技术方案）

1. **免费优先**：6 类源免费候选均满足首期（个人/小团队量级），付费源（tushare 积分）作结构化升级备选。
2. **Java 可直接 HTTP 调用优先**：避免引入 Python sidecar（akshare 全系），降低部署/运维成本与 ADR-0002 单包部署形态的偏离。
3. **"无聊但成熟"优先**：东财 push2/datacenter、新浪滚动新闻均为业界长期稳定、广泛被第三方封装的公开端点。
4. **端点收敛**：行情 + 估值同走东财 push2 `stock/get`，一次调用拿 OHLCV + 估值列，省一次往返与一个 adapter 的弹性预算。

### 3.2 逐源建议

| 源 | 默认 | 备选 | 理由 |
| --- | --- | --- | --- |
| 行情 | 东财 push2 | tushare `daily` | 东财免费、JSON 直连、字段全（含换手 f171）；tushare 字段更规整（`pct_chg` 直给涨跌幅）但需 token，作为"结构化升级"备选。新浪作降级兜底（需 Referer 头）。 |
| 财务 | 东财 datacenter F10 | tushare `fina_indicator` | 东财免费、一次取多期（reportDate 分页）；tushare 字段语义更纯（`netprofit_margin`/`roe_waaa`）但需 2000 积分。 |
| 估值 | 东财 push2 f 字段 | tushare `daily_basic` | 与行情同端点合并、免费；**风险**=f 字段语义需 T03 落地时实测确认（PE TTM/PB），若确认不准则切换 tushare `daily_basic`（2000 积分，字段干净）。 |
| 公告 | 东财 `np-anotice-stock` | tushare `anns_d` / cninfo | 东财免费、字段全（含 column_name 分类）、分页清晰；cninfo 为沪深官方平台但端点 JS 渲染难直验，作三选。 |
| 新闻 | 新浪滚动新闻 | tushare `news` | 新浪免费、JSON 直给标题/摘要/关键词/URL；个股关联靠关键词匹配 stock name。财联社端点已变更，T06 若需"快讯"再定位。 |
| 政策 | gov.cn 政策库 HTML（多源聚合） | 央行/证监会发布页 / sina 政策分类 | 无统一 API 是客观约束；官方发布页 HTML 是最权威来源，配 Jsoup + 关键词字典关联行业。sina 政策分类作降级与即时补充。 |

### 3.3 信息置信度小结

- 🟢 高（可直接落地）：东财行情 push2、东财公告 np-anotice、新浪滚动新闻、东财 F10 财务、gov.cn 政策库 HTML 结构、tushare 各接口字段/限频/代码体系、tushare REST HTTP 形态。
- 🟡 中（实现期二次校验）：东财估值 f 字段语义、新浪 hq 实时报价格式、tushare `news`/`anns_d` 字段、东财个股资讯 API、证监会/央行发布页结构。
- 🔴 低（需进一步验证）：巨潮 cninfo 端点格式、财联社 cls.cn nodeapi 现行端点、gov.cn/各官方 RSS（确认已停用）。

## 🔗 4. 字段映射矩阵（对齐 T02 `FieldMapper`）

映射规则：外部源原始字段名 → 内部 `SourceResult.data` 目标字段 + [`Transform`](/backend/src/main/java/com/info/platform/infrastructure/aggregation/Transform.java)（`none`/`to_decimal`/`to_long`/`to_string`/`to_iso_date`）。白名单映射——未列出的原始字段丢弃。配置 JSON 落 `backend/src/main/resources/field-mapping/`，由 [`FieldMapper.loadMapping`](/backend/src/main/java/com/info/platform/infrastructure/aggregation/FieldMapper.java) 加载（参考既有 [quote-sample.json](/backend/src/main/resources/field-mapping/quote-sample.json) 格式）。

### 4.1 行情（东财 push2）

| 源字段 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| f57 | externalCode | none | 6 位代码 |
| f58 | name | to_string | 证券名称 |
| f43 | price | to_decimal | 最新价 |
| f46 | open | to_decimal | 开盘价 |
| f44 | high | to_decimal | 最高 |
| f45 | low | to_decimal | 最低 |
| f60 | preClose | to_decimal | 昨收 |
| f169 | change | to_decimal | 涨跌额 |
| f170 | changePct | to_decimal | 涨跌幅 % |
| f47 | volume | to_long | 成交量（手） |
| f48 | amount | to_decimal | 成交额（元） |
| f171 | amplitude | to_decimal | 振幅 % |

### 4.2 财务（东财 datacenter F10，取最新报告期一条）

| 源字段 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| SECURITY_CODE | externalCode | none | 6 位代码 |
| TOTALOPERATEREVE | revenue | to_decimal | 营业收入（元） |
| PARENTNETPROFIT | netProfit | to_decimal | 归母净利润（元） |
| XSJLL | netProfitMargin | to_decimal | 销售净利率 % |
| XSMLL | grossProfitMargin | to_decimal | 销售毛利率 % |
| ROEJQ | roe | to_decimal | 加权净资产收益率 % |
| REPORT_DATE | reportDate | to_iso_date | 报告期（2026-06-30T00:00:00） |

### 4.3 估值（东财 push2 f 字段，与行情合并）

> 待 T03 实测确认 f162/f163/f167/f173 的确切语义后填入；暂定下表，落为 `valuation.json` 草稿（见 §4.7）。

| 源字段 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| f162 | peTtm | to_decimal | 市盈率 TTM（暂定） |
| f163 | pb | to_decimal | 市净率（暂定） |
| f167 | peStatic | to_decimal | 市盈率静态（暂定） |
| f173 | psTtm | to_decimal | 市销率 TTM（暂定） |

### 4.4 公告（东财 np-anotice-stock，列表→数组）

| 源字段 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| art_code | externalId | none | 公告唯一 ID |
| title | title | to_string | 公告标题 |
| notice_date | publishedAt | to_iso_date | 公告日期 |
| columns[0].column_name | category | to_string | 分类（嵌套在 `columns[0]`，非平铺） |
| codes[0].stock_code | externalCode | none | 6 位代码（嵌套在 `codes[0]`） |
| codes[0].short_name | subjectName | to_string | 简称（嵌套在 `codes[0]`） |
| （构造） | url | none | 详情 URL 由 art_code 拼装（A 股 PDF 直链 `https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf`，实测稳定；港股前缀待验） |

> ⚡ 实测修订（T05 落地时 curl 验证）：`stock_code`/`short_name` 嵌在 `codes[0]`、`column_name` 嵌在 `columns[0]`（非平铺列表项顶层字段）；无需 Referer（实测无 Referer 返回 200）；详情 URL 用 PDF 直链 `https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf`（A 股 `H2_` 前缀实测稳定，港股前缀待验）。

### 4.5 新闻（新浪滚动新闻，列表→数组）

| 源字段 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| docid | externalId | none | 新闻 ID |
| title | title | to_string | 标题 |
| ctime | publishedAt | to_iso_date | 发布时间（**T06 实测：ctime 为 Unix 秒级时间戳字符串**，如 `"1748275048"`，非 yyyy-MM-dd） |
| intro | summary | to_string | 摘要 |
| url | url | none | 原文链接 |
| media_name | source | to_string | 来源媒体 |
| keywords | keywords | to_string | 关键词（用于命中/关联） |

### 4.6 政策（gov.cn HTML，列表→数组）

| HTML 提取项 | 目标字段 | transform | 说明 |
| --- | --- | --- | --- |
| a 文本 | title | to_string | 标题（含发文单位/文号） |
| 日期文本 | publishedAt | to_iso_date | 发布日期 |
| a@href | url | none | 详情链接 |
| （正则切标题） | department | to_string | 发文单位（从标题前缀切分） |
| （关键词字典命中） | relatedIndustries | to_string | 关联行业（逗号分隔，后端 split） |

### 4.7 示例 mapping JSON 草稿

行情 `quote-em.json`（与 quote-sample.json 同构，可直接替换 ts_code→f57 等）：

```json
[
  {"source": "f57", "target": "externalCode", "transform": "none"},
  {"source": "f58", "target": "name", "transform": "to_string"},
  {"source": "f43", "target": "price", "transform": "to_decimal"},
  {"source": "f46", "target": "open", "transform": "to_decimal"},
  {"source": "f44", "target": "high", "transform": "to_decimal"},
  {"source": "f45", "target": "low", "transform": "to_decimal"},
  {"source": "f60", "target": "preClose", "transform": "to_decimal"},
  {"source": "f169", "target": "change", "transform": "to_decimal"},
  {"source": "f170", "target": "changePct", "transform": "to_decimal"},
  {"source": "f47", "target": "volume", "transform": "to_long"},
  {"source": "f48", "target": "amount", "transform": "to_decimal"},
  {"source": "f171", "target": "amplitude", "transform": "to_decimal"}
]
```

估值 `valuation-em.json`（暂定，T03 实测后校准）：

```json
[
  {"source": "f57", "target": "externalCode", "transform": "none"},
  {"source": "f162", "target": "peTtm", "transform": "to_decimal"},
  {"source": "f163", "target": "pb", "transform": "to_decimal"},
  {"source": "f167", "target": "peStatic", "transform": "to_decimal"},
  {"source": "f173", "target": "psTtm", "transform": "to_decimal"}
]
```

财务 `finance-em.json`：

```json
[
  {"source": "SECURITY_CODE", "target": "externalCode", "transform": "none"},
  {"source": "TOTALOPERATEREVE", "target": "revenue", "transform": "to_decimal"},
  {"source": "PARENTNETPROFIT", "target": "netProfit", "transform": "to_decimal"},
  {"source": "XSJLL", "target": "netProfitMargin", "transform": "to_decimal"},
  {"source": "XSMLL", "target": "grossProfitMargin", "transform": "to_decimal"},
  {"source": "ROEJQ", "target": "roe", "transform": "to_decimal"},
  {"source": "REPORT_DATE", "target": "reportDate", "transform": "to_iso_date"}
]
```

公告 `announce-em.json`（`source` 用嵌套路径；`FieldMapper` 对数组下标路径的支持以 T05 实测结构为准，必要时在 adapter 内展平后映射）：

```json
[
  {"source": "art_code", "target": "externalId", "transform": "none"},
  {"source": "title", "target": "title", "transform": "to_string"},
  {"source": "notice_date", "target": "publishedAt", "transform": "to_iso_date"},
  {"source": "columns[0].column_name", "target": "category", "transform": "to_string"},
  {"source": "codes[0].stock_code", "target": "externalCode", "transform": "none"},
  {"source": "codes[0].short_name", "target": "subjectName", "transform": "to_string"}
]
```

新闻 `news-sina.json`：

```json
[
  {"source": "docid", "target": "externalId", "transform": "none"},
  {"source": "title", "target": "title", "transform": "to_string"},
  {"source": "ctime", "target": "publishedAt", "transform": "to_iso_date"},
  {"source": "intro", "target": "summary", "transform": "to_string"},
  {"source": "url", "target": "url", "transform": "none"},
  {"source": "media_name", "target": "source", "transform": "to_string"},
  {"source": "keywords", "target": "keywords", "transform": "to_string"}
]
```

> 列表类源（公告/新闻/政策）取数后由 adapter 内部把数组映射产出，或由应用层聚合服务在 `SourceResult.data` 里以 `items` 数组承载（待 T09 聚合 DTO 定型）。`FieldMapper` 当前为单条 Map 映射，列表场景建议子类 adapter 在 `doFetch` 内遍历映射后把数组放 `data.items`（无需改框架）。

## 🔢 5. 代码体系映射（`subject_master.external_codes`）

各源代码格式 → 内部主表 `external_codes` JSON key 建议：

```json
{
  "eastmoney_secid": "1.600519",
  "eastmoney_code": "600519",
  "tushare": "600519.SH",
  "sina": "sh600519"
}
```

| key | 格式样例 | 用途（adapter） |
| --- | --- | --- |
| `eastmoney_secid` | `1.600519`（沪）/ `0.000001`（深）/ `116.00700`（港） | 行情 push2、估值 push2 |
| `eastmoney_code` | `600519` | 公告 `stock_list`、财务 `SECURITY_CODE` |
| `tushare` | `600519.SH` / `000001.SZ` / `00700.HK` | tushare 系接口（备选升级） |
| `sina` | `sh600519` / `sz000001` / `hk00700` | 新浪实时行情 / 新闻个股匹配 |

> 东财 secid 与纯代码可互相派生（split `.`）：主表存 `eastmoney_secid` 即可，公告/财务 adapter 内 split 取纯代码段。也可两者都存以省一次解析——倾向**都存**（迁移成本低、可读性高）。港股权限内 tushare 用 `00700.HK`、东财 secid `116.00700`、sina `hk00700`。初始化脚本（T01/主数据）按此填充 `external_codes`。

## 🔌 6. 各源 SourceAdapter 契约要点（供 T03~T08）

每源继承 [`AbstractSourceAdapter`](/backend/src/main/java/com/info/platform/infrastructure/aggregation/AbstractSourceAdapter.java)，实现 `doFetch` / `mappingConfig` / `resilienceSpec` / `sourceLabel` 四件事，按需覆写 `onDegraded`。弹性对齐 [技术方案 §4.3 流程 1](/docs/02-设计/技术方案-信息整合平台.md) 超时级联收敛（AggregationService 总预算 2s，各源并行 `CompletableFuture`，**单源最坏耗时 ≤ 2s**，故重试源须缩短单次超时）。

### 6.1 T03 行情源 `QuoteAdapter`（东财 push2）

- `doFetch`：GET `https://push2.eastmoney.com/api/qt/stock/get?secid={eastmoney_secid}&fields=f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f162,f163,f167,f169,f170,f171,f173&fltt=2&invt=2` → 解析 `data` 节点为 `rawMap`（含 f57/f58/f43…）。无数据（盘外或停牌）返回 `Optional.empty()` → MISSING。
- 参数：`secid` 来自 `subject.getExternalCodes().get("eastmoney_secid")`。
- `rawMap`：f57/f58/f43/f46/f44/f45/f60/f169/f170/f47/f48/f171（行情）+ f162/f163/f167/f173（估值，供 ValuationAdapter 复用或本源合并产出）。
- `resilienceSpec`：`ResilienceSpec.noRetry(Duration.ofMillis(1500))`（实时行情重试无意义、且为热路径，对齐流程 1 行情 1.5s 重试 0）。
- `onDegraded`：不覆写（默认 MISSING，不阻断）。
- `sourceLabel`：`"东财行情"`。
- 缓存：Caffeine TTL 5s（对齐 ADR-0005）。

### 6.2 T04 财务源 `FinanceAdapter`（东财 datacenter F10）

- `doFetch`：GET `https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_F10_FINANCE_MAINFINADATA&columns=ALL&filter=(SECURITY_CODE="{eastmoney_code}")&pageNumber=1&pageSize=1&sortColumns=REPORT_DATE&sortTypes=-1` → 取 `result.data[0]` 为 `rawMap`。需带 `Referer: https://data.eastmoney.com/`（东财软限频，无 token；**无 Referer 返回 400**）。
- ⚡ 实测修订（T04 落地时 curl 验证）：真实响应路径为 `result.data[0]`（多一层 `result`，`data` 为数组取首元素），原文档 `data.list[0]` 有误；且必须带 Referer 头，无 Referer 返回 400。
- 参数：`SECURITY_CODE` 来自 `eastmoney_code`；`pageSize=1` + 按 REPORT_DATE 倒序取最新一期。
- `rawMap`：SECURITY_CODE/TOTALOPERATEREVE/PARENTNETPROFIT/XSJLL/XSMLL/ROEJQ/REPORT_DATE。
- `resilienceSpec`：`ResilienceSpec.noRetry(Duration.ofSeconds(2))`（季频只读，重试无收益）。
- `onDegraded`：不覆写。
- `sourceLabel`：`"东财财务"`。
- 缓存：TTL 1h（对齐 ADR-0005 财务）。

### 6.3 T04 估值源 `ValuationAdapter`（东财 push2 f 字段）

- `doFetch`：复用 QuoteAdapter 的端点调用（或由聚合服务把行情结果传给估值 adapter 共享一次调用，省一次往返）→ `rawMap` 取 f162/f163/f167/f173。
- **前置验证（T03/T04 落地首日）**：实跑对几只样本股确认 f162=PE(TTM)、f163=PB、f167=PE(静)、f173=PS(TTM) 的语义；**若不符，切 tushare `daily_basic`（需 2000 积分，见 §7 拍板点 3）**。
- `resilienceSpec`：`ResilienceSpec.noRetry(Duration.ofSeconds(2))`；若与行情合并调用则不另配弹性（共享 QuoteAdapter 的 ResilienceSpec）。
- `onDegraded`：不覆写。
- `sourceLabel`：`"东财估值"`。
- 缓存：TTL 1h。

### 6.4 T05 公告源 `AnnounceAdapter`（东财 np-anotice-stock）

- `doFetch`：GET `https://np-anotice-stock.eastmoney.com/api/security/ann?sr=-1&page_size=3&page_index=1&ann_type=A&client_source=web&stock_list={eastmoney_code}` → `data.list` 为公告数组。空数组返回 `Optional.empty()` → MISSING。
- 参数：`stock_list` 来自 `eastmoney_code`；`page_size=3`（PRD 取最新 3 条）。
- `rawMap`：每条 art_code/title/notice_date/`codes[0].stock_code`/`codes[0].short_name`/`columns[0].column_name`（字段嵌套在 `codes[0]`/`columns[0]` 数组，非平铺）；`url` 由 `art_code` 拼 A 股 PDF 直链 `https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf`（实测稳定；港股前缀待验；详情正文需二次调 `np-cnotice-stock`，首期仅取标题+时间+分类即可满足 PRD 公告分区）。
- ⚡ 实测修订（T05 落地时 curl 验证）：`stock_code`/`short_name` 嵌在 `codes[0]`、`column_name` 嵌在 `columns[0]`（非平铺列表项顶层字段）；无需 Referer（实测无 Referer 返回 200）；详情 URL 用 PDF 直链 `H2_<art_code>_1.pdf`（A 股前缀实测稳定，港股前缀待验）。
- `resilienceSpec`：**建议 `ResilienceSpec.of(Duration.ofSeconds(1), 1, Duration.ofMillis(200))`**（幂等只读列表，允许 1 次重试；单次 1s + 200ms 退避 ≈ 最坏 2.2s，略超预算，靠聚合层 `orTimeout` 兜底降级）。
  > ⚠️ 技术方案 §4.3 流程 1 原写"公告 超时 2s 重试 1"与 2s 页预算冲突（2s×2 退避 = 4s+）。本调研建议单次超时收为 1s 以满足级联收敛；若开发评估连接级失败更常见，也可改 `noRetry(2s)`（与流程 1 表述一致、最坏 2s）。**此项需开发在 T05 确认 ResilienceRunner 是否区分"超时"与"连接失败"**——若不区分，采用 `noRetry(2s)`。
- `onDegraded`：覆写为返回 MISSING（空数组语义，不阻断）。
- `sourceLabel`：`"东财公告"`。
- 缓存：TTL 10min。

### 6.5 T06 新闻源 `NewsAdapter`（新浪滚动新闻）

- `doFetch`：GET `https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid={news_lid}&num=20&page=1` → `result.data[]` 数组为新闻（多一层 `result`）。按 `subject.name` 关键词本地过滤与该标的相关的条目；无命中返回 `Optional.empty()` → MISSING。**必须带 `User-Agent: Mozilla/5.0 ...` + `Referer: https://finance.sina.com.cn/` 防 403**。
- 参数：`lid` 选财经滚动分类，**默认 `2510`**（`2510`~`2518` 可用，财经滚动分类；旧值 `1685`~`1689` 已失效，返回 `code:11`「列表和页面没有经过注册」）；`num=20` 后本地按 stock name 关键词命中取 ≤3 条。
- `rawMap`：每条 docid/title/ctime/intro/url/media_name/keywords。
- ⚡ 实测修订（T06 落地时 curl 验证）：响应路径为 `result.data[]`（多一层 `result`，原文档 `data` 有误）；`lid=2510` 可用（`1685`~`1689` 失效，返回 `code:11`）；`ctime` 为 Unix 秒级时间戳字符串（如 `"1748275048"`，非 yyyy-MM-dd）；**不支持个股过滤**（`stock=600519` 与 `k=贵州茅台` 参数均被忽略）→ 确认走本地关键词匹配（Spike-1 §2.5 预判成立）；须带 UA(Mozilla) + Referer(finance.sina.com.cn) 防 403。
- `resilienceSpec`：`ResilienceSpec.noRetry(Duration.ofSeconds(2))`。
- `onDegraded`：覆写为返回 MISSING（空数组语义）。
- `sourceLabel`：`"新浪新闻"`。
- 缓存：TTL 5min。
- **个股关联待验**：若新浪滚动新闻关键词命中率低，T06 二级切东财个股资讯 API（`search-api-web.eastmoney.com`，🟡 待验），作 `onDegraded` 之外的"主备切换"配置。

### 6.6 T07 政策源 `PolicyAdapter`（gov.cn 政策库 HTML 多源聚合）

- `doFetch`：GET `https://www.gov.cn/zhengce/`（及央行 `pbc.gov.cn`、证监会 `csrc.gov.cn` 发布页）→ Jsoup 解析 `<a>` 列表取标题/日期/链接；按"最近 7 天"过滤；关联行业靠标题关键词字典命中。
- 参数：无代码（政策流不绑个股，PRD 场景按 industry 关联自选标的）；按 `days=7` 时间窗过滤。
- `rawMap`：每条 title/publishedAt/url/department/relatedIndustries。
- `resilienceSpec`：`ResilienceSpec.of(Duration.ofSeconds(2), 0, Duration.ZERO)`（HTML 抓取较重，失败不重试）。
- `onDegraded`：覆写为 MISSING（不阻断，技术方案 §4.3 流程 1 政策源 missing）。
- `sourceLabel`：`"政策时事"`。
- 缓存：TTL 10min。
- **降级补充**：官方发布页抓取失败时，可降级调 sina 滚动新闻"政策"分类（lid），保证政策流可用性。

### 6.7 T08 事件源 `EventAdapter`（复用 event-monitor）

- 复用用户环境既有事件监控能力（watch-positions event-monitor 技能），不直连外部源；`doFetch` 从既有事件入库表/接口取某标的相关重大事件。
- `resilienceSpec`：本地调用，`noRetry(500ms)`；`sourceLabel`：`"事件监控"`。
- 详见技术方案任务表 T08（复用 event-monitor，估时 1 人天）。

## 🗳️ 7. 待用户拍板的选型点

| # | 拍板点 | 选项 | 建议 | 影响任务 |
| --- | --- | --- | --- | --- |
| 1 | **是否引入 Python sidecar（akshare）** | 全免费 Java 直连栈（默认）/ 引入 akshare 覆盖乐咕估值等 | **否**——6 类源免费 Java 直连均满足首期，sidecar 增加部署/运维成本与 ADR-0002 单包形态偏离 | 全部 |
| 2 | **政策源选哪个官方源 + 是否接受 HTML 抓取** | gov.cn 政策库为主（默认）/ 证监会+央行为主 / sina 政策分类降级 | gov.cn 为主 + 央行/证监会发布页 + sina 降级，HTML+Jsoup 抓取；爬虫合规单列 Spike | T07 |
| 3 | **tushare 是否付费买积分（2000+）** | 否（用东财免费估值 f 字段，T03 实测语义）/ 是（用 tushare `daily_basic`+`fina_indicator` 字段干净） | 先否——T03 实测东财估值 f 字段语义；若不准再买 tushare 2000 积分（估值+财务一起升级） | T03/T04 |
| 4 | **估值与行情是否同端点合并调用** | 合并（省一次往返）/ 分离（两个 adapter 各调） | 合并——东财 push2 一次拿 OHLCV+估值列；ValuationAdapter 共享 QuoteAdapter 调用结果 | T03/T04 |
| 5 | **新闻个股关联策略** | 新浪滚动新闻关键词匹配（默认）/ 东财个股资讯 API（待验） | 默认新浪关键词；T06 落地若命中率不达标切东财个股资讯 | T06 |
| 6 | **公告正文是否首期取** | 仅标题+时间+分类+URL（默认）/ 取正文（二次调 np-cnotice-stock） | 默认仅元数据——PRD 公告分区展示"最新 3 条公告"标题+链接即满足 | T05 |

## ⚠️ 8. 风险与跟进

| 风险/缺口 | 影响 | 对策/跟进 | 置信 |
| --- | --- | --- | --- |
| 东财估值 f 字段语义未实测确认 | 估值列可能错位（PE/PB 混淆） | T03 落地首日对样本股（600519/000001/00700）实跑确认；不符则切 tushare `daily_basic`（触发 §7 拍板点 3） | 🟡 |
| 东财 push2/datacenter 无明示限频，按 IP 软限 | 高频调用可能被临时封 IP | Caffeine TTL 兜底（行情 5s/财务 1h/公告 10min）降实际 QPS；监控 429/封禁告警；若封禁频繁→评估 Redis 令牌桶（ADR-0003 跟进条件） | 🟡 |
| 新浪实时行情需 Referer 头且高频封 IP | 新浪作降级兜底时不稳 | 新浪仅作 tushare/东财之外的兜底；doFetch 必带 `Referer: https://finance.sina.com.cn` | 🟡 |
| 巨潮 cninfo 端点 JS 渲染难直验 | 公告三选不可用 | T05 优先东财；cninfo 作备选，落地时用浏览器抓包确认 POST 端点 | 🔴 |
| 财联社 cls.cn nodeapi 端点已变更/停用 | 快讯备选不可用 | T06 需重新定位 cls 现行端点；或弃用快讯，新浪滚动新闻足够 | 🔴 |
| gov.cn/各官方 RSS 已停用 | 政策源无统一 API | 已对齐技术方案 §8——HTML 抓取 + 爬虫合规 Spike；多源聚合降单点风险 | 🟢 |
| 政策关联行业靠关键词字典命中 | 命中率/准确率待评 | 关键词字典初始化（行业→政策热词映射），T24/T28 配 AI 倾向判断校准 | 🟡 |
| 列表类源（公告/新闻/政策）映射 | `FieldMapper` 当前单条 Map | 子类 adapter 在 `doFetch` 内遍历映射产出 `data.items` 数组，不改框架；T09 聚合 DTO 定型 | 🟢 |
| **限频极紧源是否引入 Redis 令牌桶** | 单实例 Caffeine+令牌桶够；多实例需共享 | ADR-0003 跟进条件：若东财封禁频繁或后续多实例→引入 Redis 令牌桶（ADR-0005 已预留升级线） | 🟡 |

## ✅ 9. 验证假设结论（对齐技术方案 §8 Spike-1）

- ✅ **假设 1**「各源字段可经 JSON 映射表配置化」——成立：6 类源字段均可落 `field-mapping/*.json`，由 [`FieldMapper`](/backend/src/main/java/com/info/platform/infrastructure/aggregation/FieldMapper.java) 加载映射；列表类源在 adapter 内遍历映射产出数组，框架无需改。
- ✅ **假设 2**「外部 code→内部 `subject_id` 可主表 `external_codes` 一次性映射」——成立：`eastmoney_secid`/`eastmoney_code`/`tushare`/`sina` 四 key 覆盖默认栈 + 升级备选，主数据初始化脚本按此填充（T01/T03）。
- 🔄 **调整项**：技术方案 §4.3 流程 1 公告"超时 2s 重试 1"与 2s 页预算冲突，建议改单次 1s 重试 1 或 noRetry(2s)，待开发在 T05 确认 `ResilienceRunner` 是否区分超时与连接失败。
- 🔄 **未触发 schema 注册中心**：字段非高频变动，JSON 映射表足够；保持 ADR-0003 原决策不变。

---

本调研遵循个人知识库《编辑文件规范》，权威依据见：

- [02 设计架构 · 选型五维度](/project-development/02-design/index.md#tech-evaluation)
- [通用设计方案库 · 方案 04 缓存 / 方案 07 弹性](/project-development/02-design/solution-catalog.md)
- [技术方案-信息整合平台](/docs/02-设计/技术方案-信息整合平台.md) §4.4 / §4.3 流程 1 / §8 / ADR-0003
- [REQ-20260920-01-信息整合平台](/docs/01-需求/REQ-20260920-01-信息整合平台.md)
- T02 框架：[SourceAdapter](/backend/src/main/java/com/info/platform/domain/aggregation/SourceAdapter.java) / [AbstractSourceAdapter](/backend/src/main/java/com/info/platform/infrastructure/aggregation/AbstractSourceAdapter.java) / [FieldMapper](/backend/src/main/java/com/info/platform/infrastructure/aggregation/FieldMapper.java) / [ResilienceSpec](/backend/src/main/java/com/info/platform/infrastructure/common/ResilienceSpec.java)
