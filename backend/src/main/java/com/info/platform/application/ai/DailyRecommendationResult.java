package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.TopRecommendation;
import java.util.List;

/**
 * 每日推荐生成结果（应用层返回值，对齐技术方案 §4.1.6 {@code GET /recommendations/daily}）。
 *
 * <p>{@code status}： {@code 1}=AI 生成成功取 Top5（DONE）； {@code 2}=规则兜底（LLM 失败/超时/输出空 Top5 时降级为活跃度排序，对齐
 * Spike-2 §11.5 + 技术方案 §5 降级预案）； {@code 3}=自选池空（无活跃清单项，返回空 Top5 + 提示）。 {@code topRecommend} 为排序后的
 * Top5（按 rank 升序，规则兜底时 rank=1..5）； {@code disclaimer} 恒附「AI 生成，非投资建议」（§4.1.4）； {@code fallback}
 * 标识是否走了规则兜底（前端可标「规则排序」角标）。
 *
 * @param status 状态码（1/2/3）
 * @param topRecommend Top5 推荐（空池时为空列表）
 * @param disclaimer 免责声明（恒附）
 * @param fallback 是否规则兜底（true=活跃度排序，非 AI 输出）
 */
public record DailyRecommendationResult(
        int status, List<TopRecommendation> topRecommend, String disclaimer, boolean fallback) {

    /** 状态码：AI 生成成功取 Top5。 */
    public static final int STATUS_DONE = 1;

    /** 状态码：规则兜底（活跃度排序）。 */
    public static final int STATUS_FALLBACK = 2;

    /** 状态码：自选池空。 */
    public static final int STATUS_EMPTY = 3;

    /** 构造结果，disclaimer 缺失兜底为全局免责声明。 */
    public DailyRecommendationResult {
        topRecommend = topRecommend == null ? List.of() : List.copyOf(topRecommend);
        disclaimer =
                disclaimer == null || disclaimer.isBlank()
                        ? BriefContent.DEFAULT_DISCLAIMER
                        : disclaimer;
    }
}
