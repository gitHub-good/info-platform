package com.info.platform.domain.feed;

import java.util.Locale;

/**
 * 源级 AI 管道排除档位（M15 T125，REQ 拍板五-1 / 方案 §4.4）：{@code info_source.config.aiExclusion} 字段值域。
 *
 * <ul>
 *   <li>{@link #NONE}：缺省——全管道正常参与；
 *   <li>{@link #L2}：深度分析排除——条目照常 L0/L1 归类、照常计热度资讯量，不产事件不进事件流（21 财经条款承载主档）；
 *   <li>{@link #ALL}：全管道排除——L0 不建 analysis 行、L1 不归类（页面隐藏入口，变更控制专用）。
 * </ul>
 */
public enum AiExclusion {
    NONE,
    L2,
    ALL;

    /** 线格式解析（大小写不敏感；null/空白/未知值返回 null——由校验器给 30072 字段级提示）。 */
    public static AiExclusion fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AiExclusion level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return null;
    }
}
