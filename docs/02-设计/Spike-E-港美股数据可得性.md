# Spike-E · 港美股数据面可得性实测（M29 全市场覆盖前置）

> **定位**：M29「全市场覆盖（A股/港股/美股）」前置数据可得性 Spike，对齐 M27 Spike-C/D 做法（curl 实测留证 + 结论先行 + 开发期待复核条款）。
> **实测环境**：2026-09-29（周二）18:00~18:30 本机会话；macOS / curl 8；所有请求 `-m 8`；UA 统一浏览器款（与 `EastMoneyHttpSupport`/`TencentQuoteClient` 同款）；新浪请求带 `Referer: https://finance.sina.com.cn`，东财 datacenter 带 `Referer: https://data.eastmoney.com/`。
> **已知约束**：本机 push2.eastmoney.com 处 IP 封禁期（M27 附录 A.1 同款，本轮复测仍封）——push2 族结论均标注「封禁期待复核」；datacenter / search-api / qt.gtimg.cn / hq.sinajs.cn 均为**不同宿主**，本轮全部可达。
> **现状锚点**：标的池 A_SHARE 5221 / HK 1 / US 0；`MarketSyncSpec` 已预留 `HK_STOCK` 桶（`m:116+t:3,m:116+t:4`，push2 口径 total=2927 实测先例）。

---

## 0. 总结论（一句话版）

**港美股「建池 + 行情 + 行业 + 资讯」四数据面均有免费可用通道，主通道全部避开 push2 封禁域**：建池/行业 = 东财 datacenter F10 档案报表（港 6961 / 美 21561 全量 + BELONG_INDUSTRY 行业字段，一次拉取双用途）；行情 = 腾讯 `qt.gtimg.cn`（hk/us 前缀批量，既有 `TencentQuoteClient` 扩布局即用）备新浪 `hq.sinajs.cn`；资讯 = 东财 `search-api-web` 关键词搜索（港美均 200 结构化）+ 新浪 roll 频道（lid=2516/2517）。英文 RSS（Yahoo/Nasdaq）403/404 不可用，中文源已足。既有通道复用结论：**无需新客户端架构**，扩映射 + 仿 `EastmoneyDatacenterClient` 新增 1 个 F10 列表客户端 + `TencentQuoteClient` 增美股字段布局分支即可。

---

## 1. E-1 · 港美股标的全量列表（建池）

**结论先行：✅ 可得——主通道 = 东财 datacenter F10 档案报表无过滤全量分页（含行业字段，与 E-3 同通道一次解决）；push2 clist 精确桶仍封禁待复核；新浪/腾讯免费列表通道均死（留证）。量级判定：全量拉取 + 精选建池（港股 ~2600 主板+GEM / 美股 ~9000 主板），美股建议按市值/成交额阈值精选而非全量入池。**

### 1.1 实测记录

**E-1a 东财 push2 clist（封禁期不可达，留档待复核）**

```
URL: https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=5&po=1&np=1&fltt=2&invt=2&fid=f12&fs=m:116+t:3&fields=f12,f13,f14,f100
URL: https://push2.eastmoney.com/api/qt/clist/get?...&fs=m:105,m:106,m:107&fields=f12,f13,f14,f100
结果: curl (52) Empty reply from server（港股 fs / 美股 fs 均同，http=000，0.05~0.07s 即断）
结论: 与 M27 附录 A.1 同款 IP 级封禁期持续——clist 建池路径本轮未获实证；fs=m:116+t:3,t:4 港股桶在
      MarketSyncSpec 已有定义（total=2927 为 2026-09-24 解封窗口实测先例），解封后可直接复用；
      美股桶 fs=m:105,m:106,m:107 待解封复核（市场码语义见 §5.2 映射表）
```

**E-1b 东财 datacenter F10 档案全量（✅ 本轮主发现，未封禁）**

```
URL: https://datacenter.eastmoney.com/securities/api/data/v1/get?reportName=RPT_HKF10_INFO_ORGPROFILE&columns=SECUCODE,SECURITY_NAME_ABBR,BELONG_INDUSTRY&sortColumns=SECUCODE&sortTypes=1&pageNumber=1&pageSize=5&source=F10&client=PC
头:   UA 浏览器款 + Referer: https://data.eastmoney.com/（datacenter 软限频要求，缺 Referer 不可用——EastmoneyDatacenterClient 同款先例）
结果: HTTP 200；港股 {"pages":1393(pageSize=5),"count":6961,"data":[{"SECUCODE":"00001.HK","SECURITY_NAME_ABBR":"长和","BELONG_INDUSTRY":"综合企业"},…],"success":true}
      单过滤实例（E-3 复用同报表）：filter=(SECUCODE="00700.HK") → 1 行，字段含 BELONG_INDUSTRY/ISIN_CODE/LISTING_DATE 等 ~40 列
URL: 同款 reportName=RPT_USF10_INFO_ORGPROFILE
结果: HTTP 200；美股 {"pages":4313(pageSize=5),"count":21561,"data":[{"SECUCODE":"A.N",…,"BELONG_INDUSTRY":"生命科学工具和服务"},…]}
      样本含 "AABVF.F"（OTC 粉单类，BELONG_INDUSTRY=null）——证明 SECUCODE 后缀即交易所位，且 OTC 行业缺失可天然过滤
结论: 全量分页可达（服务端 count/pages 直出，翻页契约与 EastmoneyDatacenterClient 既有实现同构）；pageSize 上限未压测，
      按既有客户端 500/页 惯例 → 港股 ~14 页 / 美股 ~44 页，全量成本可忽略
```

**E-1c 新浪列表（❌ 港美股均死，复确认 ADR-0027）**

```
URL: https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHKStockData?page=1&num=5&sort=symbol&asc=1&node=hk_stocks
     （另测 node=qbgq / hk_main / hk_gem / hk_all）
结果: 全部 HTTP 200 但体 = []（空数组）——hk_stocks 复确认 ADR-0027「新浪无港股节点」
URL: .../Market_Center.getUSStockData?page=1&num=5&...&node=us_a（另测 node=usdata）
结果: {"__ERROR":3,"__ERRORMSG":"Service not found"}——美股列表服务已下线
结论: 新浪备选列表通道对港美股不可用，留档排除（SinaSubjectListClient 维持仅 A 股桶，端口契约不变）
```

**E-1d 腾讯列表（❌ 板块排行不支持港美股；smartbox 仅单点检索可用）**

```
URL: https://proxy.finance.qq.com/cgi/cgi-bin/rank/pt/getRank?board_type=hk&sort_type=price&direct=down&offset=0&count=20
头:   UA + Referer: https://gu.qq.com/
结果: HTTP 200 {"code":0,"msg":"ok","data":{"rank_list":[],"offset":0,"total":0}}——board_type=hk 空集，港股排行不支持
URL: https://smartbox.gtimg.cn/s3/?v=2&q=00700&t=all
结果: HTTP 200 GBK，v_hint="hk~00700~腾讯控股~txkg~GP^jj~007005~中金新医药股票C~zjxyygpc~KJ^jj~…"
      ——输入联想接口：市场前缀(hk) + 代码 + 名称 + 类型标记(GP=股票 / KJ=基金) 可辨，可做代码检索/类型校验辅助，但非全量列表通道
结论: 腾讯无免费港美股全量列表 API（M27 A.3 getRank 仅 board_type=hy 即 A 股申万 31 可用）
```

### 1.2 字段映射（E-1b 主通道）

| F10 列 | 含义 | 落库映射 |
| --- | --- | --- |
| `SECUCODE` | `代码.市场后缀`（`00700.HK` / `AAPL.O` / `A.N` / `AABVF.F`） | 派生 secid + subject_code（见 §5.2） |
| `SECURITY_NAME_ABBR` | 中文简称 | `subject.name` |
| `BELONG_INDUSTRY` | 东财行业分类（中文，港美统一口径） | `subject.industry`（null 容忍，与 A 股 f100 "-"→null 同约定） |

### 1.3 量级与建池策略判定

| 市场 | F10 全量 | 有效过滤后（估） | 建议 |
| --- | --- | --- | --- |
| 港股 | 6961（含基金/债券/权证等） | ~2600（主板+GEM 股票） | 过滤 = 5 位纯数字代码 + `BELONG_INDUSTRY` 非空；与 push2 桶 total=2927 数量级吻合 → **可全量入池** |
| 美股 | 21561（含 OTC `.F` 等） | ~9000（`.N` NYSE + `.O` NASDAQ 主板） | 过滤 = 后缀 ∈ {`.N`,`.O`} + `BELONG_INDUSTRY` 非空；**精选建池**：按市值/成交额阈值（如市值 ≥ 20 亿美元）收敛至 ~1500±，避免长尾僵尸股污染推荐漏斗 |

### 1.4 风险

- F10 全量含非股票类（港 6961 > 桶 2927），过滤规则依赖代码格式 + 行业非空的启发式——**开发期首跑抽样对账**（与 smartbox GP 标记 / 解封后 push2 桶 total 双向核对）。
- `.A`（AMEX）后缀与 `.F`（OTC）边界未实测样本——首跑统计后缀分布留档（§6 复核条款）。
- datacenter 分页翻页期间数据漂移：沿用 `sortColumns=SECUCODE` 稳定排序（对齐东财 clist `fid=f12` 策略）。
- push2 解封后 clist 建池更精确（t:3+t:4 桶）——datacenter F10 仍保留为备链（list 源端口双实现先例：RoutingSubjectListSource）。

---

## 2. E-2 · 港美股实时行情

**结论先行：✅ 可得——主通道 = 腾讯 `qt.gtimg.cn`（`hk00700`/`r_hk00700`/`usAAPL` 前缀，批量逗号拼接，GBK，一行一标的，价/涨跌幅/量额/市值/币种齐备）；备通道 = 新浪 `hq.sinajs.cn`（`hk_`/`rt_hk_`/`gb_`，需 Referer，港股实时 + 美股含盘前盘后价）；东财 push2 `secid=116.HK00700`/`105.AAPL` 封禁期待复核。既有 `TencentQuoteClient` 已支持 HK 布局（ADR-0031），美股布局分叉需第三套字段位（代码段大写 + 73 位布局）。**

### 2.1 实测记录

**E-2a 腾讯 qt.gtimg.cn（✅ 港/美/混批全通）**

```
URL: https://qt.gtimg.cn/q=r_hk00700        → 200 GBK 440B，78 字段
     https://qt.gtimg.cn/q=hk00700           → 200 GBK 438B（r_ 增强前缀与裸前缀字段位一致）
     https://qt.gtimg.cn/q=usAAPL            → 200 GBK 388B，73 字段
     https://qt.gtimg.cn/q=r_hk00700,usAAPL,usMSFT → 200 1224B（港美混批一次请求 OK）
     既有客户端请求形态（无 UA 亦可，防御性携带）
样本: 腾讯控股 432.000 HKD（-7.800 / -1.77%，时间 2026/09/29 16:08:09 收盘竞价时段）
      苹果 338.40 USD（-2.67 / -0.78%，时间 2026-09-28 16:00:01 美东收盘）
```

港股字段位（与 `TencentQuoteClient` 类内核对表一致，复证）+ 本轮新增关注位：

| 索引 | 含义 | → 中间结构 |
| --- | --- | --- |
| 3/4/5 | 现价/昨收/今开 | f43/f60/f46（既有映射零改动） |
| 6/37 | 成交量(股)/成交额(**港元，已是元**) | f47/f48（既有 putAmount hk 分支） |
| 31/32/33/34 | 涨跌/涨跌幅%/最高/最低 | f169/f170/f44/f45 |
| 39/58/59 | PE/PB/换手% | f162/f167/f168（既有 hk 位） |
| **44/45** | **总市值/港股市值（亿 HKD，39291.0085 ≈ 9095140845 股 × 432 自洽）** | 本轮新增候选（热力图聚合用，新键建议 f116/f117 位留设计定） |
| 30 | 源时间戳 | f30（M20 T170 先例） |
| 75 | 币种 `HKD` | 新增候选（跨币种换算标识） |

美股字段位（**新布局，与 A/HK 分叉**）：

| 索引 | 含义 | → 中间结构（建议） |
| --- | --- | --- |
| 1/2 | 中文名/代码（`苹果` / `AAPL.OQ`，**代码段大写带交易所后缀**） | f58/f57 |
| 3/4/5 | 现价/昨收/今开（USD） | f43/f60/f46 |
| 30 | 行情时间（**美东时区**，闭市为最后成交） | f30（需时区归一） |
| 31/32/33/34 | 涨跌/涨跌幅%/最高/最低 | f169/f170/f44/f45 |
| **35** | **币种 `USD`**（注意：HK 布局此位是现价重复——布局分叉点） | 新增候选 |
| 6/36/37 | 成交量(股)/成交量/成交额（**美元，已是元**） | f47/f48 |
| 39 | PE 38.81 | f162 |
| 44/45 | 总市值/流通市值（**亿 USD**，49356.00844） | 热力图聚合用 |
| 48/49 | 52 周高/低 | — |
| 62/63 | 总股本/流通股本 | — |

**E-2b 新浪 hq.sinajs.cn（✅ 备通道，需 Referer）**

```
URL: https://hq.sinajs.cn/list=hk00700,rt_hk00700   （+ Referer: https://finance.sina.com.cn）
     https://hq.sinajs.cn/list=gb_aapl,gb_msft
结果: 全 200 GBK。
      rt_hk00700（实时增强行）= TENCENT,腾讯控股,开439.400,昨收439.800,高439.400,低431.600,现价432.000,
        涨跌-7.800,涨跌幅-1.774,买一432.000,卖一432.200,成交额7808702026.492(HKD),成交量18015236(股),
        PE 15.701,52周高675.134,52周低411.000,2026/09/29,16:08:08 —— 与腾讯同刻同值（成交额/成交量/PE 三字段交叉一致）
      gb_aapl = 苹果,338.4000,-0.78,北京时间2026-09-29 18:20:29,-2.6700,开340.37,高342.99,低338.04,
        52周高345.34,52周低242.89,量32820844,…,总市值4938671029752(美元原值),PE 40.77,总股本14594181530,
        盘后价336.4000,盘后涨跌-0.59,-2.00,盘后时间 Sep 29 06:20AM EDT,收盘时间 Sep 28 04:00PM EDT
注意: hk00700 裸前缀为 15 分钟延迟口径（时间 16:01 vs rt_ 16:08）——备通道取数必须用 rt_hk_ 前缀；
      gb_ 美股含盘前盘后字段（腾讯无），但 PE 口径与腾讯不一致（40.77 vs 38.81，TTM/静态差异）→ 以源标注，不做跨源对账该字段
```

**E-2c 东财 push2 stock/get（封禁期待复核）**

```
URL: https://push2.eastmoney.com/api/qt/stock/get?secid=116.HK00700&fields=f43,f57,f58,f116,f117,f162,f167,f168,f170
结果: curl (52) Empty reply（http=000）——secid 形态 116.HK00700 沿既有约定（注意港股 secid 代码段带 HK 前缀，
      与 f12 派生规则差异已由 MarketSyncSpec 桶先例覆盖）；美股 secid=105.AAPL 同待解封复核
```

### 2.2 风险

- **美股盘中实时性未证**（实测时美股闭市，两源时间均停在美东收盘）——开发期盘中复核条款（§6）；港股实时性已证（16:08 竞价时段数据新鲜）。
- 美股成交额/市值币种为 USD，热力图跨市场聚合需**分市场展示或汇率折算**（决策留给 M29 方案，本 Spike 仅证可得）。
- 腾讯美股 `q=usAAPL` 符号 = `us` + **大写** ticker，与既有 `toTencentSymbol` 的「代码段转小写」实现相反——客户端扩分支时必改点（§5.3）。
- 新浪 `gb_` 盘后字段与腾讯无对应，备通道字段白名单按交集消费。

---

## 3. E-3 · 港美股行业分类（热力图聚合口径）

**结论先行：✅ 可得——主通道 = E-1b 同一 F10 报表的 `BELONG_INDUSTRY` 字段（港股「软件服务」/美股「电脑硬件、储存设备及电脑周边」实测，中文统一口径），行业热力图对港美股采用「个股行情按行业字段聚合」而非板块通道；东财港美股板块行情（push2 `fs=b:BK****`）封禁期不可达待复核；腾讯板块排行不支持港美股（E-1d 已证空集）；恒生行业分类/GICS 官方体系无免费 API，不采用。**

### 3.1 实测记录

**E-3a 东财 datacenter F10 行业字段（✅ 与建池同通道，零额外请求）**

```
URL: https://datacenter.eastmoney.com/securities/api/data/v1/get?reportName=RPT_HKF10_INFO_ORGPROFILE&columns=ALL&filter=(SECUCODE="00700.HK")&pageNumber=1&pageSize=1&source=F10&client=PC
结果: 200；BELONG_INDUSTRY="软件服务"（腾讯控股）；另含 ISIN_CODE / LISTING_DATE / HK_SHARES(9095140845) / CHAIRMAN 等
URL: 同款 reportName=RPT_USF10_INFO_ORGPROFILE&filter=(SECUCODE="AAPL.O")
结果: 200；BELONG_INDUSTRY="电脑硬件、储存设备及电脑周边"（苹果）；SECURITY_TYPE="美股"、BELONG_MARKET="纳斯达克"
      ——美股报表另带 BELONG_MARKET 交易所字段（主板过滤辅助位）
口径说明: 东财自有行业体系（非恒生官方行业分类、非 GICS 官方映射），A股侧 f100 同源同体系 → 跨市场行业词表一致性优于混挂两套官方分类
```

**E-3b 板块级通道（❌/待复核，逐项留证）**

```
东财 push2 板块:   fs=b:BK****（港美股板块行情/成分）同 push2 宿主封禁期不可达——连通性未证（M27 A.4 同结论维持）
腾讯 getRank:      board_type=hk 空集（E-1d）；board_type=hy 仅 A 股申万 31（M27 A.3 已证）——港美股板块排行不支持
恒生行业/GICS:     恒生指数公司行业分类、MSCI/S&P GICS 官方均为付费授权数据，无免费公开 API —— Won't，留档排除
```

### 3.2 热力图聚合方式判定

| 方案 | 可用性 | 判定 |
| --- | --- | --- |
| A 股：板块通道直出（push2 板块 / 腾讯 board_type=hy） | ✅ 既有（M27 双通道） | 不变 |
| 港美股：板块通道直出 | ❌（push2 封禁 + 腾讯不支持） | 放弃 |
| **港美股：个股行情 × `BELONG_INDUSTRY` 就地聚合** | ✅（E-1b + E-2a 两实证拼合） | **采用**——行业行 = 池内个股按 industry 分组的加权（成交额/市值）涨跌幅聚合，纯服务端计算，无新外部依赖 |

聚合口径细节（权重/涨跌家数/是否剔除长尾）属 M29 技术方案裁决面，本 Spike 仅锁定数据可得性。

### 3.3 风险

- 东财港/美行业词表与 A 股 f100 词表存在交集但非逐名相等（「软件服务」vs A 股「软件开发」类目差异）——M29 方案需一张行业归一词表（跨市场行业 Tab 聚合用），首跑全量沉淀词频留档。
- `BELONG_INDUSTRY` 可为 null（OTC 样本）——聚合时 null 行归「未分类」桶或直接排除，方案裁决。
- F10 行业为静态档案字段，更新频率低于行情——行业变更（ rare ）以建池同步任务刷新即可。

---

## 4. E-4 · 港美股资讯源

**结论先行：✅ 可得——个股维主通道 = 东财 `search-api-web.eastmoney.com` 关键词搜索（「腾讯控股」5462 hits / 「AAPL」93 hits / 「00700」950 hits，200 JSONP 结构化 title/content/date，宿主非 push2 族未封禁），与既有 `NewsSourceAdapter` 关键词匹配模式同构；频道维 = 新浪 roll（港股 lid=2516 / 美股 lid=2517，各 10 万条滚动池），资讯脉搏频道覆盖可平移；英文源 Yahoo RSS 403 / Nasdaq RSS 404 维持不可用（M27 A.4 复确认），中文源已满足事件流/资讯覆盖地基。**

### 4.1 实测记录

**E-4a 东财 search-api-web 个股资讯搜索（✅ 港美双证）**

```
URL: https://search-api-web.eastmoney.com/search/jsonp?cb=cb&param=<URL-encoded JSON>
     param = {"uid":"","keyword":"腾讯控股","type":["cmsArticleWebOld"],"client":"web","clientType":"web",
              "clientVersion":"curr","param":{"cmsArticleWebOld":{"searchScope":"default","sort":"default",
              "pageIndex":1,"pageSize":5,"preTag":"<em>","postTag":"</em>"}}}
结果: 200；hitsTotal=5462；文章数组含 date/title/content/code——样本
      「腾讯控股：9月29日斥资10063.37万港元回购23.2万股」（2026-09-29 17:38，即日内回购公告级资讯）
变体: keyword="AAPL"  → 200，hitsTotal=93，样本「英伟达追加1500亿美元回购…苹果（AAPL.US）…」
      keyword="00700" → 200，hitsTotal=950，样本「腾讯控股(00700.HK)连续31日回购…」——代码关键词亦可用，
      但存在跨市场误配风险（纯数字代码建议与名称关键词组合，方案层裁决）
结论: 港美股个股资讯通道成立；em 标签（<em>…</em>）需剥除（既有 SinaNewsClient 类似清洗先例可仿）
```

**E-4b 新浪 roll 频道（✅ 港美频道各 10 万条滚动池）**

```
URL: https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=&num=3&page=1   （+ UA + Referer）
结果: 200；total=100326；data[] 含 title/url/intime/channelid——港股频道滚动资讯（频道维，非个股维）
URL: 同款 lid=2517
结果: 200；total=100417；样本「Etsy借力AI提升个性化体验…」「美国民主党议员要求头部AI实验室…」——美股频道
结论: 资讯脉搏的「频道级热度」模式可平移到港美股（与 A 股 34 源同构接入，roll API 形态简单）
```

**E-4c 英文源（❌ 维持 M27 结论）**

```
URL: https://finance.yahoo.com/news/rss → 403（反爬墙，M27 A.4 同款）
     https://www.nasdaq.com/feed/rss    → 404（服务下线）
结论: 免费英文 RSS 源不可用——Won't；中文源（东财搜索 + 新浪频道）已满足港美股事件流/资讯覆盖地基，
      CNBC robots 否决先例口径不变
```

### 4.2 字段映射与复用

- 东财搜索：`date/title/content/code(文章id)` → 既有资讯事件模型（title/url/摘要/发布时间）同构；关键词来源 = `subject.name` + `external_codes` 派生代码（§5.3 扩派生规则）。
- 新浪 roll：`title/url/intime` → 频道维资讯事件，`lid` 即频道键（港股 2516 / 美股 2517，与 A 股源注册同表）。

### 4.3 风险

- search-api 无官方文档（内部接口），JSONP `cb` 回调名必带；限频未压测——沿既有软限频旋钮（page-interval + ResilienceRunner）。
- 关键词搜索的召回噪声（名称常见词误配，如「苹果」非股票语境）——事件流接入需 AI 归类管道过滤（M15 既有能力复用），Spike 不展开。
- 港美股资讯量 < A 股（AAPL 93 vs 腾讯控股 5462）——热度分位数归一需按市场内排名（M29 方案裁决面）。

---

## 5. E-5 · 既有通道复用性（代码阅读结论，无 curl）

**结论先行：无需新客户端架构。改动收敛为四点：① `MarketSyncSpec` 增美股桶 + f13 映射扩 105/106/107 → `US` 前缀（HK 116→`HK` 已有）；② 新增 1 个 F10 列表客户端（仿 `EastmoneyDatacenterClient` 既有 datacenter 调用模式）挂 `SubjectListSource` 端口；③ `TencentQuoteClient` 增美股字段布局分支 + `us`+大写符号映射；④ `NewsSourceAdapter` 代码派生规则扩港 5 位/美股 ticker。**

### 5.1 阅读范围

`TencentQuoteClient` / `EastMoneyClient` / `EastMoneyListClient` / `EastmoneyDatacenterClient` / `SinaSubjectListClient` / `NewsSourceAdapter` / `MarketSyncSpec` / `SubjectSnapshot`（`backend/src/main/java/com/info/platform/infrastructure/aggregation/` + `application/aggregation/`）。

### 5.2 代码/市场映射总表（E-1b+E-2 实测反推）

| 市场 | F10 SECUCODE | 东财 secid（external_codes.eastmoney） | 内部 subject_code 前缀 | tushare 键 | 腾讯符号 |
| --- | --- | --- | --- | --- | --- |
| 沪 | `600519.SH`（F10 系） | `1.600519` | `SH600519` | `600519.SH` | `sh600519` |
| 深 | `000001.SZ` | `0.000001` | `SZ000001` | `000001.SZ` | `sz000001` |
| 港 | `00700.HK` | `116.HK00700`（**代码段带 HK 前缀**，桶先例） | `HK00700` | `00700.HK` | `hk00700` ✅已证 |
| 美纳斯达克 | `AAPL.O` | `105.AAPL`（待解封复核） | `USAAPL`（新） | `AAPL.O` | `usAAPL` ✅已证 |
| 纽交所 | `A.N` | `106.A`（待解封复核） | `USA` | `A.N` | `usA` |
| 美交所 | 后缀待复核 | `107.` | `US…` | — | — |
| OTC | `AABVF.F` | 不入池 | 不入池 | 不入池 | — |

> `CODE_PREFIX_BY_MARKET_FLAG` 现为 {1:SH, 0:SZ, 116:HK}——扩 105/106/107 → `US`（三码同前缀，交易所差异留 SECUCODE 后缀还原）；未知码抛异常的防御语义保留。

### 5.3 逐客户端复用判定

| 既有组件 | 复用判定 | 改动点 |
| --- | --- | --- |
| `TencentQuoteClient` | ✅ 直接扩 | ① `PREFIX_TO_TENCENT` 加 `US`→`us`，且美股符号**代码段保持大写**（现实现转小写，需分支）；② `mapFields` 增第三套美股字段位（§2.1 表：币种@35/市值@44,45 亿USD/时间美东）；③ HK 新增市值@44,45 亿HKD 位映射（热力图聚合用） |
| `EastMoneyClient`（push2 stock/get） | ✅ 契约不变 | secid 由 external_codes 直读——港美股仅是新增 secid 值域，零代码改动；**封禁期不可用**，auto 链下腾讯为实际主力（现状即如此） |
| `EastMoneyListClient`（push2 clist 建池） | ⚠️ 待复核 | HK 桶 fs 已在 `MarketSyncSpec` 预留；US 桶 `m:105,m:106,m:107` 待解封实证；封禁期建池走下条新客户端 |
| **新增 `EastMoneyF10ListClient`** | 🆕 仿 `EastmoneyDatacenterClient` | 同端点同 Referer 同分页契约（E-1b 实证），实现 `SubjectListSource` 港/美两桶；`RoutingSubjectListSource` 增路由位（push2 解封后 F10 可降为备链） |
| `SinaSubjectListClient` | ✅ 零改动 | 端口契约维持仅 A 股桶（E-1c 已证新浪港美股死） |
| `NewsSourceAdapter` | ✅ 小扩 | 代码派生：现取 secid `.` 后 6 位（`116.HK00700`→`HK00700`? 现按 A 股 6 位约定）——扩为「HK 前 5 位数字 / US 原样 ticker」派生分支；关键词匹配主体不变 |
| `QuoteSourceAdapter`/`ValuationSourceAdapter` | ✅ 零改动 | 备选链经中间结构（f 键）消费，客户端层对齐后透明（ADR-0031 裁定复用） |
| `EastmoneyDatacenterClient` | ✅ 模式母版 | F10 列表客户端直接仿其 reportName/分页/Referer/弹性封装写法 |

---

## 6. 港美股数据面可得性总结表（通道 × 市场 × 可用性）

| 数据面 | 主通道（本轮实证） | 备通道 | 东财 push2 域 | 可用性 |
| --- | --- | --- | --- | --- |
| 建池列表·港股 | 东财 datacenter F10 `RPT_HKF10_INFO_ORGPROFILE`（6961 全量+行业） | smartbox 单点校验；push2 桶 2927（解封后换主） | `fs=m:116+t:3,t:4` 封禁待复核 | ✅ |
| 建池列表·美股 | 东财 datacenter F10 `RPT_USF10_INFO_ORGPROFILE`（21561，过滤 `.N/.O` 后 ~9000） | 无真备（smartbox 校验）；push2 `m:105,106,107` 待复核 | 封禁待复核 | ✅（精选建池） |
| 实时行情·港股 | 腾讯 `qt.gtimg.cn` hk00700（78 字段，价/量额/市值/币种） | 新浪 `rt_hk00700`（实时）/`hk_`（延迟，不用） | `116.HK00700` 封禁待复核 | ✅ |
| 实时行情·美股 | 腾讯 `usAAPL`（73 字段，USD，含市值；盘中实时性待复核） | 新浪 `gb_aapl`（含盘前后，PE 口径差异） | `105.AAPL` 封禁待复核 | ✅ |
| 行业分类·港股 | F10 `BELONG_INDUSTRY`（中文，东财体系） | — | 板块 `fs=b:BK` 封禁待复核 | ✅ |
| 行业分类·美股 | F10 `BELONG_INDUSTRY` + `BELONG_MARKET` | —（GICS/恒生官方付费，Won't） | 同上 | ✅ |
| 个股资讯·港美 | 东财 `search-api-web` 关键词（5462/950/93 hits 三样本） | 新浪搜索页（未测，方案期可选） | —（不同宿主，可用） | ✅ |
| 频道资讯·港股 | 新浪 roll `lid=2516`（10 万滚动池） | 东财频道页（未测） | — | ✅ |
| 频道资讯·美股 | 新浪 roll `lid=2517`（10 万滚动池） | 同上 | — | ✅ |
| 英文源 RSS | —（Yahoo 403 / Nasdaq 404） | — | — | ❌ Won't |
| 板块行情直出·港美 | —（push2 封禁 + 腾讯不支持） | **个股×行业就地聚合替代**（采用） | 封禁待复核 | ⚠️ 替代方案 ✅ |

## 7. 推荐选型（主备）与开发期待复核清单

**推荐主备（M29 方案采用基线）**：

| 数据面 | 主 | 备 | 备注 |
| --- | --- | --- | --- |
| 港美股建池+行业 | 东财 datacenter F10 双报表（一次拉取双用途） | push2 clist 精确桶（解封后升主，F10 降备） | 沿 list 源端口双实现先例 |
| 港美股行情/估值 | 腾讯 `qt.gtimg.cn`（扩 US 布局） | 新浪 `hq.sinajs.cn`（`rt_hk_`/`gb_`） | 东财 push2 解封后回 auto 链首位 |
| 行业热力图港美股 | 个股行情 × `BELONG_INDUSTRY` 就地聚合 | push2 板块通道（解封后可选直出） | A 股侧 M27 双通道不变 |
| 港美股资讯 | 东财 search-api（个股维）+ 新浪 roll lid=2516/2517（频道维） | — | AI 归类管道复用过滤噪声 |

**开发期待复核条款（首跑回填本表，沿 M27 §4.2.5 / M20 Spike-B 复核栏先例）**：

1. push2 解封复核：`fs=m:116+t:3,t:4`（港桶 total 应 ~2927±）、`fs=m:105,m:106,m:107`（美股桶 total 留档）、`secid=116.HK00700`/`105.AAPL` 行情、港美股板块 `fs=b:BK****`——四项一次会话补测。
2. 美股盘中实时性：交易日美东 09:30~16:00 窗口实测 `usAAPL` 时间戳推进 + 与新浪 `gb_` 同刻对价。
3. F10 全量过滤对账：美股 SECUCODE 后缀分布统计（`.N/.O/.A/.F` 计数留档），AMEX 后缀勘定；港股过滤后数量与 2927 对照。
4. datacenter F10 分页 pageSize 上限压测（按 500/页 惯例预期可达，超限降 100）。
5. search-api 限频边界（沿软限频旋钮，1 req/s 起步观察）。

---

## 8. T250 robots 三验结论（2026-09-29 补测回填）

> 实测环境：macOS / curl 8，`-m 8`，浏览器款 UA（与本报告 §实测环境同款）；逐源拉取宿主 `/robots.txt` 判读对应路径是否 Disallow。判读口径沿 M13 普查 §1.1（RFC 9309）：**robots.txt 缺失（404/网关错误页）= 宿主未发布抓取限制**；CNBC 式显式 Disallow 才否决。使用口径：个人研究用途 + 低频访问（建池日一轮 + 资讯轮询 interval ≥30min），与既有 34 源同口径。

| # | 源 | 宿主 | `/robots.txt` 实测 | 判读 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | 东财 datacenter F10 两报表（RPT_HKF10/USF10_INFO_ORGPROFILE，建池+行业） | `datacenter.eastmoney.com` | HTTP 200，但 body 为 API 网关 JSON 错误 `{"message":"全局错误:请求url格式异常:/robots.txt"}` | 接口型宿主未发布 robots.txt，无任何 Disallow 规则 | **达标**（沿「无 robots = 无限制」口径） |
| 2 | 东财 search-api-web 个股资讯搜索（em-search-hk/us 两 preset 源） | `search-api-web.eastmoney.com` | HTTP 404（JSON 错误体） | 同上，未发布 robots.txt | **达标**（内部搜索接口无 robots 面；限频按 §7 条款 5 的 1 req/s 起步观察） |
| 3 | 新浪 roll 频道（lid=2516/2517，sina-roll-hk/us 两配置源） | `feed.mix.sina.com.cn` | HTTP 404（新浪错误提示页） | 子域未发布 robots.txt；主域 `www.sina.com.cn/robots.txt` 实测 `User-agent: *` + `Allow: /`（通配放行，无 Disallow） | **达标** |

**汇总**：三宿主（承载四个新源：em-f10 建池通道 + em-search-hk/us + sina-roll-hk/us）全部通过 robots 三验，**无源需撤下，变更控制零触发**；REQ 拍板一的美股条件 Must 维持「证实可得 → Must 落地」结论不变（§1/§3/§4 四件套证据链 + 本节 robots 面）。

---

*Spike-E 实测会话：2026-09-29；证据文件：本机 /tmp（em_hk_all.json / em_us_all.json / em_hkf10.json / em_usf10.json / tx_rhk.txt / tx_us.txt / tx_mix.txt / sina_hkq.txt / sina_usq.txt / em_search*.json / sina_hknews.json / sina_usnews.json / sbx.txt）；本报告为 M29 技术方案数据面输入，方案与任务拆解另立文档。*
