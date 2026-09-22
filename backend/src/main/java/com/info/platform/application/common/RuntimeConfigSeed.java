package com.info.platform.application.common;

/**
 * 运行时配置种子（应用层值对象，T34）。
 *
 * <p>启动 seed-if-absent 从 yml/代码缺省导入：仅当 DB 中该键<b>不存在</b>时插入，已存在不动（DB 为权威，改过 yml 不再覆盖页面值）。
 *
 * @param configKey 域前缀键
 * @param json JSON 文档文本（须为合法 JSON 对象）
 * @param description 人读说明（种子导入时写入 description 列）
 */
public record RuntimeConfigSeed(String configKey, String json, String description) {}
