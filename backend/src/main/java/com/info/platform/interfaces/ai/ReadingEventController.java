package com.info.platform.interfaces.ai;

import com.info.platform.application.ai.ReadingEventService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阅读行为留痕接口（T29 推荐相关性优化，对齐技术方案 §4.1.6 个性化推荐数据输入）。
 *
 * <p>{@code POST /api/v1/reading-events}（Bearer）：前端在标的详情页/政策详情/简报页/信息流「点原文」 静默埋点上报阅读事件（信息流条目
 * contentType=FEED、contentRef=FeedItem 稳定 contentId、 公告/新闻/推荐条目附 subjectCode，REQ-20260925-08），
 * 供每日推荐的个性化相关性排序（已读标的热度 + 时间衰减）消费。幂等友好：同 user+type+ref 1 小时窗口内 重复上报返回 {@code
 * recorded=false}（不报错、不落重复行），防前端重试与 StrictMode 双触发。
 *
 * <p>前端埋点约定：fire-and-forget（失败静默，不打扰主流程）；本接口无读取端点（画像经推荐链路内部消费）。
 */
@RestController
@RequestMapping("/api/v1/reading-events")
public class ReadingEventController {

    private static final Logger log = LoggerFactory.getLogger(ReadingEventController.class);

    private final ReadingEventService readingEventService;

    public ReadingEventController(ReadingEventService readingEventService) {
        this.readingEventService = readingEventService;
    }

    /**
     * 记录一次阅读。
     *
     * @return 200 + {recorded: true 本次落库 / false 窗口内去重跳过}
     */
    @PostMapping
    public Result<ReadingEventResultView> record(
            @Valid @RequestBody RecordReadingEventRequest request) {
        long userId = currentUserId();
        log.debug(
                "阅读留痕请求 userId={} type={} ref={}",
                userId,
                request.contentType(),
                request.contentRef());
        boolean recorded =
                readingEventService.record(
                        userId,
                        request.contentType(),
                        request.contentRef(),
                        request.subjectCode(),
                        request.subjectId());
        return Result.ok(new ReadingEventResultView(recorded));
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }

    /** 阅读留痕请求体：contentType/contentRef 必填，subjectCode/subjectId 二选一可空（政策等无标的阅读）。 */
    public record RecordReadingEventRequest(
            @NotBlank(message = "contentType 不能为空") String contentType,
            @NotBlank(message = "contentRef 不能为空") @Size(max = 200, message = "contentRef 长度 ≤200")
                    String contentRef,
            @Size(max = 32, message = "subjectCode 长度 ≤32") String subjectCode,
            Long subjectId) {}

    /** 阅读留痕响应：recorded=false 表窗口内重复被去重（幂等友好，非错误）。 */
    public record ReadingEventResultView(boolean recorded) {}
}
