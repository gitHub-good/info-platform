package com.info.platform.infrastructure.feed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link InfoSourceRepository} 端口的 SQLite 实现（M13 T100）。
 *
 * <p>查询/保存走 MyBatis-Plus；{@link #insertIfAbsent} 走 {@link JdbcTemplate} 手写 {@code INSERT OR IGNORE}
 * （MyBatis-Plus 无该语义；UNIQUE(source_code) 兜底，与 SubjectRepository 同惯例）。 PO↔Entity 转换集中于此；时间戳整秒
 * ISO-8601 文本。
 */
@Repository
public class InfoSourceRepositoryImpl implements InfoSourceRepository {

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO info_source
              (source_code, name, category, adapter_type, adapter_ref, endpoint, config,
               interval_minutes, enabled, is_preset, deleted, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """;

    private final InfoSourceMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final SourceConfigCodec codec = new SourceConfigCodec();

    public InfoSourceRepositoryImpl(InfoSourceMapper mapper, JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<InfoSource> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toEntity);
    }

    @Override
    public Optional<InfoSource> findBySourceCode(String sourceCode) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                new LambdaQueryWrapper<InfoSourcePO>()
                                        .eq(InfoSourcePO::getSourceCode, sourceCode)))
                .map(this::toEntity);
    }

    @Override
    public List<InfoSource> findActive() {
        // 每 tick 现读 = 配置热生效（enabled=1 AND deleted=0）
        return mapper
                .selectList(
                        new LambdaQueryWrapper<InfoSourcePO>()
                                .eq(InfoSourcePO::getEnabled, 1)
                                .eq(InfoSourcePO::getDeleted, 0)
                                .orderByAsc(InfoSourcePO::getId))
                .stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<InfoSource> findAll() {
        return mapper.selectList(
                        new LambdaQueryWrapper<InfoSourcePO>().orderByAsc(InfoSourcePO::getId))
                .stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public InfoSource save(InfoSource source) {
        if (source.getId() == null) {
            return insert(source);
        }
        return update(source);
    }

    private InfoSource insert(InfoSource source) {
        Instant now = Instant.now();
        InfoSourcePO po = toPO(source);
        po.setCreatedAt(now.toString());
        po.setUpdatedAt(now.toString());
        mapper.insert(po);
        source.assignPersisted(po.getId(), Instant.parse(po.getCreatedAt()), Instant.parse(po.getUpdatedAt()));
        return source;
    }

    private InfoSource update(InfoSource source) {
        Instant now = Instant.now();
        InfoSourcePO po = toPO(source);
        po.setUpdatedAt(now.toString());
        po.setCreatedAt(
                source.getCreatedAt() == null ? now.toString() : source.getCreatedAt().toString());
        mapper.updateById(po);
        source.assignPersisted(po.getId(), Instant.parse(po.getCreatedAt()), Instant.parse(po.getUpdatedAt()));
        return source;
    }

    @Override
    public boolean insertIfAbsent(InfoSource source) {
        Instant now = Instant.now();
        int inserted =
                jdbcTemplate.update(
                        INSERT_IGNORE_SQL,
                        source.getSourceCode(),
                        source.getName(),
                        source.getCategory(),
                        source.getAdapterType().wireCode(),
                        source.getAdapterRef(),
                        source.getEndpoint(),
                        codec.write(source.getConfig()),
                        source.getIntervalMinutes(),
                        source.isEnabled() ? 1 : 0,
                        source.isPreset() ? 1 : 0,
                        now.toString(),
                        now.toString());
        if (inserted > 0) {
            source.assignPersisted(null, now, now);
            // 回填自增 id（INSERT OR IGNORE 拿不到主键，按唯一键回查）
            findBySourceCode(source.getSourceCode())
                    .ifPresent(persisted -> source.assignPersisted(persisted.getId(), persisted.getCreatedAt(), persisted.getUpdatedAt()));
        }
        return inserted > 0;
    }

    private InfoSource toEntity(InfoSourcePO po) {
        return InfoSource.reconstruct(
                po.getId(),
                po.getSourceCode(),
                po.getName(),
                po.getCategory(),
                AdapterType.from(po.getAdapterType()),
                po.getAdapterRef(),
                po.getEndpoint(),
                codec.parse(po.getConfig()),
                po.getIntervalMinutes() == null ? 15 : po.getIntervalMinutes(),
                po.getEnabled() != null && po.getEnabled() == 1,
                po.getIsPreset() != null && po.getIsPreset() == 1,
                po.getDeleted() != null && po.getDeleted() == 1,
                parseInstant(po.getCreatedAt()),
                parseInstant(po.getUpdatedAt()));
    }

    private InfoSourcePO toPO(InfoSource source) {
        InfoSourcePO po = new InfoSourcePO();
        po.setId(source.getId());
        po.setSourceCode(source.getSourceCode());
        po.setName(source.getName());
        po.setCategory(source.getCategory());
        po.setAdapterType(source.getAdapterType().wireCode());
        po.setAdapterRef(source.getAdapterRef());
        po.setEndpoint(source.getEndpoint());
        po.setConfig(codec.write(source.getConfig()));
        po.setIntervalMinutes(source.getIntervalMinutes());
        po.setEnabled(source.isEnabled() ? 1 : 0);
        po.setIsPreset(source.isPreset() ? 1 : 0);
        po.setDeleted(source.isDeleted() ? 1 : 0);
        return po;
    }

    private static Instant parseInstant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
