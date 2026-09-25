package com.info.platform.interfaces.feed;

import jakarta.annotation.Nullable;

/**
 * 编辑源请求（M13 T105，方案 §4.5 PATCH /info-sources/{id}）：部分字段合并，下一 tick 现读热生效； adapterType 实体不可变——携带且异值即
 * 30072（预置源同口径，前端类型分段禁切换为双保险）。
 */
public record UpdateInfoSourceRequest(
        @Nullable String name,
        @Nullable String category,
        @Nullable String adapterType,
        @Nullable String endpoint,
        @Nullable Integer intervalMinutes,
        @Nullable Boolean enabled,
        @Nullable SourceConfigPayload config) {}
