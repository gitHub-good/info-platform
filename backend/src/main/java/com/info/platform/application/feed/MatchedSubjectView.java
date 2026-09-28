package com.info.platform.application.feed;

/**
 * 资讯条目回联标的视图（news_analysis.matched_subjects 元素，V3.1 资讯库标的增强）。
 *
 * <p>三字段 {code, name, industry} 与库内 matched_subjects JSON 值直读一致（SubjectMatcher 回写口径）； 行级「标的关联」
 * chips 与按标的筛选（subjectCode）的数据面。
 */
public record MatchedSubjectView(String code, String name, String industry) {}
