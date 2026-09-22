package com.info.platform.application.policy;

/**
 * 政策详情关联自选标的视图（按 relatedIndustries 匹配当前用户 watchlist 含该行业的标的）。
 *
 * <p>对齐 PRD 故事 4 场景 2：点击政策条目 → 展示该行业下用户自选标的列表。
 */
public record RelatedSubjectView(String subjectCode, String subjectName, String industry) {}
