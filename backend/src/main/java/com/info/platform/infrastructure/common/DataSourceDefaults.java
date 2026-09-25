package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
import com.info.platform.domain.aggregation.SourceProviders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源配置权威缺省值（T36 从各 adapter/client 硬编码提取；ADR-0032 起 application.yml {@code adapter:}
 * 段原样迁入，本类即数据源配置<b>单一事实源</b>——对齐 ADR-0020 {@code LlmDefaults} 先例，页面配置（runtime_config DB）为唯一真相）。
 *
 * <p>三处用途共用同一份常量，保证不漂移：① {@code DataSourceRuntimeConfigSeeder} 种子值（首启导入 runtime_config，页面改过即以 DB
 * 为权威）； ② {@code ConfigCenter#dataSource} 键缺失/解析失败的回落； ③ 纯构造单测 （无 Spring 上下文）的 {@code
 * AbstractSourceAdapter} 弹性缺省与各 HTTP client 构造期回落。 提取对照（源码硬编码/yml → 配置键）见 T36/ADR-0032 交付说明：
 *
 * <ul>
 *   <li>超时：QuoteSourceAdapter 1500ms；Valuation/Finance/Announce/News/Policy 各 2s；Event 500ms（原各类
 *       TIMEOUT 常量）
 *   <li>重试：全部源 0（原全部 {@code ResilienceSpec.noRetry}）
 *   <li>缓存 TTL：SourceCache.specFor —— 行情 5s / 财务·估值 1h / 公告 5min / 新闻 2min / 政策 10min / 事件 30s
 *   <li>params：原 application.yml {@code adapter.*} 段全部值（URL/字段串/referer/条数/备选源开关）+ 各 client 构造期
 *       {@code @Value} 缺省（值不变，只换存放地）
 * </ul>
 *
 * <p>不在此列：缓存容量（maximumSize，Caffeine 建缓存时固化，方案 §4.3「容量保持启动期」）；列表源分页参数（{@code subject.sync.page-size}
 * 等，仍走 yml，非 {@code adapter:} 段）。
 */
public final class DataSourceDefaults {

    /** 全部源初始重试次数（改造前 14 个 adapter 均为 noRetry）。 */
    public static final int RETRIES_NONE = 0;

    /** retries &gt; 0 时的指数退避基数（改造前无源配置重试，启用重试后的统一退避起点）。 */
    public static final long RETRY_BACKOFF_BASE_MILLIS = 200;

    /**
     * 全部源种子 mode 缺省（原 yml {@code adapter.mock.enabled=true} 的语义平移，ADR-0032）： 首启空库各源初始 MOCK，页面分源切
     * REAL；亦为 {@code ConfigCenter} 数据源键缺失时的回落 mode。 行情/估值备选源开关（原 {@code
     * adapter.quote-source}/{@code adapter.valuation-source}）经 params 热读，见 {@link #params}。
     */
    public static final RuntimeDataSource.Mode DEFAULT_MODE = RuntimeDataSource.Mode.MOCK;

    /**
     * 腾讯行情/估值备选源端点（原 yml {@code adapter.tencent.quote-url}）：行情与估值共用一个端点， 无 {@code SourceCode}
     * 专属键，作为 {@code TencentQuoteClient} 构造期缺省（RESTART 级）。
     */
    public static final String TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q=";

    /** 东财全量列表端点（原 yml {@code adapter.eastmoney.list-url}）：{@code EastMoneyListClient} 构造期缺省。 */
    public static final String EASTMONEY_LIST_URL = "https://push2.eastmoney.com/api/qt/clist/get";

    /** 新浪 A 股列表端点（原 {@code adapter.sina.stock-list-url} 构造期缺省，yml 未设、值即代码缺省）。 */
    public static final String SINA_STOCK_LIST_URL =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeData";

    /** 新浪列表源 Referer（原 {@code adapter.sina.stock-referer} 构造期缺省，yml 未设、值即代码缺省）。 */
    public static final String SINA_STOCK_REFERER = "https://finance.sina.com.cn";

    /**
     * A 股列表桶源选择缺省（原 yml {@code subject.sync.a-share-source=auto}，ADR-0032 热化）： 种子 {@code
     * subject.sync.aShareSource} 与 {@code RoutingSubjectListSource} 缺省共用。ADR-0033 起为旧键兼容口径—— 写路径统一
     * {@code fallbackChain}，读取侧缺链时按本键折算。
     */
    public static final String A_SHARE_LIST_SOURCE = "auto";

    /**
     * 降级链代码兜底（ADR-0033，用户拍板「DB 配置优先、代码固定默认只做兜底」）： DB 无 {@code fallbackChain} 配置时按注册表全链兜底（首元素 =
     * 默认主源，即 auto 语义）。注册表本体见 {@link SourceProviders}（代码事实， 校验/页面/引擎三方共用，防两处定义漂移）。
     */
    public static List<SourceProvider> fallbackChain(SourceCode code) {
        return SourceProviders.providers(code);
    }

    /** A 股列表桶（{@code subject.sync}）降级链代码兜底（ADR-0033）。 */
    public static List<SourceProvider> aShareListFallbackChain() {
        return SourceProviders.A_SHARE_LIST_PROVIDERS;
    }

    private DataSourceDefaults() {}

    /** 单次调用弹性超时（毫秒）。 */
    public static long timeoutMillis(SourceCode code) {
        return switch (code) {
            case QUOTE -> 1500;
            case EVENT -> 500;
            case FINANCE, VALUATION, ANNOUNCE, NEWS, POLICY -> 2000;
        };
    }

    /** 缓存 TTL（秒）。 */
    public static long cacheTtlSeconds(SourceCode code) {
        return switch (code) {
            case QUOTE -> 5;
            case FINANCE, VALUATION -> 3600;
            case ANNOUNCE -> 300;
            case NEWS -> 120;
            case POLICY -> 600;
            case EVENT -> 30;
        };
    }

    /**
     * 失败负缓存 TTL（秒，P1-5b/P2 体检条目）：FAILED/超时降级/MISSING 结果入短 TTL 负缓存， 防故障源每请求吃满全额超时预算（实测 push2 被封环境
     * 24h 异常 1950 次）。 行情源取 10s（对齐其 5s 高频 OK TTL），其余源 30s——窗口内命中负缓存快速返回降级态，过期即重试真实源（恢复感知 ≤30s）。
     */
    public static long failureCacheTtlSeconds(SourceCode code) {
        return switch (code) {
            case QUOTE -> 10;
            case FINANCE, VALUATION, ANNOUNCE, NEWS, POLICY, EVENT -> 30;
        };
    }

    /**
     * 各源自由参数缺省（原 application.yml {@code adapter.*} 段值原样迁入，ADR-0032；EVENT 本地表无外呼参数）。
     *
     * <p>增量键：QUOTE/VALUATION 的 {@code backupSource}（原 yml {@code adapter.quote-source}/{@code
     * adapter.valuation-source} 备选源开关，热化后经 params 用时读取）；ANNOUNCE 的 {@code announceReferer} （原 yml
     * {@code adapter.eastmoney.announce-referer}——T36 时 client 已每调用读 params 但种子缺该键，本次补齐）； FINANCE 的
     * {@code financeSina*UrlTemplate} 与 ANNOUNCE 的 {@code cninfo*}（ADR-0034 备选源接入新增，无 yml
     * 历史，代码内置即初始值）。
     */
    public static Map<String, Object> params(SourceCode code) {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (code) {
            case QUOTE -> {
                params.put("quoteUrl", "https://push2.eastmoney.com/api/qt/stock/get");
                params.put("fields", "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171");
                params.put("backupSource", BACKUP_SOURCE_AUTO);
            }
            case FINANCE -> {
                params.put("financeUrl", "https://datacenter-web.eastmoney.com/api/data/v1/get");
                params.put("financeReferer", "https://data.eastmoney.com/");
                // 新浪 vFD 备选两页 URL 模板（ADR-0034 T55）：占位符 {code}/{year} 运行时替换，沿
                // announceDetailUrlTemplate 先例
                params.put(
                        "financeSinaProfitUrlTemplate",
                        "https://money.finance.sina.com.cn/corp/go.php/vFD_ProfitStatement"
                                + "/stockid/{code}/ctrl/{year}/displaytype/4.phtml");
                params.put(
                        "financeSinaGuideUrlTemplate",
                        "https://money.finance.sina.com.cn/corp/go.php/vFD_FinancialGuideLine"
                                + "/stockid/{code}/ctrl/{year}/displaytype/4.phtml");
            }
            case VALUATION -> {
                params.put("quoteUrl", "https://push2.eastmoney.com/api/qt/stock/get");
                params.put("valuationFields", "f57,f162,f167");
                params.put("backupSource", BACKUP_SOURCE_AUTO);
            }
            case ANNOUNCE -> {
                params.put(
                        "announceUrl", "https://np-anotice-stock.eastmoney.com/api/security/ann");
                // M12（REQ-20260925-09）：公告分区页码分页，缺省 3→10（存量 DB 行经 V19 条件迁移对齐，校验收紧 1~50）
                params.put("announcePageSize", 10);
                params.put(
                        "announceDetailUrlTemplate",
                        "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf");
                params.put("announceReferer", "https://data.eastmoney.com/");
                // 巨潮 cninfo 备选三参数（ADR-0034 T57）：查询 POST 端点 / orgId 映射表 / 详情 PDF 直链前缀
                params.put("cninfoQueryUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query");
                params.put(
                        "cninfoStockListUrl", "https://www.cninfo.com.cn/new/data/szse_stock.json");
                params.put("cninfoDetailUrlPrefix", "https://static.cninfo.com.cn/");
            }
            case NEWS -> {
                params.put("newsUrl", "https://feed.mix.sina.com.cn/api/roll/get");
                params.put("newsPageId", 153);
                params.put("newsLid", 2510);
                params.put("newsPageSize", 20);
                params.put("newsReferer", "https://finance.sina.com.cn");
            }
            case POLICY -> {
                // M12 T93（方案 §1.2 实测 3/4）：zuixin HTML 列表为 AJAX 空壳，缺省改指静态 ZUIXINZHENGCE.json
                // （存量 DB 行经 V20 条件迁移对齐；页面手改回 HTML URL 即回退，GovPolicyClient 双分支兼容）
                params.put(
                        "policyUrl", "https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json");
                params.put("policyMaxItems", 30);
                params.put("policyReferer", "https://www.gov.cn/");
            }
            case EVENT -> {
                // 事件源读本地 anomaly_event 表（ADR-0013），无外呼参数
            }
        }
        return Map.copyOf(params);
    }

    /** 备选源开关缺省值：auto（东财失败自动降级备选源重拉，ADR-0030/0031 语义）。 */
    public static final String BACKUP_SOURCE_AUTO = "auto";

    /** 字符串参数缺省（client 构造期回落与种子共用同一来源，防两处定义漂移）；缺键返回 null。 */
    public static String paramString(SourceCode code, String key) {
        Object value = params(code).get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 整型参数缺省；缺键返回 {@code fallback}。 */
    public static int paramInt(SourceCode code, String key, int fallback) {
        Object value = params(code).get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
