package com.info.platform.domain.aggregation;

import java.util.Collection;
import java.util.List;
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
}
