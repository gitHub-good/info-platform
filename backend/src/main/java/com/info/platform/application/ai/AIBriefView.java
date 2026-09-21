package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefContent;
import java.util.List;

/**
 * AI 简报查询响应 DTO（应用层，对齐技术方案 §4.1.4 GET {@code /ai-briefs/{taskId}} 响应）。
 *
 * <p>{@code status} 为数值码（0 处理中 / 1 完成 / 2 失败 / 3 待核实）； {@code content} 为结构化简报（仅 {@code status=1/3}
 * 有可展示内容，{@code 0/2} 为 null）； {@code sourceLinks} 为事实回链 URL 数组； {@code disclaimer} 恒附「AI 生成，非投资建议」
 * （§4.1.4 免责声明）。
 *
 * @param status 状态码
 * @param content 结构化简报（处理中/失败时为 null）
 * @param sourceLinks 事实回链 URL（处理中/失败时为 null）
 * @param disclaimer 免责声明（恒附）
 */
public record AIBriefView(
        int status, BriefContent content, List<String> sourceLinks, String disclaimer) {}
