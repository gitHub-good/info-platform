package com.info.platform.application.subscription;

/**
 * 标的订阅命中上下文（应用层值对象，T27）。
 *
 * <p>标的订阅（subType=2）的 {@code subKey=subjectId}，匹配时需该标的的 {@code code}（公告/新闻按 fetch 归属匹配） 与 {@code
 * industry}（政策按行业关联匹配）。FeedService 落库时解析 subjectId → Subject，组装本值对象表 {@code Map<Long, SubjectRef>}
 * 传入 {@link FeedMatcher}，使匹配器不直接依赖 {@link
 * com.info.platform.domain.aggregation.SubjectRepository}（保持匹配器纯函数、可脱离容器单测）。
 *
 * @param id 标的内部主键（subjectId）
 * @param code 标的统一代码（如 SH600519）
 * @param name 标的名称（命中原因展示用）
 * @param industry 标的行业（政策按 {@code industry ∈ relatedIndustries} 命中）
 */
public record SubjectRef(Long id, String code, String name, String industry) {}
