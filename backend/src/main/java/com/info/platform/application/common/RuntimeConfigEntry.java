package com.info.platform.application.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 运行时配置快照的单条目（应用层只读视图，T34）。
 *
 * <p>同时持有原始 JSON 文本（回显/写路径原样保留）与解析后的 {@link JsonNode}（消费点用时零解析开销）。 {@code document}
 * 为快照内共享节点，调用方<b>只读不改</b>（快照不可变约定）。
 *
 * @param configKey 域前缀键（如 "llm.global"）
 * @param json JSON 文档原文
 * @param document 解析后的 JSON 树（调用方只读）
 * @param description 人读说明
 * @param updatedAt 最后更新时刻（expectedUpdatedAt 并发防呆比对口径）
 */
public record RuntimeConfigEntry(
        String configKey, String json, JsonNode document, String description, Instant updatedAt) {}
