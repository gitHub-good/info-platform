package com.info.platform.interfaces.feed;

import jakarta.annotation.Nullable;

/**
 * 新增通用源请求（M13 T105，方案 §4.5 POST /info-sources）：sourceCode 后端从名称生成（编排者裁定）， adapterType 仅开放 rss /
 * json_api（蓝图裁决 1）。
 *
 * @param adapterType 线格式（rss / json_api）
 * @param enabled 缺省 true（保存即启用——「保存并启用」，首抓 next_due_at = now）
 */
public record CreateInfoSourceRequest(
        String name,
        String category,
        String adapterType,
        String endpoint,
        Integer intervalMinutes,
        @Nullable Boolean enabled,
        SourceConfigPayload config) {}
