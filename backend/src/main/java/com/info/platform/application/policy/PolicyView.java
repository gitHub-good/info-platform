package com.info.platform.application.policy;

import java.util.List;

/**
 * 政策列表项视图（GET /api/v1/policies 每条）。
 *
 * <p>对齐 §4.1.5：标题/来源/时间/摘要/关联行业标签。publishedAt 为 ISO-8601 日期文本（yyyy-MM-dd，gov.cn 列表页粒度）。
 */
public record PolicyView(
        Long id,
        String title,
        String source,
        String publishedAt,
        String summary,
        List<String> relatedIndustries) {}
