package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubjectRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本；乐观锁由 {@code @Version} + {@code
 * OptimisticLockerInnerInterceptor}（见 {@code infrastructure.common.MyBatisPlusConfig}）守护。
 *
 * <p>T51 同步端口：批量 upsert / 计数 UPDATE 走 {@link JdbcTemplate}（技术方案增补 §4.4「JDBC batch」—— MyBatis-Plus 无
 * {@code INSERT OR IGNORE}，且手写 SQL 的 {@code version=version+1} / {@code WHERE status=1} 守卫
 * 需要语句级表达）；与 MyBatis 共享同一事务连接（Spring 事务同步），单市场事务原子性不受影响。
 */
@Repository
public class SubjectRepositoryImpl implements SubjectRepository {

    private static final Logger log = LoggerFactory.getLogger(SubjectRepositoryImpl.class);

    /** §4.4 SQL ①：批量幂等新增（INSERT OR IGNORE，UNIQUE 兜底；status=1 / missing_streak=0 / version=0）。 */
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO subject_master
              (subject_code, market, subject_type, name, external_codes, industry,
               status, missing_streak, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?, ?, 0)
            """;

    /** §4.4 SQL ②（增补 external_codes，ADR-0029）：仅名称/行业/取数键，不碰 status / missing_streak。 */
    private static final String UPDATE_SNAPSHOT_SQL =
            """
            UPDATE subject_master
               SET name = ?, industry = ?, external_codes = ?, updated_at = ?, version = version + 1
             WHERE subject_code = ?
            """;

    /** 回归清零（出现行，missing_streak > 0 才写）。 */
    private static final String CLEAR_STREAK_SQL =
            """
            UPDATE subject_master
               SET missing_streak = 0, updated_at = ?, version = version + 1
             WHERE subject_code = ? AND missing_streak > 0
            """;

    /** §4.4 SQL ③：缺失计数（仅启用标的，WHERE status=1 守卫）。 */
    private static final String INCREMENT_STREAK_SQL =
            """
            UPDATE subject_master
               SET missing_streak = missing_streak + 1, updated_at = ?, version = version + 1
             WHERE subject_code = ? AND status = 1
            """;

    /** §4.4 SQL ④：阈值停用（T52，status 单向 1→0 的 SQL 级守卫；threshold 参数化）。 */
    private static final String DEACTIVATE_IF_REACHED_SQL =
            """
            UPDATE subject_master
               SET status = 0, updated_at = ?, version = version + 1
             WHERE subject_code = ? AND status = 1 AND missing_streak >= ?
            """;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubjectMapper mapper;
    private final JdbcTemplate jdbcTemplate;

    public SubjectRepositoryImpl(SubjectMapper mapper, JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
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
    public List<Subject> searchEnabled(String keyword, int limit) {
        String pattern = "%" + escapeLike(keyword) + "%";
        List<SubjectPO> pos =
                mapper.selectList(
                        new LambdaQueryWrapper<SubjectPO>()
                                .eq(SubjectPO::getStatus, SubjectStatus.ENABLED.code())
                                .and(
                                        w ->
                                                w.apply(
                                                                "subject_code LIKE {0} ESCAPE '\\'",
                                                                pattern)
                                                        .or()
                                                        .apply(
                                                                "name LIKE {0} ESCAPE '\\'",
                                                                pattern))
                                .orderByAsc(SubjectPO::getId)
                                .last("LIMIT " + Math.max(1, limit)));
        return pos.stream().map(SubjectRepositoryImpl::toEntity).toList();
    }

    /** 转义 SQLite LIKE 通配符（%/_/\\），配合 {@code ESCAPE '\\'} 按字面匹配（用户输入不构成通配语义）。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Subject> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(distinct).stream()
                .sorted(Comparator.comparing(SubjectPO::getId))
                .map(SubjectRepositoryImpl::toEntity)
                .toList();
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

    // ---- 标的池定时同步端口（T51，技术方案增补 §4.4 关键 SQL 的 JDBC 实现） ----

    @Override
    public List<Subject> loadBucket(Market market, SubjectType subjectType) {
        List<SubjectPO> pos =
                mapper.selectList(
                        new LambdaQueryWrapper<SubjectPO>()
                                .eq(SubjectPO::getMarket, market.name())
                                .eq(SubjectPO::getSubjectType, subjectType.code()));
        return pos.stream().map(SubjectRepositoryImpl::toEntity).toList();
    }

    @Override
    @Transactional
    public int insertIgnoreBatch(List<Subject> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        List<Object[]> args =
                subjects.stream()
                        .map(
                                s ->
                                        new Object[] {
                                            s.getSubjectCode().value(),
                                            s.getMarket().name(),
                                            s.getSubjectType().code(),
                                            s.getName(),
                                            toJson(s.getExternalCodes()),
                                            s.getIndustry(),
                                            now,
                                            now
                                        })
                        .toList();
        int[] affected = jdbcTemplate.batchUpdate(INSERT_IGNORE_SQL, args);
        int inserted = 0;
        for (int rows : affected) {
            // INSERT OR IGNORE：UNIQUE 冲突行 affected=0（幂等重跑不重复计数）
            inserted += Math.max(rows, 0);
        }
        return inserted;
    }

    @Override
    public int updateSnapshot(
            String subjectCode, String name, String industry, Map<String, String> externalCodes) {
        // 仅名称/行业/取数键三列（REQ 红线：更新不碰 status / missing_streak）
        return jdbcTemplate.update(
                UPDATE_SNAPSHOT_SQL,
                name,
                industry,
                toJson(externalCodes),
                Instant.now().toString(),
                subjectCode);
    }

    @Override
    public int clearMissingStreak(String subjectCode) {
        return jdbcTemplate.update(CLEAR_STREAK_SQL, Instant.now().toString(), subjectCode);
    }

    @Override
    public int incrementMissingStreak(String subjectCode) {
        // WHERE status=1：已停用标的不再计数（SQL 级守卫）
        return jdbcTemplate.update(INCREMENT_STREAK_SQL, Instant.now().toString(), subjectCode);
    }

    @Override
    public int deactivateIfMissingReached(String subjectCode, int threshold) {
        // WHERE status=1 AND missing_streak>=threshold：未达阈值/已停用/不存在均零受影响（status 只 1→0）
        return jdbcTemplate.update(
                DEACTIVATE_IF_REACHED_SQL, Instant.now().toString(), subjectCode, threshold);
    }

    /** external_codes JSON 序列化（对齐 JacksonTypeHandler 存储形态）。 */
    private static String toJson(Map<String, String> externalCodes) {
        if (externalCodes == null || externalCodes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(externalCodes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("external_codes 序列化失败", e);
        }
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
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()),
                po.getMissingStreak() == null ? 0 : po.getMissingStreak());
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
