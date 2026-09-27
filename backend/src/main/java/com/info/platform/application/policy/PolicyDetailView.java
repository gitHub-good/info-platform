package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策详情视图（GET /api/v1/policies/{id}，id = news_item.id，V2.3-M23 T201 数据面切换）。
 *
 * <p>§4.2 契约：news 背书条目（标题/摘要/直链/源展示列/publishedAt ISO UTC）+ L1 归类产物 + 回联标的 （matchedSubjects，库内值直读）+
 * relatedEvents（event_item WHERE news_id=:id，UNIQUE 至多一条； direction 即 ai_tendency
 * 退役后的倾向承接面）。无关联事件时空数组；{@code aiTendency} 字段删除。
 */
public record PolicyDetailView(
        Long id,
        String title,
        String summary,
        String url,
        String sourceCode,
        String sourceName,
        String publishedAt,
        String mainCategory,
        String subIndustry,
        List<MatchedSubjectView> matchedSubjects,
        List<RelatedEventView> relatedEvents) {}
