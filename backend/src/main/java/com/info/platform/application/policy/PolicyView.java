package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表项视图（GET /api/v1/policies 每条，V2.3-M23 T201 数据面切换：policy_item → news_item 政策类条目）。
 *
 * <p>§4.1 契约：id=news_item.id、标题/摘要/直链、源展示列（sourceCode/sourceName）、publishedAt ISO-8601 UTC、 L1
 * 归类产物（mainCategory/subIndustry）与回联标的（matchedSubjects，行级「标的关联」呈现依据）。 {@code
 * relatedIndustries}/{@code aiTendency} 字段随旧数据面移除。
 */
public record PolicyView(
        Long id,
        String title,
        String summary,
        String url,
        String sourceCode,
        String sourceName,
        String publishedAt,
        String mainCategory,
        String subIndustry,
        List<MatchedSubjectView> matchedSubjects) {}
