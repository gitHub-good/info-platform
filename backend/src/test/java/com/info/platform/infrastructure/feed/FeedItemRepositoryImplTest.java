package com.info.platform.infrastructure.feed;

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
 * FeedItemRepository 集成测试（T104，ADR-0039 双层去重）：指纹全局唯一 / 源内 UNIQUE(source_id, external_id) 两索引路径、
 * 应插−实插 = dup 对账、external_id NULL 多条靠指纹共存、newest-first 游标分页 / 页码分页与计数、软删源条目默认排除。 测试行 t104_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedItemRepositoryImplTest {

    @Autowired private FeedItemRepository itemRepository;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceA;
    private Long sourceB;

    private final Instant publishedAt = Instant.parse("2026-09-22T01:31:00Z");
    private final Instant fetchedAt = Instant.parse("2026-09-22T01:31:30Z");

    @BeforeEach
    void setUpSources() {
        sourceA = newSource("t104_a");
        sourceB = newSource("t104_b");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't104_%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't104_%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't104_%'");
    }

    private Long newSource(String code) {
        InfoSource source =
                InfoSource.create(
                        code,
                        code,
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + code,
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        return source.getId();
    }

    private FeedItem newItem(long sourceId, String externalId, String title, String fingerprint) {
        return FeedItem.newOf(
                sourceId,
                externalId,
                title,
                "摘要-" + title,
                "https://example.com/n/" + title,
                "作者",
                publishedAt,
                fetchedAt,
                fingerprint);
    }

    @Test
    void insertIgnoreBatch_duplicateExternalIdSameSource_ignored() {
        List<FeedItem> batch =
                List.of(
                        newItem(
                                sourceA,
                                "e1",
                                "标题一",
                                FeedFingerprint.fingerprint("标题一", publishedAt)),
                        newItem(
                                sourceA,
                                "e2",
                                "标题二",
                                FeedFingerprint.fingerprint("标题二", publishedAt)),
                        // 同源同 external_id（e1）不同标题 → 源内唯一索引拦截
                        newItem(
                                sourceA,
                                "e1",
                                "标题一改",
                                FeedFingerprint.fingerprint("标题一改", publishedAt)));

        int inserted = itemRepository.insertIgnoreBatch(batch);

        assertThat(inserted).isEqualTo(2);
        // dup 对账：应插 3 − 实插 2 = 1
        assertThat(batch.size() - inserted).isEqualTo(1);
        // 首个入库者胜：e1 保留原标题
        List<FeedItem> latest = itemRepository.findLatest(sourceA, null, 10);
        assertThat(latest).extracting(FeedItem::title).containsExactly("标题二", "标题一");
    }

    @Test
    void insertIgnoreBatch_duplicateFingerprintCrossSource_ignored() {
        String fingerprint = FeedFingerprint.fingerprint("跨源同稿", publishedAt);

        int first =
                itemRepository.insertIgnoreBatch(
                        List.of(newItem(sourceA, "a1", "跨源同稿", fingerprint)));
        // 源 B 转载同稿（external_id 不同、指纹相同）→ 全局指纹索引拦截，计 dup
        int second =
                itemRepository.insertIgnoreBatch(
                        List.of(newItem(sourceB, "b1", "跨源同稿", fingerprint)));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        // 跨源流只见一条
        assertThat(itemRepository.findLatest(null, null, 10)).hasSize(1);
    }

    @Test
    void insertIgnoreBatch_nullExternalIdMultipleRows_coexistViaFingerprint() {
        // external_id NULL 不受源内唯一约束；不同标题指纹不同 → 共存
        List<FeedItem> batch =
                List.of(
                        newItem(
                                sourceA,
                                null,
                                "无ID甲",
                                FeedFingerprint.fingerprint("无ID甲", publishedAt)),
                        newItem(
                                sourceA,
                                null,
                                "无ID乙",
                                FeedFingerprint.fingerprint("无ID乙", publishedAt)));

        int inserted = itemRepository.insertIgnoreBatch(batch);

        assertThat(inserted).isEqualTo(2);
        // 同标题 NULL id 重复 → 指纹兜底拦截
        int reinserted =
                itemRepository.insertIgnoreBatch(
                        List.of(
                                newItem(
                                        sourceA,
                                        null,
                                        "无ID甲",
                                        FeedFingerprint.fingerprint("无ID甲", publishedAt))));
        assertThat(reinserted).isZero();
    }

    @Test
    void insertIgnoreBatch_rerunSameBatch_idempotent() {
        List<FeedItem> batch =
                List.of(
                        newItem(
                                sourceA,
                                "x1",
                                "幂等一",
                                FeedFingerprint.fingerprint("幂等一", publishedAt)),
                        newItem(
                                sourceA,
                                "x2",
                                "幂等二",
                                FeedFingerprint.fingerprint("幂等二", publishedAt)));

        assertThat(itemRepository.insertIgnoreBatch(batch)).isEqualTo(2);
        assertThat(itemRepository.insertIgnoreBatch(batch)).isZero();
    }

    @Test
    void findLatest_newestFirst_withBeforeIdAndSourceFilter() {
        Instant t = Instant.parse("2026-09-22T02:00:00Z");
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                sourceA,
                                "n1",
                                "旧",
                                null,
                                null,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint("旧", t)),
                        FeedItem.newOf(
                                sourceA,
                                "n2",
                                "新A",
                                null,
                                null,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint("新A", t)),
                        FeedItem.newOf(
                                sourceB,
                                "n3",
                                "新B",
                                null,
                                null,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint("新B", t))));

        // 全局 newest-first（id DESC）
        List<FeedItem> all = itemRepository.findLatest(null, null, 10);
        assertThat(all).extracting(FeedItem::title).containsExactly("新B", "新A", "旧");
        // beforeId 续取
        Long secondId = all.get(1).id();
        assertThat(itemRepository.findLatest(null, secondId, 10))
                .extracting(FeedItem::title)
                .containsExactly("旧");
        // 源过滤
        assertThat(itemRepository.findLatest(sourceA, null, 10))
                .extracting(FeedItem::title)
                .containsExactly("新A", "旧");
    }

    @Test
    void findPage_andCount_sameFilterScope() {
        Instant t = Instant.parse("2026-09-22T03:00:00Z");
        for (int i = 1; i <= 5; i++) {
            itemRepository.insertIgnoreBatch(
                    List.of(
                            FeedItem.newOf(
                                    sourceA,
                                    "p" + i,
                                    "第" + i + "条",
                                    null,
                                    null,
                                    null,
                                    t,
                                    t,
                                    FeedFingerprint.fingerprint("第" + i + "条", t))));
        }

        List<FeedItem> page2 = itemRepository.findPage(sourceA, 2, 2);
        long total = itemRepository.countByFilter(sourceA);

        assertThat(total).isEqualTo(5);
        assertThat(page2).extracting(FeedItem::title).containsExactly("第3条", "第2条");
        assertThat(itemRepository.countByFilter(null)).isEqualTo(5);
    }

    @Test
    void queries_excludeSoftDeletedSourceItems_byDefault() {
        Instant t = Instant.parse("2026-09-22T04:00:00Z");
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                sourceA,
                                "d1",
                                "保留条目",
                                null,
                                null,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint("保留条目", t)),
                        FeedItem.newOf(
                                sourceB,
                                "d2",
                                "软删源条目",
                                null,
                                null,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint("软删源条目", t))));
        // sourceB 软删：历史条目保留在库，但默认流/计数排除（join info_source）
        jdbcTemplate.update("UPDATE info_source SET deleted = 1 WHERE id = ?", sourceB);

        assertThat(itemRepository.findLatest(null, null, 10))
                .extracting(FeedItem::title)
                .containsExactly("保留条目");
        assertThat(itemRepository.countByFilter(null)).isEqualTo(1);
        assertThat(itemRepository.findPage(null, 1, 10)).hasSize(1);
        // 行未物理删除（历史数据保留语义）
        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_item WHERE source_id = ?",
                        Integer.class,
                        sourceB);
        assertThat(rows).isEqualTo(1);
    }
}
