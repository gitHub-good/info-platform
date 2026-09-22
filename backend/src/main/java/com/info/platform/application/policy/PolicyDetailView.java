package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策详情视图（GET /api/v1/policies/{id}）。
 *
 * <p>对齐 §4.1.5：政策字段 + 关联自选标的（relatedSubjects，按 relatedIndustries 匹配当前用户 watchlist）+ ai_tendency
 * （T24 全 0 未判，T28 AI 倾向判断填 1利好/2利空/3中性）。
 */
public record PolicyDetailView(
        Long id,
        String title,
        String source,
        String publishedAt,
        String summary,
        List<String> relatedIndustries,
        String sourceUrl,
        int aiTendency,
        List<RelatedSubjectView> relatedSubjects) {}
