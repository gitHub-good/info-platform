package com.info.platform.interfaces.aggregation;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.application.aggregation.SubjectQuote;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.Result;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 *
 * <p>{@code GET /api/v1/subjects/by-code/{code}}（P0-1）— 内部统一代码 → 数字主键解析，
 * 供前端路由（#/subjects/SH600519）接入数字主键寻址的聚合详情接口。
 *
 * <p>{@code GET /api/v1/subjects/search?q=xxx&limit=20}（体检 P1-2）— 按代码/名称模糊搜索启用标的，
 * 供前端搜索选择器替代手输数字主键；q 空白或 limit 越界 → 2xxx（400）。
 *
 * <p>{@code GET /api/v1/subjects/quotes?ids=1,2,3}（体检 P1-2）— 批量标的摘要+行情（自选清单表格）， 任一标的行情失败置 null
 * 不阻断；ids 空白/非法/超上限 → 2xxx（400）。
 */
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    /** search 默认返回条数。 */
    static final int DEFAULT_SEARCH_LIMIT = 20;

    /** search 单次返回上限（防一次性拉全表式滥用）。 */
    static final int MAX_SEARCH_LIMIT = 50;

    /** quotes 单次允许的 id 数上限（与 search 上限同量级）。 */
    static final int MAX_QUOTES_IDS = 50;

    private final AggregationService aggregationService;
    private final SubjectRepository subjectRepository;

    public SubjectController(
            AggregationService aggregationService, SubjectRepository subjectRepository) {
        this.aggregationService = aggregationService;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/by-code/{code}")
    public Result<SubjectSummaryView> getByCode(@PathVariable String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "subjectCode 不能为空");
        }
        SubjectSummaryView view =
                subjectRepository
                        .findByCode(SubjectCode.of(code))
                        .map(SubjectSummaryView::from)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));
        return Result.ok(view);
    }

    /** 标的搜索（体检 P1-2）：按 subjectCode/name 模糊匹配启用标的，无结果返回空列表（非错误）。 */
    @GetMapping("/search")
    public Result<List<SubjectSummaryView>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit) {
        if (q == null || q.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "q 不能为空");
        }
        int effectiveLimit = limit == null ? DEFAULT_SEARCH_LIMIT : limit; // 缺省 20，与契约示例一致
        if (effectiveLimit < 1 || effectiveLimit > MAX_SEARCH_LIMIT) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "limit 须在 1~" + MAX_SEARCH_LIMIT + " 之间");
        }
        List<SubjectSummaryView> views =
                subjectRepository.searchEnabled(q.trim(), effectiveLimit).stream()
                        .map(SubjectSummaryView::from)
                        .toList();
        return Result.ok(views);
    }

    /** 批量标的摘要+行情（体检 P1-2 自选清单表格）：不存在的主键跳过，行情失败行为 quote=null。 */
    @GetMapping("/quotes")
    public Result<List<SubjectQuote>> getQuotes(
            @RequestParam(name = "ids", required = false) String ids) {
        return Result.ok(aggregationService.getQuotes(parseIds(ids)));
    }

    /** 逗号分隔 id → 去重列表；空白/非正整数/数量超上限 → 2xxx（400）。 */
    private static List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "ids 不能为空");
        }
        Set<Long> parsed = new LinkedHashSet<>();
        for (String token : ids.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "非法 id: " + trimmed);
                }
                parsed.add(id);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "非法 id: " + trimmed);
            }
        }
        if (parsed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "ids 不能为空");
        }
        if (parsed.size() > MAX_QUOTES_IDS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "ids 数量超过上限 " + MAX_QUOTES_IDS);
        }
        return List.copyOf(parsed);
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
