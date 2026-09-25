package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.AiExclusion;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AiExclusionResolver 单测（T125，REQ 拍板五-1）：源级 AI 排除档位解析——L2 档查询排除 L2+ALL（L2 段排除面）、L0/L1 段只排除 ALL
 * （照常归类语义）、缺省 NONE 不排除。mock 仓储。AAA 结构。
 */
class AiExclusionResolverTest {

    private InfoSourceRepository infoSourceRepository;
    private AiExclusionResolver resolver;

    @BeforeEach
    void setUp() {
        infoSourceRepository = mock(InfoSourceRepository.class);
        resolver = new AiExclusionResolver(infoSourceRepository);
    }

    private static InfoSource source(long id, AiExclusion level) {
        SourceConfig config =
                level == null
                        ? SourceConfig.empty()
                        : new SourceConfig(
                                null, null, null, null, null, null, null, null, null, null, level);
        InfoSource source =
                InfoSource.create(
                        "t125_" + id,
                        "源" + id,
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + id,
                        config,
                        15,
                        true,
                        false);
        source.assignPersisted(id, null, null);
        return source;
    }

    @Test
    void excluded_l2LevelCoversL2AndAll() {
        // L2 段排除面：L2 档 + ALL 档（ALL 源条目本无 analysis 行，防御性一并排除）
        when(infoSourceRepository.findAll())
                .thenReturn(
                        List.of(
                                source(1, AiExclusion.L2),
                                source(2, AiExclusion.ALL),
                                source(3, AiExclusion.NONE),
                                source(4, null)));

        assertThat(resolver.excludedSourceIds(AiExclusion.L2)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void excluded_allLevelCoversAllOnly() {
        // L0/L1 段排除面：仅 ALL 档（L2 档源照常归类、照常计热度资讯量）
        when(infoSourceRepository.findAll())
                .thenReturn(
                        List.of(
                                source(1, AiExclusion.L2),
                                source(2, AiExclusion.ALL),
                                source(3, AiExclusion.NONE)));

        assertThat(resolver.excludedSourceIds(AiExclusion.ALL)).containsExactly(2L);
    }

    @Test
    void excluded_noneLevelOrNoSources_emptyList() {
        when(infoSourceRepository.findAll()).thenReturn(List.of());

        assertThat(resolver.excludedSourceIds(AiExclusion.ALL)).isEmpty();
        assertThat(resolver.excludedSourceIds(AiExclusion.L2)).isEmpty();
    }
}
