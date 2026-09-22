package com.info.platform.domain.common;

import java.util.List;
import java.util.Optional;

/**
 * runtime_config 表的仓储端口（领域层，T34）。
 *
 * <p>SQLite/MyBatis-Plus 实现见基础设施层 {@code RuntimeConfigRepositoryImpl}。 写语义为 upsert：新键插入；既有键
 * <b>整体替换</b> {@code config_value} 并刷新 {@code updated_at}（保留 {@code created_at} 与 {@code
 * description}）——对齐 方案 §4.1「写时整体替换、不设 version 列，并发防呆用 expectedUpdatedAt 在应用层比对」。
 */
public interface RuntimeConfigRepository {

    /** 按键查单条；不存在返回空。 */
    Optional<RuntimeConfig> findByKey(String configKey);

    /** 全量读取（启动加载快照 / 写后刷新快照用；行数 ≈ 13，量级无忧）。 */
    List<RuntimeConfig> findAll();

    /**
     * 新键插入（created_at = updated_at = 实体时间）；既有键整体替换值与 updated_at，保留 created_at 与既有 description（实体
     * description 为 null 时沿用旧值）。
     *
     * @return 落库后的实体（含持久化后的时间戳）
     */
    RuntimeConfig save(RuntimeConfig config);
}
