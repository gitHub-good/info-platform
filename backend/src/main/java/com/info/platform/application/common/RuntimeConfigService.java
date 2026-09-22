package com.info.platform.application.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 运行时配置中心服务（应用层，T34 / ADR-0017）。
 *
 * <p>读：{@link #current()} 返回不可变快照（volatile 引用整体替换，热路径零 DB 读零锁）；首次访问惰性全量加载（兜底 {@code ConfigCenter}
 * 就绪事件之前的读取与测试上下文）。 写：{@link #write} = 找校验器 → 解析 JSON → 校验 → expectedUpdatedAt 并发防呆 → DB upsert →
 * 重读全量换快照 → 发 {@link RuntimeConfigChangedEvent}。写失败（DB 异常）异常 上抛 50000，快照不动、旧值继续生效。
 *
 * <p>写方法与 {@link #reload}/{@link #seedIfAbsent} 同步互斥（单用户单写者量级，synchronized 足够；读路径无锁）。
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final RuntimeConfigRepository repository;
    private final List<RuntimeConfigValidator> validators;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** 当前快照（不可变，整体替换）；null = 尚未加载。 */
    private volatile RuntimeConfigSnapshot snapshot;

    public RuntimeConfigService(
            RuntimeConfigRepository repository,
            List<RuntimeConfigValidator> validators,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.validators = List.copyOf(validators);
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 当前快照（惰性加载，永不返回 null；返回后内容恒定——快照不可变）。 */
    public RuntimeConfigSnapshot current() {
        RuntimeConfigSnapshot current = snapshot;
        if (current == null) {
            synchronized (this) {
                if (snapshot == null) {
                    doReload();
                }
                current = snapshot;
            }
        }
        return current;
    }

    /** 按键读单条（消费点用时读取的最小入口）。 */
    public Optional<RuntimeConfigEntry> read(String configKey) {
        return current().find(configKey);
    }

    /**
     * 写入配置（整体替换值）。
     *
     * @param configKey 域前缀键
     * @param json JSON 文档文本（整体替换，非合并）
     * @param expectedUpdatedAt 并发防呆：非空时须等于当前行的 updated_at，不符抛 30065；键不存在而非空亦抛 30065
     * @return 落库后的条目（含新 updated_at，供前端回填下次防呆比对）
     * @throws BusinessException 2001 未知键/非法 JSON/校验失败；30065 并发冲突
     */
    public synchronized RuntimeConfigEntry write(
            String configKey, String json, Instant expectedUpdatedAt) {
        RuntimeConfigValidator validator = validatorOf(configKey);
        JsonNode document = parseDocument(configKey, json);
        validator.validate(configKey, document);

        Optional<RuntimeConfig> existing = repository.findByKey(configKey);
        checkConcurrent(configKey, expectedUpdatedAt, existing);

        Instant now = clock.instant();
        RuntimeConfig toSave =
                existing.map(cfg -> cfg.withUpdatedValue(json, now))
                        .orElseGet(() -> RuntimeConfig.create(configKey, json, null, now));
        RuntimeConfig saved = repository.save(toSave);

        doReload();
        eventPublisher.publishEvent(new RuntimeConfigChangedEvent(configKey, saved.getUpdatedAt()));
        // 值不落日志：key 相关键永不记值，其余键的值可经读取接口获取，日志只留键级审计痕迹（方案 §5 可观测）。
        log.info("运行时配置已更新: key={}, updatedAt={}", configKey, saved.getUpdatedAt());
        return toEntry(saved);
    }

    /**
     * seed-if-absent 种子导入：仅插入 DB 中不存在的键（DB 为权威，已存在不动，幂等），完成后刷新快照。
     *
     * @return 实际插入的键数
     */
    public synchronized int seedIfAbsent(List<RuntimeConfigSeed> seeds) {
        int inserted = 0;
        for (RuntimeConfigSeed seed : seeds) {
            if (repository.findByKey(seed.configKey()).isEmpty()) {
                repository.save(
                        RuntimeConfig.create(
                                seed.configKey(),
                                seed.json(),
                                seed.description(),
                                clock.instant()));
                inserted++;
            }
        }
        if (inserted > 0) {
            log.info("runtime_config 种子导入 {} 键（已有键不覆盖，DB 为权威）", inserted);
        }
        doReload();
        return inserted;
    }

    /** 全量重读并整体替换快照（启动加载 / 写后刷新）。 */
    public synchronized void reload() {
        doReload();
    }

    private void doReload() {
        List<RuntimeConfigEntry> entries =
                repository.findAll().stream().map(this::toEntry).toList();
        snapshot = RuntimeConfigSnapshot.of(entries);
    }

    private RuntimeConfigValidator validatorOf(String configKey) {
        return validators.stream()
                .filter(v -> v.supports(configKey))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.PARAM_INVALID, "未知配置键: " + configKey));
    }

    private JsonNode parseDocument(String configKey, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, configKey + ": 值须为 JSON 对象");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    configKey + ": JSON 解析失败 (" + e.getOriginalMessage() + ")");
        }
    }

    private void checkConcurrent(
            String configKey, Instant expectedUpdatedAt, Optional<RuntimeConfig> existing) {
        if (expectedUpdatedAt == null) {
            return;
        }
        boolean matches =
                existing.map(cfg -> expectedUpdatedAt.equals(cfg.getUpdatedAt())).orElse(false);
        if (!matches) {
            throw new BusinessException(
                    ErrorCode.CONFIG_CONFLICT, "配置已被并发修改: " + configKey + "，请刷新后重试");
        }
    }

    private RuntimeConfigEntry toEntry(RuntimeConfig config) {
        return new RuntimeConfigEntry(
                config.getConfigKey(),
                config.getConfigValue(),
                readTree(config),
                config.getDescription(),
                config.getUpdatedAt());
    }

    /** 存量行 JSON 解析失败 = 权威存储已损坏，fail-fast（对齐方案 §5 降级预案「启动加载失败属启动故障」）。 */
    private JsonNode readTree(RuntimeConfig config) {
        try {
            return objectMapper.readTree(config.getConfigValue());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "runtime_config 键 "
                            + config.getConfigKey()
                            + " 的 JSON 解析失败: "
                            + e.getOriginalMessage(),
                    e);
        }
    }
}
