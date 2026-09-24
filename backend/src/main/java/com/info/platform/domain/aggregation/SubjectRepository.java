package com.info.platform.domain.aggregation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 标的仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 */
public interface SubjectRepository {

    Optional<Subject> findById(Long id);

    Optional<Subject> findByCode(SubjectCode subjectCode);

    /**
     * 取首个启用标的（按 id 升序，T36 连通性测试的探针标的来源）。
     *
     * <p>各源真实外呼需要 external_codes（如东财 secid）；种子标的已带映射，取首个启用即具备代表性。无启用标的返回空（页面提示录入）。
     */
    Optional<Subject> findFirstActive();

    boolean existsByCode(SubjectCode subjectCode);

    /**
     * 按关键字模糊搜索启用标的（subjectCode/name contains，大小写不敏感），按 id 升序返回至多 {@code limit} 条。
     *
     * <p>搜索选择器数据源（体检 P1-2，解「只知数字主键」的冷启动死锁）；LIKE 通配符（%/_）由实现层转义按字面匹配。
     */
    List<Subject> searchEnabled(String keyword, int limit);

    /**
     * 批量按主键取标的（去重、按 id 升序返回，不筛状态）；不存在的主键跳过不抛错。
     *
     * <p>自选清单等小批量（≤50）批量取数端口（体检 P1-2/P3 N+1 收口方向）。
     */
    List<Subject> findAllById(Collection<Long> ids);

    /** 落库：id 为空走 INSERT 并回填主键，非空走 UPDATE（乐观锁由基础设施层处理）。 */
    Subject save(Subject subject);

    // ---- 标的池定时同步端口（T51，技术方案增补 §4.4；纯增量，既有方法与乐观锁语义不动） ----

    /**
     * 按市场 + 类型加载同步 diff 基线（该桶现存全量行，<b>不筛状态</b>——停用行也要比对名称/行业与「已停用不再计数」判定）。
     *
     * <p>diff 集合按双条件圈定：指数桶与股票桶互不触碰，板块/基金/债券（若有手工行）天然不在同步范围（范围外永不触碰）。
     */
    List<Subject> loadBucket(Market market, SubjectType subjectType);

    /**
     * 批量幂等新增（{@code INSERT OR IGNORE}，V17 先例；UNIQUE 兜底）：已存在同 subject_code 的行静默跳过。
     *
     * @return 实际插入行数（忽略行不计）
     */
    int insertIgnoreBatch(List<Subject> subjects);

    /**
     * 同步快照更新：仅写 name / industry / external_codes（<b>不碰 status / missing_streak</b>，REQ 红线「更新不碰
     * status」），updated_at 刷新、version+1。
     *
     * @return 受影响行数（0 = 代码不存在）
     */
    int updateSnapshot(
            String subjectCode, String name, String industry, Map<String, String> externalCodes);

    /** 回归清零：出现行的 missing_streak 归零（{@code WHERE missing_streak > 0}，无事可做时零写入）。 */
    int clearMissingStreak(String subjectCode);

    /**
     * 缺失确认：连续缺失计数 +1（{@code WHERE status = 1} SQL 级守卫——已停用标的不再计数）。
     *
     * @return 受影响行数（0 = 已停用/不存在）
     */
    int incrementMissingStreak(String subjectCode);

    /**
     * 阈值停用（T52，技术方案增补 §4.4 SQL ④）：连续缺失计数达 {@code threshold} 时置 status=0—— SQL 级单向守卫 {@code WHERE
     * subject_code=? AND status=1 AND missing_streak>=?}（status 只 1→0，永不复活）。
     *
     * @return 受影响行数（1 = 本轮真实翻转停用；0 = 未达阈值 / 已停用 / 不存在）
     */
    int deactivateIfMissingReached(String subjectCode, int threshold);
}
