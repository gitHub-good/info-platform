package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.AdapterType;
import java.util.List;

/**
 * 预置源目录（M13 T100，方案 §4.6/§3.4）：预置源<b>单一事实源</b>（对齐 SourceProviders/DataSourceDefaults 惯例）。
 *
 * <p>M13 种子三源覆盖全部三类适配通道（rss / json_api / preset）；M14 T110 批次一一级 JSON 四源 + T111/T112 官方与报纸 HTML
 * 六源入目录（累计 13 预置，REQ 累计源数口径）； M14+ 每批新增源 = 本目录加行， {@code InfoSourceSeeder} seed-if-absent
 * 补种（存量行不覆盖，DB 为权威）。目录即合规白名单：robots 禁抓/需签名/登录墙的源根本不入目录（普查 §6 红线案例集）。
 *
 * <p>合规预检留档（T106 复核）：MarketWatch robots 403 → RFC 9309 无 robots 即无限制（落地复核注记）；金十/新浪 7×24 无 robots。
 * M14 T110 复核：np-weblist/news.10jqka/cache.thepaper robots 404、datacenter-web robots 为 JSON 错误页 →
 * 均按无限制。M14 T111/T112 复核（2026-09-25 实测，ADR-0044）：ndrc robots 403（WAF 拒读）→ 无限制留档；csrc robots 302 跳
 * HTML → 无 robots 文件；stats/stcn robots 404 → 无限制；yicai 禁 /api/、/search（/news/ 列表不涉）； 21jingji 通配
 * Allow 但显式禁 AI 训练爬虫（聚合展示不涉，M15 管道前复核条款）。
 */
public final class InfoSourceCatalog {

    private InfoSourceCatalog() {}

    /** 预置源条目（configJson 与 {@code info_source.config} 线格式一致，结构见方案 §4.3）。 */
    public record PresetEntry(
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            String configJson,
            int intervalMinutes) {}

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
                STATS_RELEASE);
    }
}
