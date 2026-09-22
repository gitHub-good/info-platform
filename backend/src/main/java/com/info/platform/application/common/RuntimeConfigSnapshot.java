package com.info.platform.application.common;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时配置内存快照（不可变，T34 / ADR-0017）。
 *
 * <p>全量键值一次构建、永不修改：写路径「校验 → 落库 → 重读全量 → 构建新快照 → volatile 引用整体替换」， 读侧拿到旧快照引用后内容恒定（无并发读脏）。热路径零 DB
 * 读零锁。
 */
public final class RuntimeConfigSnapshot {

    private final Map<String, RuntimeConfigEntry> entries;

    private RuntimeConfigSnapshot(Map<String, RuntimeConfigEntry> entries) {
        this.entries = entries;
    }

    /** 由全量条目构建（构建后内部 Map 不可变；重复键后者覆盖，正常不应出现——DB 有 UNIQUE 约束）。 */
    public static RuntimeConfigSnapshot of(Collection<RuntimeConfigEntry> entries) {
        Map<String, RuntimeConfigEntry> map = new LinkedHashMap<>(entries.size() * 2);
        for (RuntimeConfigEntry entry : entries) {
            map.put(entry.configKey(), entry);
        }
        return new RuntimeConfigSnapshot(Collections.unmodifiableMap(map));
    }

    /** 空快照（无任何配置行时的降级态）。 */
    public static RuntimeConfigSnapshot empty() {
        return new RuntimeConfigSnapshot(Map.of());
    }

    public Optional<RuntimeConfigEntry> find(String configKey) {
        return Optional.ofNullable(entries.get(configKey));
    }

    /** 键集合视图（不可变）。 */
    public Map<String, RuntimeConfigEntry> asMap() {
        return entries;
    }

    public int size() {
        return entries.size();
    }
}
