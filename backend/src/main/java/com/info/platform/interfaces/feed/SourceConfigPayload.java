package com.info.platform.interfaces.feed;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AiExclusion;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import java.util.Map;

/**
 * {@code info_source.config} 请求负载（M13 T105，接口层线格式 → 领域 VO）：新增/编辑共用； 缺省字段回落 null（领域 effective*
 * 取缺省值），游标类型大小写不敏感、未知值由校验器给字段级 30072 提示。M14 T110 增 {@code urlTemplate}（条目直链合成模板， 澎湃等无直链字段源）。 M15
 * T125 增 {@code aiExclusion}（AI 管道排除档位，REQ 拍板五-1）。
 *
 * @param cursorType 线格式（ID / TIME / NONE；空 = NONE）
 * @param urlTemplate 条目 URL 合成模板（可选，须含 {externalId} 占位——校验器把关）
 * @param aiExclusion AI 管道排除档位线值（NONE/L2/ALL；空 = 缺省 NONE；非法值 30072 字段级）
 */
public record SourceConfigPayload(
        String listPath,
        String stripPrefix,
        String stripSuffix,
        List<ItemMappingPayload> itemMapping,
        Map<String, String> headers,
        Integer maxItems,
        Integer pageSize,
        String cursorType,
        String cursorField,
        String urlTemplate,
        String aiExclusion) {

    /** 单条字段映射负载。 */
    public record ItemMappingPayload(String source, String target, String transform) {}

    /** null 安全转领域 VO（aiExclusion 空/缺省落 null = NONE；非空非法值 30072 字段级——不静默吞）。 */
    public SourceConfig toDomain() {
        List<SourceConfig.ItemMapping> mappings =
                itemMapping == null
                        ? List.of()
                        : itemMapping.stream()
                                .map(
                                        m ->
                                                new SourceConfig.ItemMapping(
                                                        m.source(), m.target(), m.transform()))
                                .toList();
        return new SourceConfig(
                listPath,
                stripPrefix,
                stripSuffix,
                mappings,
                headers,
                maxItems,
                pageSize,
                CursorType.from(cursorType),
                cursorField,
                urlTemplate,
                parseAiExclusion());
    }

    private AiExclusion parseAiExclusion() {
        if (aiExclusion == null || aiExclusion.isBlank()) {
            return null;
        }
        AiExclusion level = AiExclusion.fromName(aiExclusion);
        if (level == null) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID,
                    "aiExclusion: 须为 NONE / L2 / ALL，当前值 " + aiExclusion);
        }
        return level;
    }

    /** 空配置（rss 缺省映射形态）。 */
    public static SourceConfigPayload empty() {
        return new SourceConfigPayload(
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
