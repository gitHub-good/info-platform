package com.info.platform.domain.policy;

import java.util.List;
import java.util.Optional;

/**
 * 政策条目仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 */
public interface PolicyRepository {

    /** 批量落库（id 为空走 INSERT 并回填主键）。 */
    List<PolicyItem> saveAll(List<PolicyItem> items);

    /** source_url 去重（同一条政策不重复入库）。 */
    boolean existsBySourceUrl(String sourceUrl);

    Optional<PolicyItem> findById(Long id);

    /**
     * 游标分页查询政策时事流（实现 findRecent + byIndustry + findByCursor 三能力合一）。
     *
     * <p><b>分页契约</b>：newest-first（id DESC）；首页 {@code cursor=null/0} 取最新 limit 条；翻页传上一页最后一条
     * id（{@code WHERE id < cursor}）续取 limit 条。 days 天内（{@code published_at >= today - days}）+ 行业过滤
     * （{@code industry} 为 null/blank 则不过滤，按 related_industries JSON 文本 LIKE 匹配）。LIMIT 防深分页（对齐 §4.4
     * 游标分页）。
     *
     * @param days 时间窗（天）；&lt;=0 取默认 7，&gt;90 截 90
     * @param industry 行业过滤；null/blank 不过滤
     * @param cursor 游标（上一页末条 id）；null/0 表首页
     * @param limit 页大小
     */
    List<PolicyItem> findRecent(int days, String industry, Long cursor, int limit);

    /**
     * 查询近期 {@code ai_tendency=0}（未判）的政策条目（T28 政策倾向批量 Job 扫描用）。
     *
     * <p>newest-first（id DESC），days 天内，{@code WHERE ai_tendency = 0 LIMIT limit}。区别于 {@link
     * #findRecent} （含已判）：Job 只处理未判条目，避免已判条目占满 LIMIT 窗口导致漏扫（个人量级数据小，但显式过滤更稳）。
     *
     * @param days 时间窗（天）；&lt;=0 取默认 7，&gt;90 截 90
     * @param limit 单轮扫描上限
     */
    List<PolicyItem> findRecentUnjudged(int days, int limit);

    /**
     * 更新政策条目倾向（T28 填 {@code ai_tendency}，1利好/2利空/3中性）。
     *
     * <p>仅写 {@code ai_tendency} + {@code updated_at}（{@code WHERE id=?}）；不触碰 title/source 等不可变字段。
     * 影响 0 行（id 不存在）静默忽略（Job 容错，调用方据返回值记日志）。
     *
     * @param id 政策条目 id
     * @param tendency 倾向；传 {@link AiTendency#UNJUDGED} 视为无操作（直接返 false）
     * @return 是否实际更新了 1 行
     */
    boolean updateAiTendency(Long id, AiTendency tendency);
}
