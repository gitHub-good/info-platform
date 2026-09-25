package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * InfoSourceRepository 集成测试（T100，V22 info_source 表）：insertIfAbsent 幂等（UNIQUE(source_code)）、 findActive 过滤
 * enabled/deleted、save 插入/更新双路径。测试行用 t100r_ 前缀隔离并在 @AfterEach 清理（共享内存库不污染种子行）。
 */
@SpringBootTest
@ActiveProfiles("test")
class InfoSourceRepositoryImplTest {

    @Autowired private InfoSourceRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't100r_%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't100r_%'");
    }

    private static InfoSource newSource(String code, boolean enabled) {
        return InfoSource.create(
                code, "测试源-" + code, "媒体", AdapterType.RSS, null,
                "https://example.com/" + code + ".xml", null, 15, enabled, false);
    }

    @Test
    void insertIfAbsent_firstInsertTrue_conflictFalse() {
        InfoSource source = newSource("t100r_a", true);

        assertThat(repository.insertIfAbsent(source)).isTrue();
        assertThat(repository.insertIfAbsent(newSource("t100r_a", true))).isFalse();

        Optional<InfoSource> found = repository.findBySourceCode("t100r_a");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getId()).isNotNull();
        assertThat(found.orElseThrow().getCreatedAt()).isNotNull();
    }

    @Test
    void findActive_filtersDisabledAndSoftDeleted() {
        InfoSource active = newSource("t100r_active", true);
        InfoSource disabled = newSource("t100r_disabled", false);
        InfoSource deleted = newSource("t100r_deleted", true);
        repository.save(active);
        repository.save(disabled);
        repository.save(deleted);
        deleted.markDeleted();
        repository.save(deleted);

        List<String> codes = repository.findActive().stream().map(InfoSource::getSourceCode).toList();

        assertThat(codes).contains("t100r_active");
        assertThat(codes).doesNotContain("t100r_disabled", "t100r_deleted");
    }

    @Test
    void save_insertThenUpdate_roundTrip() {
        InfoSource source = newSource("t100r_edit", true);
        repository.save(source);
        Long id = source.getId();
        assertThat(id).isNotNull();

        source.edit("改名", "自建", null, null, 30, false);
        repository.save(source);

        Optional<InfoSource> reloaded = repository.findById(id);
        assertThat(reloaded).isPresent();
        InfoSource re = reloaded.orElseThrow();
        assertThat(re.getName()).isEqualTo("改名");
        assertThat(re.getCategory()).isEqualTo("自建");
        assertThat(re.getIntervalMinutes()).isEqualTo(30);
        assertThat(re.isEnabled()).isFalse();
        // config 为空时缺省 '{}'/empty 回路
        assertThat(re.getConfig()).isNotNull();
    }

    @Test
    void save_configRoundTrip_keepsMappingsAndCursorDeclaration() {
        InfoSource source =
                InfoSource.create(
                        "t100r_cfg", "配置源", "快讯", AdapterType.JSON_API, null,
                        "https://example.com/api",
                        new com.info.platform.domain.feed.SourceConfig(
                                "", "var x=", ";",
                                List.of(
                                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                                "id", "externalId", "to_string"),
                                        new com.info.platform.domain.feed.SourceConfig.ItemMapping(
                                                "time", "publishedAt", "to_iso_datetime")),
                                java.util.Map.of("User-Agent", "Mozilla/5.0"),
                                30, 20,
                                com.info.platform.domain.feed.CursorType.ID, "externalId"),
                        5, true, false);
        repository.save(source);

        Optional<InfoSource> reloaded = repository.findBySourceCode("t100r_cfg");
        assertThat(reloaded).isPresent();
        var config = reloaded.orElseThrow().getConfig();
        assertThat(config.stripPrefix()).isEqualTo("var x=");
        assertThat(config.stripSuffix()).isEqualTo(";");
        assertThat(config.mappings()).hasSize(2);
        assertThat(config.effectiveCursorType())
                .isEqualTo(com.info.platform.domain.feed.CursorType.ID);
        assertThat(config.cursorField()).isEqualTo("externalId");
        assertThat(config.headers()).containsEntry("User-Agent", "Mozilla/5.0");
        assertThat(config.effectiveMaxItems()).isEqualTo(30);
        assertThat(config.effectivePageSize()).isEqualTo(20);
    }
}
