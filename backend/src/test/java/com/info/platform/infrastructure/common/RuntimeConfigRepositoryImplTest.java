package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * RuntimeConfigRepositoryImpl 集成测试（T34）：SQLite 共享内存库 + Flyway V15 建表后，测 upsert（新键插入 / 既有键整体 替换且保留
 * created_at 与 description）、findByKey、findAll。 {@code @Transactional} 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RuntimeConfigRepositoryImplTest {

    @Autowired private RuntimeConfigRepository repository;

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void save_newKey_insertsAndFindsBack() {
        // Act（键取种子键空间之外，避免共享上下文启动种子把插入变更新）
        RuntimeConfig saved =
                repository.save(RuntimeConfig.create("test.insert", "{\"a\":1}", "说明", T1));

        // Assert
        Optional<RuntimeConfig> found = repository.findByKey("test.insert");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getConfigValue()).isEqualTo("{\"a\":1}");
        assertThat(found.orElseThrow().getDescription()).isEqualTo("说明");
        assertThat(found.orElseThrow().getCreatedAt()).isEqualTo(T1);
        assertThat(saved.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void save_existingKey_replacesValueKeepsCreatedAtAndDescription() {
        // Arrange
        repository.save(RuntimeConfig.create("test.update", "{\"enabled\":true}", "说明", T1));

        // Act：整体替换值（description 为 null → 沿用旧说明）
        RuntimeConfig updated =
                repository.save(
                        RuntimeConfig.create("test.update", "{\"enabled\":false}", null, T2)
                                .withUpdatedValue("{\"enabled\":false}", T2));

        // Assert
        assertThat(updated.getConfigValue()).isEqualTo("{\"enabled\":false}");
        assertThat(updated.getCreatedAt()).isEqualTo(T1);
        assertThat(updated.getUpdatedAt()).isEqualTo(T2);
        assertThat(updated.getDescription()).isEqualTo("说明");
        assertThat(repository.findByKey("test.update").orElseThrow().getConfigValue())
                .isEqualTo("{\"enabled\":false}");
    }

    @Test
    void findByKey_absent_returnsEmpty() {
        assertThat(repository.findByKey("not.exist")).isEmpty();
    }
}
