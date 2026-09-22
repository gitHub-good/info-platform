package com.info.platform.application.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.ConfigFieldRules.FieldRule;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 数据源域运行时配置校验器（T36，键空间规则见方案 §4.1 / UI 方案 §5.3）。
 *
 * <p>覆盖 {@code datasource.{SOURCE_CODE}} 7 键。字段表：enabled 布尔、mode 枚举（REAL|MOCK）、timeoutMillis 正整数、
 * retries 0~3、cacheTtlSeconds 正整数；params 为各源自由参数（URL http(s) 格式、条数正整数），键空间按源白名单收口（防拼写错静默失效）， EVENT
 * 源读本地表无外呼参数、params 须为空。 校验对象为「当前文档合并请求字段后」的完整文档（部分更新由 facade 先合并再落库）， 故种子始终写入的字段按必填校验。
 */
@Component
public class DataSourceConfigValidator implements RuntimeConfigValidator {

    /** 键前缀（与 ConfigCenter.KEY_DATASOURCE_PREFIX 同值；application 层不引基础设施类型，本地声明防层间环）。 */
    static final String KEY_PREFIX = "datasource.";

    private static final List<FieldRule> RULES =
            List.of(
                    required(ConfigFieldRules.bool("enabled")),
                    required(
                            ConfigFieldRules.oneOf(
                                    "mode",
                                    RuntimeDataSourceMode.REAL,
                                    RuntimeDataSourceMode.MOCK)),
                    required(ConfigFieldRules.positiveLong("timeoutMillis")),
                    required(ConfigFieldRules.longRange("retries", 0, 3)),
                    required(ConfigFieldRules.positiveLong("cacheTtlSeconds")));

    /** 各源 params 白名单（键空间收口；EVENT 为空 = 不允许任何外呼参数）。 */
    private static final Map<SourceCode, Set<String>> ALLOWED_PARAMS =
            Map.of(
                    SourceCode.QUOTE, Set.of("quoteUrl", "fields"),
                    SourceCode.FINANCE, Set.of("financeUrl", "financeReferer"),
                    SourceCode.VALUATION, Set.of("quoteUrl", "valuationFields"),
                    SourceCode.ANNOUNCE,
                            Set.of("announceUrl", "announcePageSize", "announceDetailUrlTemplate"),
                    SourceCode.NEWS,
                            Set.of(
                                    "newsUrl",
                                    "newsPageId",
                                    "newsLid",
                                    "newsPageSize",
                                    "newsReferer"),
                    SourceCode.POLICY, Set.of("policyUrl", "policyReferer"),
                    SourceCode.EVENT, Set.of());

    /** 本地枚举镜像（不引基础设施类型，application 层保持只依赖 domain/common）。 */
    private static final class RuntimeDataSourceMode {
        static final String REAL = "REAL";
        static final String MOCK = "MOCK";
    }

    @Override
    public boolean supports(String configKey) {
        return sourceCodeOf(configKey) != null;
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        SourceCode code = sourceCodeOf(configKey);
        if (code == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "未知配置键: " + configKey);
        }
        // 字段表与 params 问题一次性收集（字段表首错抛出不再短路 params 校验，页面一次标全错误）
        List<String> problems = new ArrayList<>(enforceCollect(document, RULES));
        problems.addAll(paramsProblems(code, document.get("params")));
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }

    /** 字段表收集（不抛出，供与 params 问题合并）。 */
    private static List<String> enforceCollect(JsonNode document, List<FieldRule> rules) {
        try {
            ConfigFieldRules.enforce(document, rules);
            return List.of();
        } catch (BusinessException e) {
            return List.of(e.getMessage());
        }
    }

    /** params 结构与取值校验：对象 + 键白名单 + URL/条数按后缀规则（返回问题清单）。 */
    private static List<String> paramsProblems(SourceCode code, JsonNode params) {
        if (params == null || params.isNull()) {
            return List.of("params: 必填（可为空对象）");
        }
        if (!params.isObject()) {
            return List.of("params: 须为对象");
        }
        List<String> problems = new ArrayList<>();
        Set<String> allowed = ALLOWED_PARAMS.get(code);
        params.fields()
                .forEachRemaining(
                        field -> {
                            String key = field.getKey();
                            JsonNode value = field.getValue();
                            if (!allowed.contains(key)) {
                                problems.add("params." + key + ": 不支持的字段（源 " + code + "）");
                                return;
                            }
                            if (isUrlParam(key)) {
                                if (!isHttpUrl(value)) {
                                    problems.add("params." + key + ": 须以 http(s):// 开头且不含空白");
                                }
                            } else if (isCountParam(key)) {
                                if (!value.isIntegralNumber() || value.asLong() <= 0) {
                                    problems.add("params." + key + ": 须为正整数");
                                }
                            } else if (!value.isTextual() || value.asText().isBlank()) {
                                problems.add("params." + key + ": 不能为空");
                            }
                        });
        return problems;
    }

    private static boolean isUrlParam(String key) {
        return key.endsWith("Url") || key.endsWith("Template") || key.endsWith("Referer");
    }

    private static boolean isCountParam(String key) {
        return key.endsWith("Size") || key.endsWith("PageId") || key.endsWith("Lid");
    }

    private static boolean isHttpUrl(JsonNode value) {
        return value.isTextual()
                && (value.asText().startsWith("http://") || value.asText().startsWith("https://"))
                && !value.asText().contains(" ");
    }

    private static SourceCode sourceCodeOf(String configKey) {
        if (configKey == null || !configKey.startsWith(KEY_PREFIX)) {
            return null;
        }
        String code = configKey.substring(KEY_PREFIX.length());
        try {
            return SourceCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 可选规则升为必填（种子始终写入的字段，缺行/缺字段即配置面异常）。 */
    private static FieldRule required(FieldRule rule) {
        return new FieldRule(rule.field(), true, rule.checker(), rule.violation());
    }
}
