package com.info.platform.application.policy;

/**
 * 政策条目回联标的视图（news_analysis.matched_subjects 元素，V2.3-M23 T201）。
 *
 * <p>§4.1 契约：{code, name, industry}——与库内 matched_subjects JSON 值直读一致（REQ 故事 2 场景 4）， 行级「标的关联」
 * 徽章与跳详情入口的数据面。
 */
public record MatchedSubjectView(String code, String name, String industry) {}
