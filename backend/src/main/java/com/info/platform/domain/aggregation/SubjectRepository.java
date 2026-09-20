package com.info.platform.domain.aggregation;

import java.util.Optional;

/**
 * 标的仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 */
public interface SubjectRepository {

    Optional<Subject> findById(Long id);

    Optional<Subject> findByCode(SubjectCode subjectCode);

    boolean existsByCode(SubjectCode subjectCode);

    /** 落库：id 为空走 INSERT 并回填主键，非空走 UPDATE（乐观锁由基础设施层处理）。 */
    Subject save(Subject subject);
}
