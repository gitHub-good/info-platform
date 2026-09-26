package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.ImpactCacheState;
import com.info.platform.domain.analysis.ImpactChainRepository;
import com.info.platform.domain.analysis.IndustryImpactChain;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 影响链仓储集成测试（M17 T144，V28 {@code industry_impact_chain}）：replaceForEvent
 * 整事件替换（重生成幂等——UNIQUE(event_id, industry) 收敛）、findByEventId 读回。t144_ 前缀数据隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class ImpactChainRepositoryImplTest {

    private static final long EVENT_ID = 9441L;

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    @Autowired private ImpactChainRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM industry_impact_chain WHERE event_id = ?", EVENT_ID);
    }

    @Test
    void replaceForEvent_thenFindByEventId_roundTrip() {
        int inserted =
                repository.replaceForEvent(
                        EVENT_ID,
                        List.of(
                                chainOf("银行", Direction.BULLISH),
                                chainOf("房地产", Direction.BULLISH)));

        assertThat(inserted).isEqualTo(2);
        List<IndustryImpactChain> loaded = repository.findByEventId(EVENT_ID);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.stream().map(IndustryImpactChain::getIndustry))
                .containsExactlyInAnyOrder("银行", "房地产");
        assertThat(loaded.get(0).getLogicChain()).isNotBlank();
        assertThat(loaded.get(0).getBasis()).contains("newsId");
    }

    @Test
    void replaceForEvent_reGenerates_replaceSemantics() {
        repository.replaceForEvent(EVENT_ID, List.of(chainOf("银行", Direction.BULLISH)));
        repository.replaceForEvent(
                EVENT_ID,
                List.of(
                        chainOf("银行", Direction.NEUTRAL),
                        chainOf("非银金融", Direction.BULLISH),
                        chainOf("房地产", Direction.BULLISH)));

        List<IndustryImpactChain> loaded = repository.findByEventId(EVENT_ID);
        assertThat(loaded).as("整事件替换不残留旧行").hasSize(3);
        assertThat(
                        loaded.stream()
                                .filter(chain -> chain.getIndustry().equals("银行"))
                                .findFirst()
                                .orElseThrow()
                                .getDirection())
                .isEqualTo(Direction.NEUTRAL);
    }

    @Test
    void findByEventId_emptyReturnsEmptyList() {
        assertThat(repository.findByEventId(EVENT_ID)).isEmpty();
    }

    private static IndustryImpactChain chainOf(String industry, Direction direction) {
        return IndustryImpactChain.create(
                EVENT_ID,
                industry,
                direction,
                "传导逻辑链示例文本",
                "{\"newsId\":100,\"signalNewsIds\":[100]}",
                "POLICY_MONETARY",
                ImpactCacheState.AUTO,
                NOW);
    }
}
