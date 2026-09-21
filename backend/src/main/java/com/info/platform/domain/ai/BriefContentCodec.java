package com.info.platform.domain.ai;

import java.util.List;
import java.util.Optional;

/**
 * 简报内容编解码端口（依赖倒置：领域层定义、基础设施层 {@code BriefContentParser} 实现）。
 *
 * <p>领域层纯净接口，不依赖 Jackson 等框架类型。承载 LLM 原始 content（JSON 字符串）↔ {@link BriefContent} 领域值对象的互转 +
 * 事实回链采集，使应用层 {@code AIBriefService} 只依赖本端口（守护分层：应用层不直接依赖基础设施实现，避循环依赖）。
 *
 * <p>解析兜底链（去 {@code ```json} 标记 / 取首{到末} / 容忍未知字段）由实现承担，失败返回 {@link Optional#empty()}（应用层据此置
 * status=2）。回链采集实现去重 {@code facts}/{@code keyEvents} 的 {@code sourceUrl}。
 */
public interface BriefContentCodec {

    /**
     * 解析 LLM 原始 content 为 {@link BriefContent}。
     *
     * @param rawContent LLM 返回原文（可能空/含 fence/含解释文字），可空
     * @return 解析成功→BriefContent；空/非法→empty（实现内部记 WARN）
     */
    Optional<BriefContent> parse(String rawContent);

    /** 序列化 BriefContent 为规范 JSON（落 {@code ai_brief.content}）。 */
    String writeJson(BriefContent content);

    /** 采集 facts/keyEvents 的 sourceUrl 去重后写 JSON 数组（落 {@code ai_brief.source_links}）。 */
    String writeSourceLinks(BriefContent content);

    /** 解析存储的 sourceLinks JSON 数组为 List（GET 查询还原）。 */
    List<String> readSourceLinks(String sourceLinksJson);
}
