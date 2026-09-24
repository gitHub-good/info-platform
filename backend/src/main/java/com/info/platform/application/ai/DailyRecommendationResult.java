package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.TopRecommendation;
import java.util.List;

/**
 * 每日推荐生成结果（应用层返回值，对齐技术方案 §4.1.6 {@code GET /recommendations/daily}）。
 *
 * <p>{@code status}： {@code 1}=AI 生成成功取 Top5（DONE）； {@code 2}=规则兜底（LLM 失败/超时/输出空 Top5 时降级为活跃度排序，对齐
 * Spike-2 §11.5 + 技术方案 §5 降级预案）； {@code 3}=自选池空（无活跃清单项，返回空 Top5 + 提示）； {@code 4}=当日简报未就绪（P1-5a feed
 * 只读口径：未触发或在途生成中，Top5 为空、{@code recommendationPending=true}，仅 {@link
 * DailyRecommendationService#readDaily} 产生， {@code generateDaily} 轮询至终态不会返回此态）。 {@code
 * topRecommend} 为排序后的 Top5（按 rank 升序，规则兜底时 rank=1..5）； {@code disclaimer} 恒附「AI 生成，非投资建议」（§4.1.4）；
 * {@code fallback} 标识是否走了规则兜底（前端可标「规则排序」角标）。
 *
 * @param status 状态码（1/2/3/4）
 * @param topRecommend Top5 推荐（空池时为空列表）
 * @param disclaimer 免责声明（恒附）
 * @param fallback 是否规则兜底（true=活跃度排序，非 AI 输出）
 * @param recommendationPending 当日简报未就绪（feed 只读命中未终态任务/未触发，P1-5a 增量字段）
 */
public record DailyRecommendationResult(
        int status,
        List<TopRecommendation> topRecommend,
        String disclaimer,
        boolean fallback,
        boolean recommendationPending) {

    /** 状态码：AI 生成成功取 Top5。 */
    public static final int STATUS_DONE = 1;

    /** 状态码：规则兜底（活跃度排序）。 */
    public static final int STATUS_FALLBACK = 2;

    /** 状态码：自选池空。 */
    public static final int STATUS_EMPTY = 3;

    /** 状态码：当日简报未就绪（feed 只读口径，generateDaily 不返回此态）。 */
    public static final int STATUS_PENDING = 4;

    /** 构造结果，disclaimer 缺失兜底为全局免责声明。 */
    public DailyRecommendationResult {
        topRecommend = topRecommend == null ? List.of() : List.copyOf(topRecommend);
        disclaimer =
                disclaimer == null || disclaimer.isBlank()
                        ? BriefContent.DEFAULT_DISCLAIMER
                        : disclaimer;
    }

    /** 既有 4 参构造（不区分 pending；兼容既有调用点与测试）。 */
    public DailyRecommendationResult(
            int status, List<TopRecommendation> topRecommend, String disclaimer, boolean fallback) {
        this(status, topRecommend, disclaimer, fallback, false);
    }
}
