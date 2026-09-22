package com.info.platform.application.common;

import java.time.Instant;

/**
 * 运行时配置变更事件（Spring 事件，T34 / ADR-0017）。
 *
 * <p>由 {@code RuntimeConfigService.write} 在「校验 → 落库 → 换快照」成功后发布。 消费点热生效<b>不依赖</b>本事件
 * （快照整体替换、用时读取即热）；事件仅用于需要「重绑」的场景（如 T37 JobScheduler 取消旧调度重注册）。
 *
 * <p>只携带键名与更新时刻，不携带配置值——key 相关键不落值，其余值可经读取接口获取。
 *
 * @param configKey 变更的配置键
 * @param updatedAt 新值落库时刻
 */
public record RuntimeConfigChangedEvent(String configKey, Instant updatedAt) {}
