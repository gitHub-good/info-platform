package com.info.platform.application.feed;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.SourceConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 资讯源配置校验器（M13 T100，方案 §4.5「配置错误可诊断」——蓝图故事 1 场景 2）。
 *
 * <p>保存前后端双校验的后端单点：类型白名单/URL/映射结构/headers 白名单/游标声明/数值边界，失败抛 30072（msg 字段级，多问题以 "； " 连接）。频控 1~60
 * 由实体构造期把守（领域规则单一事实源，此处不重复）。robots 硬校验（30075）在注册服务保存路径叠加（T105）。
 *
 * <p>transform 白名单为线格式契约（与 {@code field-mapping/*.json} 同名空间 + M13 新增三值），引擎侧 {@code Transform.from}
 * 为运行期解析兜底。
 */
@Component
public class SourceConfigValidator {

    /** 页面新增向导开放的通道（蓝图裁决 1：HTML 站点走开发侧预置通道）。 */
    private static final Set<AdapterType> CREATE_ALLOWED_TYPES =
            Set.of(AdapterType.RSS, AdapterType.JSON_API);

    /** transform 线格式白名单（既有五值 + M13 三扩展 + M14 T110 epoch_millis_to_iso）。 */
    private static final Set<String> ALLOWED_TRANSFORMS =
            Set.of(
                    "none",
                    "to_string",
                    "to_long",
                    "to_decimal",
                    "to_iso_date",
                    "to_iso_datetime",
                    "epoch_seconds_to_iso",
                    "epoch_millis_to_iso",
                    "strip_html");

    /** headers 白名单（UA/Referer；config 禁止存放密钥，方案 §5）。 */
    private static final Set<String> ALLOWED_HEADER_NAMES = Set.of("user-agent", "referer");

    private static final int MAX_ITEMS_MIN = 1;
    private static final int MAX_ITEMS_MAX = 200;
    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 100;

    /** 新增通道预检（T105 构造前拦截）：preset 通道对实体有 adapter_ref 强约束，须先于构造拒绝， 报错文案保持「adapterType」字段级。 */
    public void validateCreateType(AdapterType adapterType) {
        if (!CREATE_ALLOWED_TYPES.contains(adapterType)) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID,
                    "adapterType: 仅允许 rss / json_api（页面新增不开放 "
                            + (adapterType == null ? "空值" : adapterType.wireCode())
                            + "）");
        }
    }

    /** 新增通用源校验：通道白名单 + 通用结构校验。 */
    public void validateCreate(InfoSource source) {
        List<String> problems = new ArrayList<>();
        if (!CREATE_ALLOWED_TYPES.contains(source.getAdapterType())) {
            problems.add(
                    "adapterType: 仅允许 rss / json_api（页面新增不开放 "
                            + source.getAdapterType().wireCode()
                            + "）");
        }
        collectCommonProblems(source, problems);
        raiseIfAny(problems);
    }

    /** 通用结构校验（新增与编辑共用；预置源编辑亦走此处）。 */
    public void validateCommon(InfoSource source) {
        List<String> problems = new ArrayList<>();
        collectCommonProblems(source, problems);
        raiseIfAny(problems);
    }

    private static void collectCommonProblems(InfoSource source, List<String> problems) {
        validateEndpoint(source.getEndpoint(), problems);
        SourceConfig config = source.getConfig();
        validateMappings(source.getAdapterType(), config, problems);
        validateHeaders(config, problems);
        validateNumericBounds(config, problems);
        validateCursorDeclaration(config, problems);
        validateUrlTemplate(config, problems);
    }

    private static void validateEndpoint(String endpoint, List<String> problems) {
        if (endpoint != null
                && !endpoint.startsWith("http://")
                && !endpoint.startsWith("https://")) {
            problems.add("endpoint: 须为 http(s) URL，当前值 " + endpoint);
        }
    }

    /** URL 合成模板（M14 T110）：声明即须 http(s) 开头且含 {@code {externalId}} 占位（否则合不成条目直链）。 */
    private static void validateUrlTemplate(SourceConfig config, List<String> problems) {
        String template = config.urlTemplate();
        if (template == null || template.isBlank()) {
            return;
        }
        if (!template.startsWith("http://") && !template.startsWith("https://")) {
            problems.add("urlTemplate: 须为 http(s) URL 模板，当前值 " + template);
        }
        if (!template.contains("{externalId}")) {
            problems.add("urlTemplate: 须含 {externalId} 占位符，当前值 " + template);
        }
    }

    private static void validateMappings(
            AdapterType adapterType, SourceConfig config, List<String> problems) {
        List<SourceConfig.ItemMapping> mappings = config.mappings();
        if (mappings.isEmpty()) {
            // rss 有默认映射可省；json_api 必须显式映射（无映射无法定位字段）
            if (adapterType == AdapterType.JSON_API) {
                problems.add("itemMapping: json_api 源必填（至少映射 title）");
            }
            return;
        }
        boolean hasTitle = false;
        for (SourceConfig.ItemMapping mapping : mappings) {
            if (mapping.source().isBlank() || mapping.target().isBlank()) {
                problems.add("itemMapping: source/target 不能为空（" + mapping + "）");
                continue;
            }
            String transform = mapping.transform() == null ? "none" : mapping.transform();
            if (!ALLOWED_TRANSFORMS.contains(transform)) {
                problems.add("itemMapping.transform: 未知转换规则 " + transform);
            }
            if ("title".equals(mapping.target())) {
                hasTitle = true;
            }
        }
        if (adapterType == AdapterType.JSON_API && !hasTitle) {
            problems.add("itemMapping: json_api 源必须映射 title 字段");
        }
    }

    private static void validateHeaders(SourceConfig config, List<String> problems) {
        if (config.headers() == null) {
            return;
        }
        for (String name : config.headers().keySet()) {
            if (!ALLOWED_HEADER_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                problems.add("headers: 仅允许 User-Agent / Referer（禁止存放密钥），当前含 " + name);
            }
        }
    }

    private static void validateNumericBounds(SourceConfig config, List<String> problems) {
        if (config.maxItems() != null
                && (config.maxItems() < MAX_ITEMS_MIN || config.maxItems() > MAX_ITEMS_MAX)) {
            problems.add(
                    "maxItems: 须在 "
                            + MAX_ITEMS_MIN
                            + "~"
                            + MAX_ITEMS_MAX
                            + "，当前值 "
                            + config.maxItems());
        }
        if (config.pageSize() != null
                && (config.pageSize() < PAGE_SIZE_MIN || config.pageSize() > PAGE_SIZE_MAX)) {
            problems.add(
                    "pageSize: 须在 "
                            + PAGE_SIZE_MIN
                            + "~"
                            + PAGE_SIZE_MAX
                            + "，当前值 "
                            + config.pageSize());
        }
    }

    private static void validateCursorDeclaration(SourceConfig config, List<String> problems) {
        CursorType type = config.effectiveCursorType();
        boolean hasCursorField = config.cursorField() != null && !config.cursorField().isBlank();
        if (type != CursorType.NONE && !hasCursorField) {
            problems.add("cursorField: cursorType=" + type.name() + " 时必填（游标取值字段）");
        }
        if (type == CursorType.NONE && hasCursorField) {
            problems.add("cursorField: cursorType=NONE 时不应声明（冗余声明即配置漂移隐患）");
        }
    }

    private static void raiseIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID, String.join("; ", problems));
        }
    }
}
