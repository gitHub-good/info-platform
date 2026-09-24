package com.info.platform.application.aggregation;

import java.util.Map;

/**
 * 批量标的行情行（体检 P1-2，{@code GET /subjects/quotes?ids=...} 响应元素）。
 *
 * <p>标的摘要字段与 detail 响应的 subject 投影一致（多出 id）；{@code quote} 为行情分区数据， 任一标的取数失败/超时/源不适用该类型时为
 * {@code null}（降级不阻断，前端显示「—」）。
 */
public record SubjectQuote(
        Long id,
        String subjectCode,
        String name,
        String market,
        int type,
        String industry,
        Map<String, Object> quote) {}
