package com.info.platform.infrastructure.common;

import com.info.platform.domain.aggregation.SourceCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 数据源运行时配置视图（基础设施层值对象，T36 / ADR-0017）。
 *
 * <p>对应 {@code runtime_config} 键 {@code datasource.{SOURCE_CODE}} 的类型化读取，消费点（RoutingSourceAdapter
 * 路由 / AbstractSourceAdapter 弹性 / SourceCache TTL / 各 HTTP client 参数）<b>用时读取</b>即热生效（方案 §4.2
 * 数据源参数全部 LIVE）。 键不存在或解析失败时经 {@link #fallback} 回落到 {@link DataSourceDefaults} 代码缺省（对齐改造前各
 * adapter/client 的硬编码值）。
 *
 * @param sourceCode 数据源标识
 * @param enabled 是否参与聚合（false → RoutingSourceAdapter 直接 MISSING，不外调）
 * @param mode 运行模式（REAL 真实外呼 / MOCK 模拟数据）
 * @param timeoutMillis 单次调用弹性超时（毫秒）
 * @param retries 额外重试次数（0 = 不重试）
 * @param cacheTtlSeconds 本源缓存 TTL（秒，新缓存条目生效）
 * @param failureCacheTtlSeconds 失败负缓存 TTL（秒，P1-5b；≤0 视为缺失回落 {@link
 *     DataSourceDefaults#failureCacheTtlSeconds}——存量 runtime_config 行无该字段，读取不阻断）
 * @param params 各源自由参数（URL / 条数 / referer 等，键空间见各 client）
 * @param fallbackChain 降级链原始值（ADR-0033）：{@code null} = 未配置（按旧 {@code params.backupSource} 折算，
 *     再缺回落注册表全链）；空清单 = 仅主源；有效解析经 {@code FallbackChains.resolve}（含折算与兜底）
 */
public record RuntimeDataSource(
        SourceCode sourceCode,
        boolean enabled,
        Mode mode,
        long timeoutMillis,
        int retries,
        long cacheTtlSeconds,
        long failureCacheTtlSeconds,
        Map<String, Object> params,
        List<String> fallbackChain) {

    /** 运行模式（原全局 yml {@code adapter.mock.enabled} 细化为分源，PRD 场景 3.2）。 */
    public enum Mode {
        REAL,
        MOCK
    }

    /** 兼容构造（无降级链字段；T36 既有调用点与测试口径，等价 fallbackChain=null）。 */
    public RuntimeDataSource(
            SourceCode sourceCode,
            boolean enabled,
            Mode mode,
            long timeoutMillis,
            int retries,
            long cacheTtlSeconds,
            long failureCacheTtlSeconds,
            Map<String, Object> params) {
        this(
                sourceCode,
                enabled,
                mode,
                timeoutMillis,
                retries,
                cacheTtlSeconds,
                failureCacheTtlSeconds,
                params,
                null);
    }

    public RuntimeDataSource {
        params = params == null ? Map.of() : Map.copyOf(params);
        fallbackChain = fallbackChain == null ? null : List.copyOf(fallbackChain);
        if (failureCacheTtlSeconds <= 0) {
            // 存量文档缺字段（Jackson 原始类型缺省 0）/非法值：回落代码缺省，不阻断读取（对齐键缺失降级精神）
            failureCacheTtlSeconds = DataSourceDefaults.failureCacheTtlSeconds(sourceCode);
        }
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMillis);
    }

    public Duration cacheTtl() {
        return Duration.ofSeconds(cacheTtlSeconds);
    }

    /** 失败负缓存 TTL（P1-5b：FAILED/MISSING 短 TTL 分档）。 */
    public Duration failureCacheTtl() {
        return Duration.ofSeconds(failureCacheTtlSeconds);
    }

    /** 字符串参数（URL / fields 等），缺失回落 fallback。 */
    public String paramString(String key, String fallback) {
        Object value = params.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 整型参数（条数 / 分类 id 等），缺失或非法回落 fallback。 */
    public int paramInt(String key, int fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 键缺失/解析失败时的代码缺省（值与 DataSourceDefaults 一致，mode 由调用方按全局 mock 开关裁定）。 */
    public static RuntimeDataSource fallback(SourceCode code, Mode mode) {
        return new RuntimeDataSource(
                code,
                true,
                mode,
                DataSourceDefaults.timeoutMillis(code),
                DataSourceDefaults.RETRIES_NONE,
                DataSourceDefaults.cacheTtlSeconds(code),
                DataSourceDefaults.failureCacheTtlSeconds(code),
                DataSourceDefaults.params(code));
    }
}
