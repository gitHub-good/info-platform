package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubjectRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本；乐观锁由 {@code @Version} + {@code
 * OptimisticLockerInnerInterceptor}（见 {@code infrastructure.common.MyBatisPlusConfig}）守护。
 */
@Repository
public class SubjectRepositoryImpl implements SubjectRepository {

    private static final Logger log = LoggerFactory.getLogger(SubjectRepositoryImpl.class);

    private final SubjectMapper mapper;

    public SubjectRepositoryImpl(SubjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Subject> findById(Long id) {
        SubjectPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(SubjectRepositoryImpl::toEntity);
    }

    @Override
    public Optional<Subject> findByCode(SubjectCode subjectCode) {
        SubjectPO po =
                mapper.selectOne(
                        new LambdaQueryWrapper<SubjectPO>()
                                .eq(SubjectPO::getSubjectCode, subjectCode.value()));
        return Optional.ofNullable(po).map(SubjectRepositoryImpl::toEntity);
    }

    @Override
    public Optional<Subject> findFirstActive() {
        SubjectPO po =
                mapper.selectOne(
                        new LambdaQueryWrapper<SubjectPO>()
                                .eq(SubjectPO::getStatus, SubjectStatus.ENABLED.code())
                                .orderByAsc(SubjectPO::getId)
                                .last("LIMIT 1"));
        return Optional.ofNullable(po).map(SubjectRepositoryImpl::toEntity);
    }

    @Override
    public boolean existsByCode(SubjectCode subjectCode) {
        return mapper.exists(
                new LambdaQueryWrapper<SubjectPO>()
                        .eq(SubjectPO::getSubjectCode, subjectCode.value()));
    }

    @Override
    @Transactional
    public Subject save(Subject subject) {
        SubjectPO po = toPO(subject);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getVersion() == null) {
                po.setVersion(0);
            }
            mapper.insert(po);
            log.info("新增标的: id={}, subjectCode={}", po.getId(), po.getSubjectCode());
        } else {
            po.setUpdatedAt(now);
            mapper.updateById(po);
            log.info(
                    "更新标的: id={}, subjectCode={}, version={}",
                    po.getId(),
                    po.getSubjectCode(),
                    po.getVersion());
        }
        return toEntity(po);
    }

    private static Subject toEntity(SubjectPO po) {
        return Subject.reconstruct(
                po.getId(),
                SubjectCode.of(po.getSubjectCode()),
                Market.fromName(po.getMarket()),
                SubjectType.fromCode(po.getSubjectType()),
                po.getName(),
                po.getExternalCodes(),
                po.getIndustry(),
                SubjectStatus.fromCode(po.getStatus()),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static SubjectPO toPO(Subject subject) {
        SubjectPO po = new SubjectPO();
        po.setId(subject.getId());
        po.setSubjectCode(subject.getSubjectCode().value());
        po.setMarket(subject.getMarket().name());
        po.setSubjectType(subject.getSubjectType().code());
        po.setName(subject.getName());
        po.setExternalCodes(subject.getExternalCodes());
        po.setIndustry(subject.getIndustry());
        po.setStatus(subject.getStatus().code());
        po.setVersion((int) subject.getVersion());
        po.setCreatedAt(subject.getCreatedAt() == null ? null : subject.getCreatedAt().toString());
        po.setUpdatedAt(subject.getUpdatedAt() == null ? null : subject.getUpdatedAt().toString());
        return po;
    }
}
