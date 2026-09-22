package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RuntimeConfigRepository} 端口的 SQLite/MyBatis-Plus 实现（T34）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本。 upsert：新键插入（沿用实体时间戳）；既有键整体替换 config_value 并以实体 updated_at
 * 落库，保留 created_at 与既有 description（实体未携带说明时不动旧值）。
 */
@Repository
public class RuntimeConfigRepositoryImpl implements RuntimeConfigRepository {

    private final RuntimeConfigMapper mapper;

    public RuntimeConfigRepositoryImpl(RuntimeConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<RuntimeConfig> findByKey(String configKey) {
        RuntimeConfigPO po =
                mapper.selectOne(
                        new LambdaQueryWrapper<RuntimeConfigPO>()
                                .eq(RuntimeConfigPO::getConfigKey, configKey));
        return Optional.ofNullable(po).map(RuntimeConfigRepositoryImpl::toEntity);
    }

    @Override
    public List<RuntimeConfig> findAll() {
        return mapper.selectList(null).stream().map(RuntimeConfigRepositoryImpl::toEntity).toList();
    }

    @Override
    @Transactional
    public RuntimeConfig save(RuntimeConfig config) {
        RuntimeConfigPO existing =
                mapper.selectOne(
                        new LambdaQueryWrapper<RuntimeConfigPO>()
                                .eq(RuntimeConfigPO::getConfigKey, config.getConfigKey()));
        if (existing == null) {
            RuntimeConfigPO po = toPO(config);
            po.setCreatedAt(config.getCreatedAt().toString());
            po.setUpdatedAt(config.getUpdatedAt().toString());
            mapper.insert(po);
            return toEntity(po);
        }
        existing.setConfigValue(config.getConfigValue());
        if (config.getDescription() != null) {
            existing.setDescription(config.getDescription());
        }
        existing.setUpdatedAt(config.getUpdatedAt().toString());
        mapper.updateById(existing);
        return toEntity(existing);
    }

    private static RuntimeConfig toEntity(RuntimeConfigPO po) {
        return RuntimeConfig.reconstruct(
                po.getConfigKey(),
                po.getConfigValue(),
                po.getDescription(),
                Instant.parse(po.getCreatedAt()),
                Instant.parse(po.getUpdatedAt()));
    }

    private static RuntimeConfigPO toPO(RuntimeConfig config) {
        RuntimeConfigPO po = new RuntimeConfigPO();
        po.setConfigKey(config.getConfigKey());
        po.setConfigValue(config.getConfigValue());
        po.setDescription(config.getDescription());
        return po;
    }
}
