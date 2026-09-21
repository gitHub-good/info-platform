package com.info.platform.interfaces.ai;

import com.info.platform.domain.ai.TopRecommendation;
import java.util.List;

/**
 * 每日推荐响应视图（接口层，对齐技术方案 §4.1.6 {@code GET /api/v1/recommendations/daily}）。
 *
 * <p>{@code status}： {@code 1}=AI 生成 Top5； {@code 2}=规则兜底（活跃度排序，非 AI 输出）； {@code 3}=自选池空（Top5
 * 为空，提示用户添加标的）。 {@code topRecommend} 为 Top5（每只 subjectCode/subjectName/reason/rank）； {@code
 * disclaimer} 恒附「AI 生成，非投资建议」。
 *
 * @param status 状态码（1/2/3）
 * @param topRecommend Top5 推荐条目（空池时为空数组）
 * @param disclaimer 免责声明（恒附）
 */
public record DailyRecommendationView(
        int status, List<TopRecommendation> topRecommend, String disclaimer) {}
