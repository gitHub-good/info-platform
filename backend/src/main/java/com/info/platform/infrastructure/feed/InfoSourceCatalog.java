package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.AdapterType;
import java.util.List;

/**
 * 预置源目录（M13 T100，方案 §4.6/§3.4）：预置源<b>单一事实源</b>（对齐 SourceProviders/DataSourceDefaults 惯例）。
 *
 * <p>M13 种子三源覆盖全部三类适配通道（rss / json_api / preset）；M14 批次一十源 + M17 批次二九源（累计 22 预置）；M18 T150~T152
 * 批次三九源（门户 3/媒体 3/纵深/竞争/条件席，T153 预检终局 ADR-0055）+ 通用 RSS 示例包 ×2（<b>默认停用</b>播种不计 30 口径）——目录 33 行、默认启用
 * 31（Nasdaq 行留档待编排者软删后现役 30，精确命中蓝图达标线）；M14+ 每批新增源 = 本目录加行， {@code InfoSourceSeeder} seed-if-absent
 * 补种（存量行不覆盖，DB 为权威）。目录即合规白名单：robots 禁抓/需签名/登录墙的源根本不入目录（普查 §6 红线案例集；腾讯端点 WAF JS
 * 盾即预检永久关闭不入目录，ADR-0055）。
 *
 * <p>合规预检留档（T106 复核）：MarketWatch robots 403 → RFC 9309 无 robots 即无限制（落地复核注记）；金十/新浪 7×24 无 robots。
 * M14 T110 复核：np-weblist/news.10jqka/cache.thepaper robots 404、datacenter-web robots 为 JSON 错误页 →
 * 均按无限制。M14 T111/T112 复核（2026-09-25 实测，ADR-0044）：ndrc robots 403（WAF 拒读）→ 无限制留档；csrc robots 302 跳
 * HTML → 无 robots 文件；stats/stcn robots 404 → 无限制；yicai 禁 /api/、/search（/news/ 列表不涉）； 21jingji 通配
 * Allow 但显式禁 AI 训练爬虫（聚合展示不涉，M15 管道前复核条款）。
 */
public final class InfoSourceCatalog {

    private InfoSourceCatalog() {}

    /**
     * 预置源条目（configJson 与 {@code info_source.config} 线格式一致，结构见方案 §4.3）。
     *
     * @param defaultEnabled 种子默认启停（M18 示例包 ×2 默认停用播种不计 30 口径，REQ-20260926-15 条目 6；其余缺省启用）
     */
    public record PresetEntry(
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            String configJson,
            int intervalMinutes,
            boolean defaultEnabled) {

        /** 兼容构造（M13~M17 既有调用面：未声明即默认启用）。 */
        public PresetEntry(
                String sourceCode,
                String name,
                String category,
                AdapterType adapterType,
                String adapterRef,
                String endpoint,
                String configJson,
                int intervalMinutes) {
            this(
                    sourceCode,
                    name,
                    category,
                    adapterType,
                    adapterRef,
                    endpoint,
                    configJson,
                    intervalMinutes,
                    true);
        }
    }

    /** MarketWatch Top Stories：标准 RSS 2.0（普查 🟢 200/2576B 实测），TIME 游标，30min。 */
    private static final PresetEntry MARKETWATCH =
            new PresetEntry(
                    "mw_topstories",
                    "MarketWatch·头条",
                    "国际",
                    AdapterType.RSS,
                    null,
                    "https://feeds.content.dowjones.io/public/rss/mw_topstories",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30);

    /**
     * 金十数据快讯：JS 包装 JSON（{@code var newest=[...];}），ID 数值游标，5min。
     *
     * <p>字段口径经 2026-09-25 真实外呼复核（ADR-0042）：快讯正文嵌于 {@code data} 子对象（{@code data.title} 常空、{@code
     * data.content} 为正文——普查样本的顶层 {@code title/important_title} 不存在）；映射走点分导航 + 引擎标题回落（title 空以
     * summary 补位），中文快讯出题、英文快讯题文分立。
     */
    private static final PresetEntry JIN10_FLASH =
            new PresetEntry(
                    "jin10_flash",
                    "金十数据·快讯",
                    "快讯",
                    AdapterType.JSON_API,
                    null,
                    "https://www.jin10.com/flash_newest.js",
                    """
                    {"listPath":"","stripPrefix":"var newest=","stripSuffix":";",\
                    "itemMapping":[\
                    {"source":"id","target":"externalId","transform":"to_string"},\
                    {"source":"time","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"data.title","target":"title","transform":"to_string"},\
                    {"source":"data.content","target":"summary","transform":"strip_html"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://www.jin10.com"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    5);

    /**
     * 新浪财经 7×24：预置 adapter（richtext 清洗 + id 数值游标），5min；M14「升级新浪源」的前置通道。
     *
     * <p>字段口径经 2026-09-25 真实外呼复核（ADR-0042）：{@code rich_text}（普查记 richtext）/ {@code create_time}
     * 墙钟（普查记 ctime epoch）/{@code docurl}；解析全在 {@code SinaZhiboAdapter} 代码内。
     */
    private static final PresetEntry SINA_ZHIBO =
            new PresetEntry(
                    "sina_zhibo_7x24",
                    "新浪财经·7×24",
                    "快讯",
                    AdapterType.PRESET,
                    "sinaZhiboAdapter",
                    "https://zhibo.sina.com.cn/api/zhibo/feed?zhibo_id=152",
                    """
                    {"cursorType":"ID","cursorField":"externalId"}""",
                    5);

    /**
     * 东财 7×24 快讯（M14 T110，REQ-20260925-11 拍板一 #1）：np-weblist 宿主（与 push2 WAF 前科宿主不同， 预检实测 2026-09-25
     * 通过）。
     *
     * <p>实测口径：端点必带 {@code client=web&req_trace}（缺参 400 提示参数名，与普查样本比有参数演进）； 条目数组 {@code
     * data.fastNewsList[]}，{@code code} 日期前缀数值游标、{@code showTime} 墙钟、title/summary；条目无直链字段（url 留空，
     * externalId 兜底过滤线）。robots：np-weblist 404 → 按 RFC 9309 无限制（普查「无 robots」复核一致）。频控 2min（REQ 锁定清单
     * 快讯类下限）。
     */
    private static final PresetEntry EM_FASTNEWS =
            new PresetEntry(
                    "em_fastnews_7x24",
                    "东方财富·7×24快讯",
                    "快讯",
                    AdapterType.JSON_API,
                    null,
                    "https://np-weblist.eastmoney.com/comm/web/getFastNewsList"
                            + "?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=20&req_trace=1",
                    """
                    {"listPath":"data.fastNewsList",\
                    "itemMapping":[\
                    {"source":"code","target":"externalId","transform":"to_string"},\
                    {"source":"showTime","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"title","target":"title","transform":"to_string"},\
                    {"source":"summary","target":"summary","transform":"to_string"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://kuaixun.eastmoney.com/"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    2);

    /**
     * 同花顺财经快讯（M14 T110，拍板一 #2）：tapp push/stock JSON 通道。
     *
     * <p>实测口径：{@code data.list[]}，{@code id} 单调数值游标、{@code ctime} Unix 秒、title/digest/url 直链齐全； 响应
     * {@code application/json} 无 charset——实测 UTF-8（普查记 GBK 系 today_list <b>HTML</b> 通道，JSON 通道不涉及；
     * Spring StringHttpMessageConverter 对无 charset 的 application/json 按 UTF-8 解码，有单测锁定）。robots：
     * news.10jqka.com.cn 404 → 无限制。频控 5min（试点三源同频惯例）。
     */
    private static final PresetEntry THS_PUSH =
            new PresetEntry(
                    "ths_push_stock",
                    "同花顺·快讯",
                    "快讯",
                    AdapterType.JSON_API,
                    null,
                    "https://news.10jqka.com.cn/tapp/news/push/stock/",
                    """
                    {"listPath":"data.list",\
                    "itemMapping":[\
                    {"source":"id","target":"externalId","transform":"to_string"},\
                    {"source":"ctime","target":"publishedAt","transform":"epoch_seconds_to_iso"},\
                    {"source":"title","target":"title","transform":"to_string"},\
                    {"source":"digest","target":"summary","transform":"to_string"},\
                    {"source":"url","target":"url","transform":"to_string"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://news.10jqka.com.cn/"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    5);

    /**
     * 澎湃要闻（M14 T110，拍板一 #3）：cache.thepaper.cn 公开 JSON。
     *
     * <p>频道密度复核（PM 待澄清 ② 结论）：rightSidebar 财经专属列表仅 {@code financialInformationNews} 2 条（~5%），
     * 同平台频道端点补位尝试 nodeCont/254、nodeCont/25438、wwwIndex 均 404（2026-09-25 实测）——无可用纯财经频道 JSON 端点，按 REQ
     * 品类定位「要闻·含财经」接 {@code data.hotNews}（20 条/轮，分钟级），密度不足留 PM/架构裁定。
     *
     * <p>实测口径：条目无直链字段 → {@code urlTemplate} 合成（澎湃详情页公开规律 {@code newsDetail_forward_{contId}}，URL
     * 样式正确性由 T117 验收复核）；{@code pubTimeLong} Unix <b>毫秒</b> （epoch_millis_to_iso，M14 引擎扩展）；{@code
     * contId} 数值游标。robots：cache.thepaper.cn 404 → 无限制。频控 10min（REQ 5~15min 频段中值，页面级要闻源礼貌抓取）。
     */
    private static final PresetEntry THEPAPER_HOTNEWS =
            new PresetEntry(
                    "thepaper_hotnews",
                    "澎湃新闻·要闻",
                    "媒体",
                    AdapterType.JSON_API,
                    null,
                    "https://cache.thepaper.cn/contentapi/wwwIndex/rightSidebar",
                    """
                    {"listPath":"data.hotNews",\
                    "itemMapping":[\
                    {"source":"contId","target":"externalId","transform":"to_string"},\
                    {"source":"name","target":"title","transform":"to_string"},\
                    {"source":"pubTimeLong","target":"publishedAt","transform":"epoch_millis_to_iso"}],\
                    "urlTemplate":"https://www.thepaper.cn/newsDetail_forward_{externalId}",\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://www.thepaper.cn/"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    10);

    /**
     * 东财宏观指标（M14 T110，拍板一 #10，Should）：datacenter reportName 字典预置适配（{@code
     * eastmoneyMacroAdapter}），序列→条目映射与 cursorType=NONE 裁量见适配器类注释。
     *
     * <p>robots：datacenter-web robots 请求返回 JSON 错误页（无 robots 文件）→ 无限制。频控 60min（REQ 锁定清单）。
     */
    private static final PresetEntry EM_MACRO_INDICATORS =
            new PresetEntry(
                    "em_macro_indicators",
                    "东方财富·宏观指标",
                    "宏观",
                    AdapterType.PRESET,
                    "eastmoneyMacroAdapter",
                    "https://datacenter-web.eastmoney.com/api/data/v1/get",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 发改委政策发布（M14 T111，拍板一 #4）：预置 adapter（{@code ndrcPolicyAdapter}），xxgk/zcfb/fzggwl 列表页 HTML
     * 解析口径与解读条目保留见适配器类注释（2026-09-25 预检实测，ADR-0044）。
     *
     * <p>robots：ndrc.gov.cn robots 本身 403（WAF 拒读）→ 按 RFC 9309「无 robots 文件 = 无限制」口径处理并留档（REQ 场景 5
     * 必检项落地复核）。频控 60min（REQ 官方频段 30~60 上限，日级源礼貌抓取）。
     */
    private static final PresetEntry NDRC_POLICY =
            new PresetEntry(
                    "ndrc_policy",
                    "国家发展改革委·政策发布",
                    "政策",
                    AdapterType.PRESET,
                    "ndrcPolicyAdapter",
                    "https://www.ndrc.gov.cn/xxgk/zcfb/fzggwl/",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 证监会要闻（M14 T111，拍板一 #5）：预置 adapter（{@code csrcNewsAdapter}）。端点为<b>首页要闻 tab 块</b>——普查
     * common_list.shtml 实测冻结于 2021-12（生成时间戳与条目均停更）、现行 common_xq_list.shtml 服务端渲染为空列表，
     * 首页块为唯一服务端渲染新鲜窗口（现行结构对照结论见 ADR-0044）。
     *
     * <p>robots：302 跳 HTML 页（非 robots 文件）→ 按「无 robots 文件 = 无限制」处理并留档。频控 60min（首页 221KB 大页礼貌抓取）。
     */
    private static final PresetEntry CSRC_NEWS =
            new PresetEntry(
                    "csrc_news",
                    "中国证监会·要闻",
                    "政策",
                    AdapterType.PRESET,
                    "csrcNewsAdapter",
                    "https://www.csrc.gov.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 统计局最新发布（M14 T111，拍板一 #9，Should）：预置 adapter（{@code statsGovReleaseAdapter}），sj/zxfb 列表页
     * 三响应式锚点取舍与日期墙钟口径见适配器类注释（2026-09-25 预检实测，ADR-0044）。
     *
     * <p>robots：stats.gov.cn robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」。频控 60min（REQ 锁定清单）。
     */
    private static final PresetEntry STATS_RELEASE =
            new PresetEntry(
                    "stats_release",
                    "国家统计局·最新发布",
                    "宏观",
                    AdapterType.PRESET,
                    "statsGovReleaseAdapter",
                    "https://www.stats.gov.cn/sj/zxfb/",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 证券时报要闻（M14 T112，拍板一 #6，Should）：预置 adapter（{@code stcnNewsAdapter}）。端点为现行栏目路径 {@code
     * /article/list/yw.html}（旧快讯路径与 RSS 均 404，由首页 data-items 对照得出，ADR-0044）；首屏 id 相邻 乱序对 →
     * cursorType=NONE（裁量见适配器类注释）。
     *
     * <p>robots：stcn.com robots 为自定义 404 页 → 按「无 robots 文件 = 无限制」。频控 30min（REQ 报纸频段上限）。
     */
    private static final PresetEntry STCN_NEWS =
            new PresetEntry(
                    "stcn_news",
                    "证券时报·要闻",
                    "媒体",
                    AdapterType.PRESET,
                    "stcnNewsAdapter",
                    "https://www.stcn.com/article/list/yw.html",
                    """
                    {"cursorType":"NONE"}""",
                    30);

    /**
     * 第一财经资讯（M14 T112，拍板一 #7，Should）：预置 adapter（{@code yicaiNewsAdapter}），yicai.com/news 主列表 （旧快讯
     * API 已死），相对时间折算与尾部 id 乱序 → cursorType=NONE 见适配器类注释（2026-09-25 预检实测， ADR-0044）。
     *
     * <p>robots：200 仅禁 {@code /api/}、{@code /search}——走 {@code /news/} HTML 列表不受影响。频控 20min（REQ 报纸
     * 频段中值，快讯密度较高的列表源）。
     */
    private static final PresetEntry YICAI_NEWS =
            new PresetEntry(
                    "yicai_news",
                    "第一财经·资讯",
                    "媒体",
                    AdapterType.PRESET,
                    "yicaiNewsAdapter",
                    "https://www.yicai.com/news/",
                    """
                    {"cursorType":"NONE"}""",
                    20);

    /**
     * 21 财经金融频道（M14 T112，拍板一 #8，Should）：预置 adapter（{@code jingji21FinanceAdapter}），channel/finance
     * 列表（编辑排序非时间序 → cursorType=NONE，裁量见适配器类注释，ADR-0044）。
     *
     * <p>robots：通配 {@code Allow:/}（仅禁 {@code /sitemap/generate}）但显式禁止 GPTBot 等 AI 训练类爬虫——聚合展示
     * 用途不受影响；<b>M15 AI 管道启动前复核条款，有疑虑则该源内容不入深度分析</b>（REQ 非功能条款注记）。频控 30min。
     */
    private static final PresetEntry JINGJI21_FINANCE =
            new PresetEntry(
                    "jingji21_finance",
                    "21财经·金融",
                    "媒体",
                    AdapterType.PRESET,
                    "jingji21FinanceAdapter",
                    "https://www.21jingji.com/channel/finance/",
                    """
                    {"cursorType":"NONE","aiExclusion":"L2"}""",
                    30);

    /**
     * 工信部政策文件（M17 T140，REQ-20260926-14 拍板一 #1）：预置 adapter（{@code miitPolicyAdapter}）。REQ 记 {@code
     * /zwgk/zcwj/} 路径实测为 JS 跳转壳（新站列表客户端渲染），现行数据端点为站点公开检索 API {@code
     * search-front-server/api/search/info}（参数与字段口径见适配器类注释，2026-09-22 预检实测，ADR-0053）。
     *
     * <p>robots：miit.gov.cn robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」；历史 WAF 风险（普查预告）实测未触发，
     * 单源退避兜底。频控 60min（REQ 官方频段上限，日级源礼貌抓取）。
     */
    private static final PresetEntry MIIT_POLICY =
            new PresetEntry(
                    "miit_policy",
                    "工业和信息化部·政策文件",
                    "政策",
                    AdapterType.PRESET,
                    "miitPolicyAdapter",
                    "https://www.miit.gov.cn/search-front-server/api/search/info"
                            + "?websiteid=110000000000000&searchid=51&tpl=14&category=51"
                            + "&scope=basic&q=&pg=10&cateid=&pos=&_cus_eq_typename="
                            + "&_cus_eq_publishgroupname=&_cus_eq_themename=&begin=&end="
                            + "&dateField=deploytime&selectFields=title,deploytime,url"
                            + "&group=distinct&level=6&sortFields=&p=1",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 财政部政策发布（M17 T140，拍板一 #2）：预置 adapter（{@code mofPolicyAdapter}），szs.mof.gov.cn/zhengcefabu 列表页
     * HTML 解析口径与 URL 尾号 externalId 见适配器类注释（2026-09-22 预检实测，ADR-0053）。
     *
     * <p>robots：szs.mof.gov.cn robots 302 跳主站 404 页（非 robots 文件）→ 按「无 robots 文件 = 无限制」留档。 频控
     * 60min（REQ 官方频段上限）。
     */
    private static final PresetEntry MOF_POLICY =
            new PresetEntry(
                    "mof_policy",
                    "财政部·政策发布",
                    "政策",
                    AdapterType.PRESET,
                    "mofPolicyAdapter",
                    "https://szs.mof.gov.cn/zhengcefabu/",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * 东财要闻频道（M17 T140，拍板一 #3）：np-weblist 宿主 getNewsByColumns（与 7×24 快讯同宿主，频道纵深形态）。
     *
     * <p>实测口径（2026-09-22 预检）：端点必带 {@code client=web&req_trace}；条目数组 {@code data.list[]}，{@code
     * code} 日期前缀数值游标、{@code showTime} {@code yyyy-MM-dd HH:mm:ss} 墙钟、title/summary 齐全且<b>自带直链字段
     * url</b>（与 7×24 快讯通道的差异点）。robots：np-weblist 404 → 无限制（M14 T110 已档，同宿主复核一致）。频控 15min（REQ
     * 要闻频段下限，高更新密度）。
     */
    private static final PresetEntry EM_HEADLINES =
            new PresetEntry(
                    "em_headlines",
                    "东方财富·要闻",
                    "媒体",
                    AdapterType.JSON_API,
                    null,
                    "https://np-weblist.eastmoney.com/comm/web/getNewsByColumns"
                            + "?client=web&biz=web_news&column=350&order=1&needInteractData=0"
                            + "&page_index=1&page_size=20&req_trace=1",
                    """
                    {"listPath":"data.list",\
                    "itemMapping":[\
                    {"source":"code","target":"externalId","transform":"to_string"},\
                    {"source":"showTime","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"title","target":"title","transform":"to_string"},\
                    {"source":"summary","target":"summary","transform":"to_string"},\
                    {"source":"url","target":"url","transform":"to_string"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://finance.eastmoney.com/"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    15);

    /**
     * 界面新闻财经（M17 T141，拍板一 #4）：预置 adapter（{@code jiemianFinanceAdapter}）。REQ 记 {@code lists/2} 实测为
     * 商业频道，财经频道预检修正为 {@code lists/800}——卡片结构与 MM/dd HH:mm 墙钟口径见适配器类注释（2026-09-22 预检实测，ADR-0053）。
     *
     * <p>robots：jiemian.com robots 200 仅禁 {@code Con} 前缀路径（列表页不涉，REQ 注记复核一致）。频控 30min（REQ 纸媒频段上限）。
     */
    private static final PresetEntry JIEMIAN_FINANCE =
            new PresetEntry(
                    "jiemian_finance",
                    "界面新闻·财经",
                    "媒体",
                    AdapterType.PRESET,
                    "jiemianFinanceAdapter",
                    "https://www.jiemian.com/lists/800.html",
                    """
                    {"cursorType":"NONE"}""",
                    30);

    /**
     * 上证报要闻（M17 T141，拍板一 #5，澎湃密度补位①）：预置 adapter（{@code cnstockNewsAdapter}）。旧 news 子域 302 进新站、
     * 栏目列表客户端渲染——现行窗口为首页要闻卡块（沿证监会首页块先例），CSS-module 哈希类锚定与相对/MM-dd 混排墙钟见适配器类注释 （2026-09-22
     * 预检实测，ADR-0053）。
     *
     * <p>robots：cnstock.com robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」。频控 30min（首页大页礼貌抓取）。
     */
    private static final PresetEntry CNSTOCK_NEWS =
            new PresetEntry(
                    "cnstock_news",
                    "上海证券报·要闻",
                    "媒体",
                    AdapterType.PRESET,
                    "cnstockNewsAdapter",
                    "https://www.cnstock.com/",
                    """
                    {"cursorType":"NONE"}""",
                    30);

    /**
     * 中证网要闻（M17 T141，拍板一 #6，Should，澎湃密度补位②）：预置 adapter（{@code csNewsAdapter}）。栏目列表页均 JS
     * 模板渲染——现行窗口为首页中证快讯 7×24 块（em 时分 + URL 内嵌日期拼合墙钟），口径见适配器类注释（2026-09-22 预检实测， ADR-0053）。
     *
     * <p>robots：cs.com.cn robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」。频控
     * 30min。同质对冲：与证券时报同稿由跨源指纹去重拦截。
     */
    private static final PresetEntry CS_NEWS =
            new PresetEntry(
                    "cs_news",
                    "中证网·要闻",
                    "媒体",
                    AdapterType.PRESET,
                    "csNewsAdapter",
                    "https://www.cs.com.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    30);

    /**
     * 人民网经济频道（M17 T142，拍板一 #7，Should）：预置 adapter（{@code
     * peopleFinanceAdapter}），finance.people.com.cn 首页 /n1/ 锚点与 URL 内嵌日期墙钟口径见适配器类注释（2026-09-22
     * 预检实测，ADR-0053）。
     *
     * <p>robots：www.people.com.cn robots 200（全放行 + Crawl-delay 120s）→ 频控 60min = 30 倍裕量（REQ ≥15
     * 倍条款）。
     */
    private static final PresetEntry PEOPLE_FINANCE =
            new PresetEntry(
                    "people_finance",
                    "人民网·经济",
                    "媒体",
                    AdapterType.PRESET,
                    "peopleFinanceAdapter",
                    "http://finance.people.com.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    60);

    /**
     * Nasdaq 市场 RSS（M17 T142，拍板一 #8，国际 RSS 源 1，三验锁定 2026-09-22）：标准 RSS 2.0（15 条/轮），robots 200 通配放行仅
     * {@code Crawl-delay: 30}（/feed/ 不在 Disallow 列表）→ 频控 30min = 60 倍裕量； 境内可达 200 实证。TIME 游标沿
     * MarketWatch 先例（T106）。
     */
    private static final PresetEntry NASDAQ_MARKETS =
            new PresetEntry(
                    "nasdaq_markets",
                    "Nasdaq·市场",
                    "国际",
                    AdapterType.RSS,
                    null,
                    "https://www.nasdaq.com/feed/rssoutbound?category=markets",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30);

    /**
     * 华尔街日报市场 RSS（M17 T142，拍板一 #9，国际 RSS 源 2，三验锁定 2026-09-22）：标准 RSS 2.0（61 条/轮，入库层 maxItems 缺省 50
     * 截断），feeds.content.dowjones.io 宿主与 MarketWatch 同族（robots 403 → RFC 9309 无 robots 即无限制， T106
     * 复核留档）；境内可达 200 实证（首测连接重置、重试即 200——瞬时抖动非封禁）。TIME 游标沿 MarketWatch 先例。
     */
    private static final PresetEntry WSJ_MARKETS =
            new PresetEntry(
                    "wsj_markets",
                    "华尔街日报·市场",
                    "国际",
                    AdapterType.RSS,
                    null,
                    "https://feeds.content.dowjones.io/public/rss/RSSMarketsMain",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30);

    /**
     * 网易财经首页（M18 T150，REQ-20260926-15 拍板一 #1）：预置 adapter（{@code neteaseMoneyAdapter}），首页
     * /dy/article/ 锚点 与 「无显式时间 → 发布时间回落摄取时刻」口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：money.163.com robots 200 全放行。频控 15min（REQ 门户频段下限）。
     */
    private static final PresetEntry NETEASE_MONEY =
            new PresetEntry(
                    "netease_money",
                    "网易财经·要闻",
                    "门户",
                    AdapterType.PRESET,
                    "neteaseMoneyAdapter",
                    "https://money.163.com/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 凤凰财经首页（M18 T150，拍板一 #2）：预置 adapter（{@code ifengFinanceAdapter}），首页 /c/{base62} 短链锚点口径见适配器类注释
     * （旧列表 api 已弃用走 HTML，2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：ifeng robots 200 全放行（附 llms.txt 指引，聚合展示不涉）。频控 15min。
     */
    private static final PresetEntry IFENG_FINANCE =
            new PresetEntry(
                    "ifeng_finance",
                    "凤凰财经·资讯",
                    "门户",
                    AdapterType.PRESET,
                    "ifengFinanceAdapter",
                    "https://finance.ifeng.com/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 金融界首页（M18 T150，拍板一 #6）：预置 adapter（{@code jrjHomeAdapter}）。REQ 记「列表路径」实测 404/静态精选滞后 12 天——现行
     * 窗口为 <b>www 根首页</b>（81 条带题锚点，URL 内嵌 ddHHmm 分钟墙钟），口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p><b>合规注记（REQ 场景 6）</b>：robots 仅禁搜索/翻页参数路径——仅抓 www 根无参数路径。频控 15min。
     */
    private static final PresetEntry JRJ_HOME =
            new PresetEntry(
                    "jrj_home",
                    "金融界·要闻",
                    "门户",
                    AdapterType.PRESET,
                    "jrjHomeAdapter",
                    "https://www.jrj.com.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 每日经济新闻首页（M18 T151，拍板一 #3）：预置 adapter（{@code nbdNewsAdapter}），首页
     * /articles/{yyyy-MM-dd}/{id}.html 锚点 与 URL 内嵌日粒度墙钟口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：nbd robots 200 仅禁 js/css 与查询参数路径（文章列表不涉）。频控 15min（REQ 媒体频段下限）。
     */
    private static final PresetEntry NBD_NEWS =
            new PresetEntry(
                    "nbd_news",
                    "每日经济新闻·要闻",
                    "媒体",
                    AdapterType.PRESET,
                    "nbdNewsAdapter",
                    "https://www.nbd.com.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 新华财经首页快讯块（M18 T151，拍板一 #4）：预置 adapter（{@code cnfinFlashAdapter}），首页「新华快讯」块（URL 日期 + 锚点 HH:mm
     * 前缀拼合分钟精度墙钟）口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：cnfin robots（https 跟随后）200 全放行。频控 15min。
     */
    private static final PresetEntry CNFIN_FLASH =
            new PresetEntry(
                    "cnfin_flash",
                    "新华财经·快讯",
                    "媒体",
                    AdapterType.PRESET,
                    "cnfinFlashAdapter",
                    "https://www.cnfin.com/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 央视财经经济资讯（M18 T151，拍板一 #5）：json_api 通道走页面自身 jsonp 数据端点（jingji 首页客户端渲染壳，沿工信部检索 API 先例
     * ADR-0053）：{@code economy_zixun({data:{list:[...]}})} 包装剥离 + {@code focus_date} 墙钟（2026-09-26
     * 预检实测，ADR-0055）。
     *
     * <p>id 为 ARTI 段（非数值）→ cursorType=NONE；条目 80 条/轮（入库层 maxItems 缺省 50 截断）。robots：news.cctv.com
     * robots 404 错误页 → 无 robots 文件。频控 15min。
     */
    private static final PresetEntry CCTV_ECONOMY =
            new PresetEntry(
                    "cctv_economy",
                    "央视财经·经济",
                    "媒体",
                    AdapterType.JSON_API,
                    null,
                    "https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/economy_zixun_1.jsonp",
                    """
                    {"stripPrefix":"economy_zixun(","stripSuffix":")",\
                    "listPath":"data.list",\
                    "itemMapping":[\
                    {"source":"id","target":"externalId","transform":"to_string"},\
                    {"source":"focus_date","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"title","target":"title","transform":"to_string"},\
                    {"source":"url","target":"url","transform":"to_string"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://jingji.cctv.com/"},\
                    "cursorType":"NONE"}""",
                    15);

    /**
     * 东财财经频道（M18 T152 纵深席，拍板一 #7 预检锁定）：np-weblist getNewsByColumns <b>column=352</b>（与在役
     * em_headlines column=350 同宿主同端点同构——四期实证基础设施复用，ADR-0055）。
     *
     * <p>实测口径（2026-09-26 预检）：{@code data.list[]}，{@code code} 日期前缀数值游标、{@code showTime}
     * 墙钟、title/summary/url 直链齐全。robots：np-weblist 404 → 无限制（M14 T110 已档）。频控 15min（沿 em_headlines）。
     */
    private static final PresetEntry EM_FINANCE_COLUMN =
            new PresetEntry(
                    "em_finance_column",
                    "东方财富·财经",
                    "媒体",
                    AdapterType.JSON_API,
                    null,
                    "https://np-weblist.eastmoney.com/comm/web/getNewsByColumns"
                            + "?client=web&biz=web_news&column=352&order=1&needInteractData=0"
                            + "&page_index=1&page_size=20&req_trace=1",
                    """
                    {"listPath":"data.list",\
                    "itemMapping":[\
                    {"source":"code","target":"externalId","transform":"to_string"},\
                    {"source":"showTime","target":"publishedAt","transform":"to_iso_datetime"},\
                    {"source":"title","target":"title","transform":"to_string"},\
                    {"source":"summary","target":"summary","transform":"to_string"},\
                    {"source":"url","target":"url","transform":"to_string"}],\
                    "headers":{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36","Referer":"https://finance.eastmoney.com/"},\
                    "cursorType":"ID","cursorField":"externalId"}""",
                    15);

    /**
     * 格隆汇快讯（M18 T152 竞争席，拍板二四验全过）：预置 adapter（{@code gelonghuiLiveAdapter}），/live/ 页 Nuxt payload
     * IIFE 参数绑定解析（createTimestamp 尾参表解 epoch 秒 + route 直链还原），口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：gelonghui robots 404 → 无限制。频控 15min（REQ 竞争席频段下限）。跨时段结构稳定性由 7 天观察期承载。
     */
    private static final PresetEntry GELONGHUI_LIVE =
            new PresetEntry(
                    "gelonghui_live",
                    "格隆汇·快讯",
                    "快讯",
                    AdapterType.PRESET,
                    "gelonghuiLiveAdapter",
                    "https://www.gelonghui.com/live/",
                    """
                    {"cursorType":"NONE"}""",
                    15);

    /**
     * 中国经济网滚动新闻（M18 T152 条件席〔第 9 席〕，Nasdaq 终局停用触发、补位序首位和讯瑞数盾 FAIL 顶替）：预置 adapter（{@code
     * ceNewsAdapter}），首页 gdxw 滚动新闻块与 URL 内嵌 t 日期日粒度墙钟口径见适配器类注释（2026-09-26 预检实测，ADR-0055）。
     *
     * <p>robots：ce.cn robots 200 {@code Allow:/} 仅禁 /guanggao/（REQ 记 301 循环已落地失效）。频控
     * 30min（首页大页礼貌抓取）。
     */
    private static final PresetEntry CE_NEWS =
            new PresetEntry(
                    "ce_news",
                    "中国经济网·滚动",
                    "媒体",
                    AdapterType.PRESET,
                    "ceNewsAdapter",
                    "http://www.ce.cn/",
                    """
                    {"cursorType":"NONE"}""",
                    30);

    /**
     * 通用 RSS 示例包 1（M18 T152，REQ-20260926-15 条目 6）：WSJ WorldNews（feeds.content.dowjones.io，200/72
     * 条可达性实测 2026-09-26 ——M17 ADR-0053 曾以时政密度未入选正席，此处仅作<b>默认停用模板</b>复用结构实证）。
     *
     * <p><b>默认停用播种，不计 30 口径</b>（REQ 拍板一口径：稳定源 = 默认启用且周成功率 ≥95%）；接入指引：源管理页启用 → 按 通用 RSS
     * 源语义调间隔/启停（M13 裁决 1）。robots：dowjones feeds 宿主 403 → 无 robots（MW T106 同族留档）。
     */
    private static final PresetEntry EXAMPLE_WSJ_WORLD =
            new PresetEntry(
                    "example_wsj_world",
                    "示例·华尔街日报·国际",
                    "国际",
                    AdapterType.RSS,
                    null,
                    "https://feeds.content.dowjones.io/public/rss/RSSWorldNews",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30,
                    false);

    /**
     * 通用 RSS 示例包 2（M18 T152，REQ-20260926-15 条目 6）：IT之家（ithome.com/rss，200/60 条境内可达异宿主 2026-09-26
     * 实测—— 演示通用 RSS 通道非财经专属宿主同样可接）。默认停用播种不计 30 口径，接入指引同示例包 1。
     */
    private static final PresetEntry EXAMPLE_ITHOME =
            new PresetEntry(
                    "example_ithome",
                    "示例·IT之家·科技",
                    "科技",
                    AdapterType.RSS,
                    null,
                    "https://www.ithome.com/rss/",
                    """
                    {"cursorType":"TIME","cursorField":"publishedAt"}""",
                    30,
                    false);

    /** 预置源清单（种子顺序即展示顺序；source_code 唯一由单测守护）。 */
    public static List<PresetEntry> presets() {
        return List.of(
                MARKETWATCH,
                JIN10_FLASH,
                SINA_ZHIBO,
                EM_FASTNEWS,
                THS_PUSH,
                THEPAPER_HOTNEWS,
                EM_MACRO_INDICATORS,
                NDRC_POLICY,
                CSRC_NEWS,
                STATS_RELEASE,
                STCN_NEWS,
                YICAI_NEWS,
                JINGJI21_FINANCE,
                MIIT_POLICY,
                MOF_POLICY,
                EM_HEADLINES,
                JIEMIAN_FINANCE,
                CNSTOCK_NEWS,
                CS_NEWS,
                PEOPLE_FINANCE,
                NASDAQ_MARKETS,
                WSJ_MARKETS,
                NETEASE_MONEY,
                IFENG_FINANCE,
                JRJ_HOME,
                NBD_NEWS,
                CNFIN_FLASH,
                CCTV_ECONOMY,
                EM_FINANCE_COLUMN,
                GELONGHUI_LIVE,
                CE_NEWS,
                EXAMPLE_WSJ_WORLD,
                EXAMPLE_ITHOME);
    }
}
