package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;

/**
 * 各 HTTP client 的运行时参数读取小工具（T36）。
 *
 * <p>URL/条数/referer 等外呼参数改<b>每次调用</b>从 {@code datasource.{CODE}.params} 读取（LIVE 级热生效，方案 §4.3「client
 * params 改每调用读取」）。 配置中心为 null（纯构造单测场景，无 Spring 处理字段注入）时回落调用方传入的 yml 值， 既有测试零改动。
 */
final class RuntimeParams {

    private RuntimeParams() {}

    /** 字符串参数（URL / fields / referer 等）。 */
    static String of(ConfigCenter center, SourceCode code, String key, String ymlFallback) {
        return center == null ? ymlFallback : center.dataSource(code).paramString(key, ymlFallback);
    }

    /** 整型参数（条数 / 分类 id 等）。 */
    static int intOf(ConfigCenter center, SourceCode code, String key, int ymlFallback) {
        return center == null ? ymlFallback : center.dataSource(code).paramInt(key, ymlFallback);
    }
}
