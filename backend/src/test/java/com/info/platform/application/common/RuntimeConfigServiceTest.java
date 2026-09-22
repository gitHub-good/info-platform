package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link RuntimeConfigService} 单元测试（T34）：快照一致性（不可变 + 写时整体替换）、校验器框架（未知键/非法值拒绝）、 expectedUpdatedAt
 * 并发防呆（30065）、seed 幂等与 DB 权威、写失败快照不动。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeConfigServiceTest {

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T08:00:00Z");

    /** 内存仓储：真实落库语义（键覆盖、时间戳沿用实体），供快照/种子流程走真实路径。 */
    private static class InMemoryRepository implements RuntimeConfigRepository {
        final Map<String, RuntimeConfig> rows = new LinkedHashMap<>();

        @Override
        public Optional<RuntimeConfig> findByKey(String configKey) {
            return Optional.ofNullable(rows.get(configKey));
        }

        @Override
        public List<RuntimeConfig> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public RuntimeConfig save(RuntimeConfig config) {
            RuntimeConfig toStore =
                    rows.containsKey(config.getConfigKey()) && config.getDescription() == null
                            ? RuntimeConfig.reconstruct(
                                    config.getConfigKey(),
                                    config.getConfigValue(),
                                    rows.get(config.getConfigKey()).getDescription(),
                                    rows.get(config.getConfigKey()).getCreatedAt(),
                                    config.getUpdatedAt())
                            : config;
            rows.put(config.getConfigKey(), toStore);
            return toStore;
        }
    }

    /** 测试用域校验器：value 须为正整数。 */
    private static class PositiveValueValidator implements RuntimeConfigValidator {
        @Override
        public boolean supports(String configKey) {
            return "test.key".equals(configKey);
        }

        @Override
        public void validate(String configKey, JsonNode document) {
            ConfigFieldRules.enforce(document, List.of(ConfigFieldRules.positiveLong("value")));
        }
    }

    @Mock private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(T1, ZoneOffset.UTC);
    private InMemoryRepository repository;
    private RuntimeConfigService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        service =
                new RuntimeConfigService(
                        repository,
                        List.of(new PositiveValueValidator()),
                        eventPublisher,
                        clock,
                        objectMapper);
    }

    @Test
    void current_lazyLoadsSnapshotFromRepository() {
        // Arrange：DB 已有一行（模拟启动前写入），服务未加载
        repository.save(RuntimeConfig.create("test.key", "{\"value\":1}", null, T1));

        // Act
        RuntimeConfigSnapshot snapshot = service.current();

        // Assert
        assertThat(snapshot.find("test.key")).isPresent();
        assertThat(snapshot.find("test.key").orElseThrow().document().get("value").asLong())
                .isEqualTo(1);
    }

    @Test
    void write_unknownKey_rejectedWithParamInvalid() {
        // Act + Assert：无校验器注册的键拒绝写入（三域接口开放前配置面不可写）
        assertThatThrownBy(() -> service.write("llm.provider.deepseek", "{}", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知配置键")
                .extracting(ex -> ((BusinessException) ex).getErrorCode().getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    void write_illegalValue_rejectedWithFieldReason_snapshotUntouched() {
        // Arrange：DB 已有合法值
        repository.save(RuntimeConfig.create("test.key", "{\"value\":10}", null, T1));
        service.current();

        // Act + Assert：value=0 违反正整数规则，msg 带字段级原因
        assertThatThrownBy(() -> service.write("test.key", "{\"value\":0}", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("value")
                .hasMessageContaining("正整数");
        assertThat(service.current().find("test.key").orElseThrow().json())
                .isEqualTo("{\"value\":10}");
        // 校验失败路径不发布事件（快照与事件都只在写成功后）
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never())
                .publishEvent(any(RuntimeConfigChangedEvent.class));
    }

    @Test
    void write_nonObjectJson_rejected() {
        assertThatThrownBy(() -> service.write("test.key", "[1,2]", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 对象");
        assertThatThrownBy(() -> service.write("test.key", "{broken", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 解析失败");
    }

    @Test
    void write_success_swapsSnapshot_andPublishesEvent_oldSnapshotStable() {
        // Arrange：DB 已有初值（T1），加载并持有旧快照引用
        repository.save(RuntimeConfig.create("test.key", "{\"value\":10}", null, T1));
        RuntimeConfigSnapshot before = service.current();

        // Act：时钟推进到 T2 再写（模拟保存后下一次写）
        service =
                new RuntimeConfigService(
                        repository,
                        List.of(new PositiveValueValidator()),
                        eventPublisher,
                        Clock.fixed(T2, ZoneOffset.UTC),
                        objectMapper);
        RuntimeConfigEntry second = service.write("test.key", "{\"value\":20}", null);

        // Assert：新快照生效；旧快照引用内容不变（不可变，读侧无并发脏读）
        assertThat(
                        service.current()
                                .find("test.key")
                                .orElseThrow()
                                .document()
                                .get("value")
                                .asLong())
                .isEqualTo(20);
        assertThat(before.find("test.key").orElseThrow().document().get("value").asLong())
                .isEqualTo(10);
        assertThat(second.updatedAt()).isEqualTo(T2);

        // 事件发布（键 + 更新时刻）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        RuntimeConfigChangedEvent event = (RuntimeConfigChangedEvent) captor.getValue();
        assertThat(event.configKey()).isEqualTo("test.key");
        assertThat(event.updatedAt()).isEqualTo(T2);
    }

    @Test
    void write_expectedUpdatedAtMismatch_rejectedWith30065() {
        // Arrange：初值写入（updated_at = T1）
        service.write("test.key", "{\"value\":10}", null);

        // Act + Assert：携带错误 expectedUpdatedAt → 30065；DB 值不变
        assertThatThrownBy(() -> service.write("test.key", "{\"value\":20}", T2))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_CONFLICT);
        assertThat(repository.rows.get("test.key").getConfigValue()).isEqualTo("{\"value\":10}");

        // 携带正确 expectedUpdatedAt → 成功
        RuntimeConfigEntry updated = service.write("test.key", "{\"value\":20}", T1);
        assertThat(updated.updatedAt()).isEqualTo(T1);
    }

    @Test
    void write_missingKeyWithExpectedUpdatedAt_rejectedWith30065() {
        assertThatThrownBy(() -> service.write("test.key", "{\"value\":1}", T1))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_CONFLICT);
    }

    @Test
    void write_repositoryFailure_propagates_snapshotUntouched() {
        // Arrange：已有合法值并加载快照；仓储改为抛异常
        repository.save(RuntimeConfig.create("test.key", "{\"value\":10}", null, T1));
        service.current();
        InMemoryRepository failing =
                new InMemoryRepository() {
                    @Override
                    public RuntimeConfig save(RuntimeConfig config) {
                        throw new IllegalStateException("sqlite busy");
                    }
                };
        RuntimeConfigService failingService =
                new RuntimeConfigService(
                        failing,
                        List.of(new PositiveValueValidator()),
                        eventPublisher,
                        clock,
                        objectMapper);
        failingService.current();

        // Act + Assert：异常上抛（全局处理器 → 50000），快照不动、旧值继续生效
        assertThatThrownBy(() -> failingService.write("test.key", "{\"value\":20}", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.current().find("test.key").orElseThrow().json())
                .isEqualTo("{\"value\":10}");
    }

    @Test
    void seedIfAbsent_idempotent_andDbAuthoritative() {
        // Arrange
        List<RuntimeConfigSeed> seeds =
                List.of(new RuntimeConfigSeed("test.key", "{\"value\":1}", "种子说明"));

        // Act：两次导入
        int first = service.seedIfAbsent(seeds);
        int second = service.seedIfAbsent(seeds);

        // Assert：第二次零插入（幂等）；快照可见种子
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(service.current().find("test.key").orElseThrow().json())
                .isEqualTo("{\"value\":1}");

        // DB 权威：页面改值后再导入种子，不被 yml 覆盖
        service.write("test.key", "{\"value\":99}", T1);
        service.seedIfAbsent(seeds);
        assertThat(
                        service.current()
                                .find("test.key")
                                .orElseThrow()
                                .document()
                                .get("value")
                                .asLong())
                .isEqualTo(99);
    }

    @Test
    void seedIfAbsent_emptyList_stillReloadsSafely() {
        assertThat(service.seedIfAbsent(new ArrayList<>())).isZero();
        assertThat(service.current().size()).isZero();
    }

    @Test
    void read_absentKey_returnsEmpty() {
        assertThat(service.read("not.exist")).isEmpty();
    }

    @Test
    void reload_replacesSnapshotFromDb() {
        // Arrange：加载后 DB 被外部改动（模拟另一写路径）
        service.current();
        repository.rows.put(
                "test.key", RuntimeConfig.create("test.key", "{\"value\":7}", null, T2));

        // Act
        service.reload();

        // Assert
        assertThat(
                        service.current()
                                .find("test.key")
                                .orElseThrow()
                                .document()
                                .get("value")
                                .asLong())
                .isEqualTo(7);
    }
}
