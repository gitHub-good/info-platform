package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源弹性/缓存权威缺省值（T36，从各 adapter/client 硬编码提取）。
 *
 * <p>三处用途共用同一份常量，保证不漂移：① {@code DataSourceRuntimeConfigSeeder} 种子值（首启导入 runtime_config，页面改过即以 DB
 * 为权威）； ② {@code ConfigCenter#dataSource} 键缺失/解析失败的回落； ③ 纯构造单测 （无 Spring 上下文）的 {@code
 * AbstractSourceAdapter} 弹性缺省。 提取对照（源码硬编码 → 配置键）见 T36 交付说明：
 *
 * <ul>
 *   <li>超时：QuoteSourceAdapter 1500ms；Valuation/Finance/Announce/News/Policy 各 2s；Event 500ms（原各类
 *       TIMEOUT 常量）
 *   <li>重试：全部源 0（原全部 {@code ResilienceSpec.noRetry}）
 *   <li>缓存 TTL：SourceCache.specFor —— 行情 5s / 财务·估值 1h / 公告 5min / 新闻 2min / 政策 10min / 事件 30s
 *   <li>params：各 HTTP client 构造期 {@code @Value} 默认值（与 application.yml 同值）
 * </ul>
 *
 * <p>缓存容量（maximumSize）不在此列：Caffeine 容量建缓存时固化，保持启动期（方案 §4.3「容量保持启动期」）。
 */
public final class DataSourceDefaults {

    /** 全部源初始重试次数（改造前 14 个 adapter 均为 noRetry）。 */
    public static final int RETRIES_NONE = 0;

    /** retries &gt; 0 时的指数退避基数（改造前无源配置重试，启用重试后的统一退避起点）。 */
    public static final long RETRY_BACKOFF_BASE_MILLIS = 200;

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

    /** 各源自由参数缺省（与 application.yml 同值；EVENT 本地表无外呼参数）。 */
    public static Map<String, Object> params(SourceCode code) {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (code) {
            case QUOTE -> {
                params.put("quoteUrl", "https://push2.eastmoney.com/api/qt/stock/get");
                params.put("fields", "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171");
            }
            case FINANCE -> {
                params.put("financeUrl", "https://datacenter-web.eastmoney.com/api/data/v1/get");
                params.put("financeReferer", "https://data.eastmoney.com/");
            }
            case VALUATION -> {
                params.put("quoteUrl", "https://push2.eastmoney.com/api/qt/stock/get");
                params.put("valuationFields", "f57,f162,f167");
            }
            case ANNOUNCE -> {
                params.put(
                        "announceUrl", "https://np-anotice-stock.eastmoney.com/api/security/ann");
                params.put("announcePageSize", 3);
                params.put(
                        "announceDetailUrlTemplate",
                        "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf");
            }
            case NEWS -> {
                params.put("newsUrl", "https://feed.mix.sina.com.cn/api/roll/get");
                params.put("newsPageId", 153);
                params.put("newsLid", 2510);
                params.put("newsPageSize", 20);
                params.put("newsReferer", "https://finance.sina.com.cn");
            }
            case POLICY -> {
                params.put("policyUrl", "https://www.gov.cn/zhengce/");
                params.put("policyReferer", "https://www.gov.cn/");
            }
            case EVENT -> {
                // 事件源读本地 anomaly_event 表（ADR-0013），无外呼参数
            }
        }
        return Map.copyOf(params);
    }
}
