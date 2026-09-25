package com.info.platform.domain.feed;

import java.time.Instant;

/**
 * 源轮询运行态值对象（{@code source_poll_state} 表，1:1 info_source，M13 T100）。
 *
 * <p>配置行（info_source，低频编辑、页面域）与高频运行态（本表，调度器每轮写）分离。 到期判定 {@code now ≥ max(next_due_at,
 * backoff_until)}；{@code consecutive_failures} 驱动指数退避（成功清零）。
 *
 * @param sourceId = info_source.id（1:1，无自增主键）
 * @param lastAttemptAt 最近尝试时刻
 * @param lastSuccessAt 最近成功时刻（断流 gap 基准：{@code gap > 3×interval} 触发深翻补抓）
 * @param nextDueAt 下次应抓时刻（含错峰偏移）
 * @param cursorValue 增量游标值（ID 数值串 / ISO 时间 / null = NONE）
 * @param cursorUpdatedAt 游标推进时刻
 * @param consecutiveFailures 连续失败数（成功清零）
 * @param backoffUntil 退避截止（null = 不在静默期）
 * @param lastDurationMillis 最近一轮耗时毫秒
 * @param lastRoundDetail 最近轮明细（ADR-0036 段式惯例）
 * @param lastError 最近失败摘要
 */
public record SourcePollState(
        Long sourceId,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        Instant nextDueAt,
        String cursorValue,
        Instant cursorUpdatedAt,
        int consecutiveFailures,
        Instant backoffUntil,
        Long lastDurationMillis,
        String lastRoundDetail,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {}
