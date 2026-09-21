package com.info.platform.domain.ai;

/**
 * AI 简报关键事件条目（值对象，对齐 Spike-2 §5.1 {@code keyEvents[]} Schema）。
 *
 * <p>领域层纯净（纯 JDK record）。每条事件含描述、影响方向（利好/利空/中性）、判断理由与原文链接； {@code sourceUrl} 供事实回链（回链率 100%
 * 验收）。不参与幻觉数值校验（定性判断，Spike-2 §6「定性判断不校验」）。
 *
 * @param event 事件描述
 * @param impact 影响方向（利好/利空/中性）
 * @param reason 判断理由
 * @param sourceUrl 原文链接（事实回链）
 */
public record BriefKeyEvent(String event, String impact, String reason, String sourceUrl) {}
