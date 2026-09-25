package com.info.platform.application.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 公告分区子端点响应视图（M12 T90，方案 §4.1.1 / ADR-0037 决策 1）。
 *
 * <p>条目字段与聚合路径 {@code SubjectDetail.announcements[]} 逐字段一致（title/publishedAt/category/url——同一
 * adapter 同一 itemMapping 产出）；page/size 如实回显（越界页同样 200 + 空列表 + total 如实）； 巨潮生效（东财失败降级）时
 * {@code paginationSupported=false} + {@code total=null}，{@code moreUrl} 恒透出（源站列表出口）。 失败/超时 → 200 +
 * {@code sourceStatus: failed/timeout} + 空列表（分区降级不阻断，前端保留当前内容 + 重试）。
 *
 * <p>归 application 层（同 {@code PolicyPagedView} 先例——{@code SubjectSectionPageService} 直返、接口层零映射）；
 * 方案附录 A 原列 interfaces 层，因 application→interfaces 依赖会与既有 interfaces→application 成环
 * （LayeredArchitectureTest 层切片无环约束）而落位 application，JSON 契约不变。
 *
 * @param items 当前页条目（可为空——越界页/降级）
 * @param page 当前页码（回显请求值）
 * @param size 页大小（回显：显式请求值或运行时 announcePageSize 缺省）
 * @param total 源公告总数（东财 total_hits 透出）；巨潮生效/降级为 null
 * @param paginationSupported 本次请求实际生效 provider 是否支持翻页（东财 true / 巨潮 false）
 * @param moreUrl 源站公告列表出口（按生效 provider 构造；降级无生效 provider 为 null）
 * @param sourceStatus ok | missing | failed | timeout（与聚合口径同源同码）
 * @param source 生效 provider 标注（巨潮时「东方财富公告→巨潮资讯备选」）
 */
public record AnnouncementPageView(
        List<Map<String, Object>> items,
        int page,
        int size,
        Long total,
        boolean paginationSupported,
        String moreUrl,
        String sourceStatus,
        String source) {}
