package com.info.platform.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.infrastructure.aggregation.GovPolicyClient;
import java.io.IOException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PolicyFetchJob 单元测试（T24）：mock GovPolicyClient/PolicyRepository，验证 抓取→去重→标注行业→saveAll 各分支 （不依赖真实
 * gov.cn 网络，对齐 04 测试规范 FIRST）。@Scheduled 不触发（policy.fetch.enabled=false 时 Job 不装配）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyFetchJobTest {

    @Mock private GovPolicyClient client;
    @Mock private PolicyRepository repository;

    private PolicyFetchJob job;

    @BeforeEach
    void setUp() {
        job = new PolicyFetchJob(client, repository);
    }

    private static Map<String, Object> raw(String title, String date, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("pubDate", date);
        m.put("url", url);
        return m;
    }

    @Test
    void fetch_newPolicies_dedupAndSaveAllWithIndustries() throws Exception {
        // Arrange：两条新政策（白酒 + 降准=银行），均未入库
        when(client.fetchPolicies())
                .thenReturn(
                        Optional.of(
                                List.of(
                                        raw("国务院关于促进白酒产业高质量发展的意见", "2026-09-20", "https://gov/a"),
                                        raw(
                                                "中国人民银行关于降低存款准备金率的通知",
                                                "2026-09-19",
                                                "https://gov/b"))));
        when(repository.existsBySourceUrl("https://gov/a")).thenReturn(false);
        when(repository.existsBySourceUrl("https://gov/b")).thenReturn(false);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        job.fetch();

        // Assert：去重后 saveAll 2 条；行业标注正确；source=国务院政策；aiTendency=0 未判
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicyItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<PolicyItem> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getTitle()).contains("白酒");
        assertThat(saved.get(0).getRelatedIndustries()).containsExactly("白酒");
        assertThat(saved.get(0).getSource()).isEqualTo("国务院政策");
        assertThat(saved.get(0).getSourceUrl()).isEqualTo("https://gov/a");
        assertThat(saved.get(0).getPublishedAt().toString()).isEqualTo("2026-09-20");
        assertThat(saved.get(0).getAiTendency()).isEqualTo(AiTendency.UNJUDGED);
        assertThat(saved.get(1).getRelatedIndustries()).containsExactly("银行"); // 降准 → 银行
        assertThat(saved.get(1).getSummary()).isNull(); // 列表页无摘要
    }

    @Test
    void fetch_existingUrl_dedupSkippedNoSave() throws Exception {
        // Arrange：source_url 已存在 → 去重跳过
        when(client.fetchPolicies())
                .thenReturn(Optional.of(List.of(raw("国务院关于白酒的意见", "2026-09-20", "https://gov/a"))));
        when(repository.existsBySourceUrl("https://gov/a")).thenReturn(true);

        // Act
        job.fetch();

        // Assert：不入库
        verify(repository, never()).saveAll(any());
    }

    @Test
    void fetch_emptyResult_doesNothing() throws Exception {
        // Arrange：抓取无数据（MISSING）
        when(client.fetchPolicies()).thenReturn(Optional.empty());

        // Act
        job.fetch();

        // Assert：不查重、不入库
        verify(repository, never()).saveAll(any());
    }

    @Test
    void fetch_ioException_skipsLoggedNoSave() throws Exception {
        // Arrange：抓取抛 IOException → 整轮跳过（不抛出，下次调度重试）
        when(client.fetchPolicies()).thenThrow(new IOException("连接超时"));

        // Act
        job.fetch();

        // Assert：不入库
        verify(repository, never()).saveAll(any());
    }

    @Test
    void fetch_blankPubDate_fallbacksToToday() throws Exception {
        // Arrange：缺发布日期 → 兜底今日（published_at NOT NULL）
        when(client.fetchPolicies())
                .thenReturn(Optional.of(List.of(raw("国务院关于白酒的意见", "", "https://gov/a"))));
        when(repository.existsBySourceUrl("https://gov/a")).thenReturn(false);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        job.fetch();

        // Assert：publishedAt 非空（兜底今日）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicyItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getPublishedAt()).isNotNull();
    }

    @Test
    void fetch_titleMissing_skipped() throws Exception {
        // Arrange：缺标题/URL 的条目跳过（防御脏数据）
        when(client.fetchPolicies())
                .thenReturn(Optional.of(List.of(raw("", "2026-09-20", "https://gov/a"))));

        // Act
        job.fetch();

        // Assert：不入库（标题为空被跳过）
        verify(repository, never()).saveAll(any());
    }
}
