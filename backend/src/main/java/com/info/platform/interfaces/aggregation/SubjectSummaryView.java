package com.info.platform.interfaces.aggregation;

import com.info.platform.domain.aggregation.Subject;

/**
 * 标的摘要视图（{@code GET /subjects/by-code/{code}} 响应 data，P0-1）。
 *
 * <p>前端路由携带内部统一代码（如 SH600519），而聚合详情接口以数字主键寻址； 此端点完成代码 → 主键解析，返回主键 + 头部展示字段（与 detail 响应的 subject
 * 投影一致，多出 id）。
 */
public record SubjectSummaryView(
        Long id, String subjectCode, String name, String market, int type, String industry) {

    /** 领域实体 → 接口视图（market 存枚举名文本，与 subject_master 列一致）。 */
    public static SubjectSummaryView from(Subject subject) {
        return new SubjectSummaryView(
                subject.getId(),
                subject.getSubjectCode().value(),
                subject.getName(),
                subject.getMarket().name(),
                subject.getSubjectType().code(),
                subject.getIndustry());
    }
}
