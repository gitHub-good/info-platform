package com.info.platform.domain.analysis;

import java.util.List;
import java.util.Optional;

/**
 * 行业周报仓储端口（{@code industry_weekly_report}，M17 T145，V29）。领域层纯净接口：写路径 UPSERT（UNIQUE(week_start)——FAILED
 * 重生成与降级覆盖收敛为一行）；读路径供周报列表（游标分页）/详情（30084 语义由应用层把守）与周窗事件区间取数（事件主键归并——event_item
 * 一行一主线，跨日去重天然成立）。
 */
public interface WeeklyReportRepository {

    /** 按周锚点取周报（详情/retry 校验/幂等判断）。 */
    Optional<IndustryWeeklyReport> findByWeekStart(String weekStart);

    /**
     * 周报列表页：week_start DESC 等价 id DESC 游标分页（每周一锚一行单调）。
     *
     * @param beforeId id 游标（id &lt; beforeId；null = 首页）
     * @param limit 页大小（应用层校验 1~50）
     */
    List<IndustryWeeklyReport> findPage(Long beforeId, int limit);

    /**
     * UPSERT 周报（by week_start 整行替换：FAILED 重生成 / 重试覆盖）。
     *
     * @return 落库后的实体（回填 id/时间戳）
     */
    IndustryWeeklyReport upsert(IndustryWeeklyReport report);

    /**
     * 周窗全部结构化事件（事件回顾与政策动向数据面，主键归并——周窗 [from, to] 内 event_item 一行一主线）： 重要度降序（HIGH &gt;
     * MEDIUM &gt; LOW）、id 升序稳定序。
     *
     * @param eventDateFrom Asia/Shanghai yyyy-MM-dd（含，周一锚）
     * @param eventDateTo Asia/Shanghai yyyy-MM-dd（含，生成日）
     * @param cap 取数上限（防御性）
     */
    List<DailyReportRepository.ReportEvent> findEventsBetween(
            String eventDateFrom, String eventDateTo, int cap);
}
