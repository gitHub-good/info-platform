package com.info.platform.domain.policy;

/**
 * 政策列表组合过滤条件（M9 页码模式，纯 JDK record）。
 *
 * <p>页码模式查询条件载体：{@code days + industry + keyword}（T60/T62）任意组合、全部 AND 语义；{@code findPage}/{@code
 * countByFilter} 同一 filter 保证页数据与计数同口径。
 *
 * @param days 时间窗（天）；&lt;=0 取默认 7、&gt;90 截 90（实现层既有 clamp 语义）
 * @param industry 行业过滤；null/blank 不过滤（按 related_industries JSON 文本 LIKE 匹配）
 * @param keyword 标题/摘要关键词；null/blank 不过滤（接口层已校验长度 2~64 并 trim；LIKE 转义见实现层）
 */
public record PolicyListFilter(int days, String industry, String keyword) {}
