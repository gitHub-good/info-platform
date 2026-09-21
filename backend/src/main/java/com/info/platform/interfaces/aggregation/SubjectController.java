package com.info.platform.interfaces.aggregation;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.Result;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标的详情聚合查询接口（对齐技术方案 §4.1.1）。
 *
 * <p>{@code GET /api/v1/subjects/{subjectId}/detail?sections=quote,finance,...} — sections
 * 可选（默认全部分区）； 单源缺失不阻断，返回体含 sourceStatus 标注每分区状态。标的不存在 → 30001（404）；非法 section → 2xxx（400）。
 */
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final AggregationService aggregationService;

    public SubjectController(AggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/{subjectId}/detail")
    public Result<SubjectDetail> getDetail(
            @PathVariable Long subjectId,
            @RequestParam(name = "sections", required = false) String sections) {
        Set<SourceCode> parsed = parseSections(sections);
        SubjectDetail detail = aggregationService.getDetail(subjectId, parsed);
        return Result.ok(detail);
    }

    /** 逗号分隔 section 名 → {@link SourceCode} 集合（大小写不敏感）；空集表示取全部。非法名 → 2xxx。 */
    private static Set<SourceCode> parseSections(String sections) {
        if (sections == null || sections.isBlank()) {
            return Set.of();
        }
        Set<SourceCode> result = EnumSet.noneOf(SourceCode.class);
        for (String token : sections.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(SourceCode.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "非法 section: " + trimmed);
            }
        }
        return result;
    }
}
