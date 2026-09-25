package com.info.platform.domain.feed;

import java.time.Instant;

/**
 * 资讯源失败旁路事件值对象（M14 T114，{@code data_source_event} 行的读侧投影）。
 *
 * <p>写侧沿用 M13 {@code FeedEventRecorder}（首败与每 10 次节流，{@code info:{sourceCode}} 前缀）；
 * 本值为大盘「近期失败列表」的读侧载体—— 仓储实现剥前缀后返回裸 sourceCode。
 *
 * @param sourceCode 源稳定代码（裸值，无 info: 前缀）
 * @param occurredAt 事件记录时刻
 * @param detail 失败摘要（含 consecutiveFailures=n 前缀段）
 */
public record FeedFailureEvent(String sourceCode, Instant occurredAt, String detail) {}
