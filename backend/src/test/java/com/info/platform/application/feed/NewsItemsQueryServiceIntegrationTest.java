package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * NewsItemsQueryService 集成测试（T104）：真实仓储视图装配——游标模式 nextBeforeId 续页信号、 页码模式 total 回显、源展示名
 * join、软删源默认排除。
 */
@SpringBootTest
@ActiveProfiles("test")
class NewsItemsQueryServiceIntegrationTest {

    @Autowired private NewsItemsQueryService service;
    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private FeedItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceId;

    @BeforeEach
    void setUp() {
        InfoSource source =
                InfoSource.create(
                        "t104_q",
                        "查询源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/q",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
        Instant t = Instant.parse("2026-09-22T05:00:00Z");
        for (int i = 1; i <= 3; i++) {
            itemRepository.insertIgnoreBatch(
                    List.of(
                            FeedItem.newOf(
                                    sourceId,
                                    "q" + i,
                                    "第" + i + "条",
                                    null,
                                    null,
                                    null,
                                    t,
                                    t,
                                    FeedFingerprint.fingerprint("第" + i + "条", t))));
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't104_q')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't104_q')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't104_q'");
    }

    @Test
    void listCursor_fullPageSignalsNextBeforeId_lastPageNull() {
        NewsItemsCursorView page1 = service.listCursor(sourceId, null, 2);

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.items().get(0).title()).isEqualTo("第3条");
        // 源展示名 join（页面渲染用）
        assertThat(page1.items().get(0).sourceCode()).isEqualTo("t104_q");
        assertThat(page1.items().get(0).sourceName()).isEqualTo("查询源");
        assertThat(page1.items().get(0).publishedAt()).isEqualTo("2026-09-22T05:00:00Z");
        assertThat(page1.nextBeforeId()).isEqualTo(page1.items().get(1).id());

        NewsItemsCursorView page2 = service.listCursor(sourceId, page1.nextBeforeId(), 2);

        // 末页（不满页）无续页信号
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.nextBeforeId()).isNull();
    }

    @Test
    void listPaged_returnsTotalAndEcho() {
        NewsItemsPagedView view = service.listPaged(sourceId, 1, 2);

        assertThat(view.items()).hasSize(2);
        assertThat(view.total()).isEqualTo(3);
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(2);
    }

    @Test
    void list_softDeletedSourceExcluded_byDefault() {
        jdbcTemplate.update("UPDATE info_source SET deleted = 1 WHERE id = ?", sourceId);

        assertThat(service.listCursor(null, null, 10).items()).isEmpty();
        assertThat(service.listPaged(null, 1, 10).total()).isZero();
    }
}
