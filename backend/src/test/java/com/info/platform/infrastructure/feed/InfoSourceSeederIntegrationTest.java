package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * InfoSourceSeeder 集成测试（T100，ADR-0032 同系列 seed-if-absent）：启动种子落库（M13 三源 + M14 T110 批次一四源 = 7 预置源）、
 * 幂等重跑零新增、 运行态行随种子初始化（错峰 next_due_at ∈ [now, now+interval]）。共享内存库 + Flyway V22 建表，直连断言。
 */
@SpringBootTest
@ActiveProfiles("test")
class InfoSourceSeederIntegrationTest {

    @Autowired private InfoSourceSeeder seeder;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private SourcePollStateRepository stateRepository;

    @Test
    void bootSeedsAllPresetSources_withPollStateRows() {
        // 启动（ApplicationReadyEvent）已种：目录全部预置源齐备且均为预置、启用、未软删
        for (String code :
                new String[] {
                    "mw_topstories",
                    "jin10_flash",
                    "sina_zhibo_7x24",
                    "em_fastnews_7x24",
                    "ths_push_stock",
                    "thepaper_hotnews",
                    "em_macro_indicators"
                }) {
            Optional<InfoSource> found = infoSourceRepository.findBySourceCode(code);
            assertThat(found).as("预置源缺失: %s", code).isPresent();
            InfoSource source = found.orElseThrow();
            assertThat(source.isPreset()).isTrue();
            assertThat(source.isEnabled()).isTrue();
            assertThat(source.isDeleted()).isFalse();

            Optional<SourcePollState> state = stateRepository.findBySourceId(source.getId());
            assertThat(state).as("运行态行缺失: %s", code).isPresent();
            SourcePollState pollState = state.orElseThrow();
            Instant now = Instant.now();
            // 错峰：next_due_at = now + hash(source_code) mod interval ∈ [now, now+interval]
            assertThat(pollState.nextDueAt()).isAfterOrEqualTo(now.minusSeconds(60));
            assertThat(pollState.nextDueAt())
                    .isBeforeOrEqualTo(now.plusSeconds(60L * source.getIntervalMinutes() + 60));
            assertThat(pollState.consecutiveFailures()).isZero();
        }
    }

    @Test
    void seedIfAbsent_rerunIdempotent_noDuplicatesOrNewStateRows() {
        int before = infoSourceRepository.findAll().size();

        seeder.seedIfAbsent();

        assertThat(infoSourceRepository.findAll()).hasSize(before);
        // 幂等重跑不覆盖存量行（DB 为权威）：运行态行不被重置
        InfoSource mw = infoSourceRepository.findBySourceCode("mw_topstories").orElseThrow();
        assertThat(stateRepository.findBySourceId(mw.getId())).isPresent();
    }
}
