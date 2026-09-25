package com.info.platform.interfaces.aggregation;

import com.info.platform.application.aggregation.AnnouncementPageView;
import com.info.platform.application.aggregation.EventPageView;
import com.info.platform.application.aggregation.NewsPageView;
import com.info.platform.application.aggregation.SubjectSectionPageService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标的详情分区子端点（M12 REQ-20260925-09，方案 §4.1 / ADR-0037 决策 1）。
 *
 * <p>{@code GET /api/v1/subjects/{subjectId}/announcements?page=&size=} — 公告分区分页（page 必填 1~5，超限 400
 * 「更多历史公告请走源站」；size 可选 1~50，缺省 = 运行时 announcePageSize）。一请求=一分区（分区独立翻页三约束①）， 取数绕 SourceCache 直调源（决策
 * 2），sourceStatus 三态与聚合口径同源（约束③）。
 *
 * <p>{@code GET /api/v1/subjects/{subjectId}/events?page=&size=} — 事件分区分页（T91，方案 §4.1.2）：page 必填
 * ≥1（无 5 页上限——本地 7 天窗数据翻完即止）；size 可选 1~50，缺省 10；total 为窗内精确 count。
 *
 * <p>参数校验经 {@link PageQuery} 共用件（page≥1 / size 1~50 越界 400 拒绝不截断）；标的不存在 → 30001（404）； 源失败/超时 → 200
 * + 降级态（不是 HTTP 错误，方案 §4.1.5）。
 */
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectSectionPageController {

    private static final Logger log = LoggerFactory.getLogger(SubjectSectionPageController.class);

    /** 公告子端点页码上限（D5：后端 400 硬拒绝是权威，前端 maxPages 显示层封顶双保险）。 */
    static final int ANNOUNCE_MAX_PAGE = 5;

    private static final String ANNOUNCE_OVER_LIMIT_MESSAGE =
            "page 超过上限 " + ANNOUNCE_MAX_PAGE + "，更多历史公告请走源站";

    /** 新闻子端点拒 size 参数的契约 msg（方案 §4.1.3：源页大小是运维配置，不属调用方自由度）。 */
    private static final String NEWS_SIZE_REJECTED_MESSAGE = "size 仅公告/事件端点可用";

    private final SubjectSectionPageService sectionPageService;

    public SubjectSectionPageController(SubjectSectionPageService sectionPageService) {
        this.sectionPageService = sectionPageService;
    }

    /** 公告分区分页（方案 §4.1.1）。 */
    @GetMapping("/{subjectId}/announcements")
    public Result<AnnouncementPageView> announcements(
            @PathVariable Long subjectId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        int resolvedPage =
                PageQuery.requirePage(page, ANNOUNCE_MAX_PAGE, ANNOUNCE_OVER_LIMIT_MESSAGE);
        Integer resolvedSize = PageQuery.requireSizeIfPresent(size);
        log.debug("公告分区子端点请求 subjectId={} page={} size={}", subjectId, resolvedPage, resolvedSize);
        return Result.ok(sectionPageService.announcements(subjectId, resolvedPage, resolvedSize));
    }

    /** 事件分区分页（M12 T91，方案 §4.1.2）：page≥1 无 5 页上限（本地数据翻完即止）。 */
    @GetMapping("/{subjectId}/events")
    public Result<EventPageView> events(
            @PathVariable Long subjectId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        int resolvedPage =
                PageQuery.requirePage(page, PageQuery.MAX_PAGE, "page 超过上限 " + PageQuery.MAX_PAGE);
        Integer resolvedSize = PageQuery.requireSizeIfPresent(size);
        log.debug("事件分区子端点请求 subjectId={} page={} size={}", subjectId, resolvedPage, resolvedSize);
        return Result.ok(sectionPageService.events(subjectId, resolvedPage, resolvedSize));
    }

    /**
     * 新闻分区「加载更多」（M12 T92，方案 §4.1.3）：page = 源页码（必填）；<b>不接受 size</b>——出现即 400 「size
     * 仅公告/事件端点可用」（源页大小是运维配置 newsPageSize，不属调用方自由度）。
     */
    @GetMapping("/{subjectId}/news")
    public Result<NewsPageView> news(
            @PathVariable Long subjectId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        int resolvedPage =
                PageQuery.requirePage(page, PageQuery.MAX_PAGE, "page 超过上限 " + PageQuery.MAX_PAGE);
        if (size != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, NEWS_SIZE_REJECTED_MESSAGE);
        }
        log.debug("新闻分区子端点请求 subjectId={} page={}", subjectId, resolvedPage);
        return Result.ok(sectionPageService.news(subjectId, resolvedPage));
    }
}
