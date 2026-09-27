package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DeepDiveNewsStoreImpl 集成测试（M21 T183，方案 §4.4.2）：关联资讯按 matched_subjects JSON 引号定界 LIKE 命中 + 窗界 +
 * cap； 行业资讯按 main_category 精确匹配；非 DONE 行不入选。夹具用远未来 news id 隔离并逐轮清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeepDiveNewsStoreImplTest {

    /** 远未来隔离 id 基（不与业务行碰撞）。 */
    private static final long BASE_ID = 900_000_000L;

    @Autowired private DeepDiveNewsStoreImpl store;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM news_analysis WHERE news_id >= ?", BASE_ID);
        jdbcTemplate.update("DELETE FROM news_item WHERE id >= ?", BASE_ID);
    }

    private long insertNews(
            String title,
            String publishedAt,
            String matchedSubjects,
            String mainCategory,
            String l1Status) {
        long id = BASE_ID + count.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO news_item (id, source_id, title, published_at, fetched_at,"
                        + " fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, 1, ?, ?)",
                id,
                title,
                publishedAt,
                publishedAt,
                "fp-" + id + "-" + title,
                publishedAt,
                publishedAt);
        jdbcTemplate.update(
                "INSERT INTO news_analysis (news_id, l1_status, main_category, matched_subjects,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                l1Status,
                mainCategory,
                matchedSubjects,
                publishedAt,
                publishedAt);
        return id;
    }

    private final java.util.concurrent.atomic.AtomicInteger count =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void findRelatedNews_matchesByCodeWithinWindowNewestFirst() {
        long inWindow =
                insertNews(
                        "机器人获大额订单",
                        "2099-06-02T08:00:00Z",
                        "[{\"code\":\"SZ300024\",\"name\":\"机器人\"}]",
                        "电子",
                        "DONE");
        long newest =
                insertNews(
                        "机器人新品发布",
                        "2099-06-03T09:00:00Z",
                        "[{\"code\":\"SZ300024\",\"name\":\"机器人\"}]",
                        "电子",
                        "DONE");
        insertNews(
                "窗外旧文",
                "2099-05-01T00:00:00Z",
                "[{\"code\":\"SZ300024\",\"name\":\"机器人\"}]",
                "电子",
                "DONE");
        insertNews(
                "他标的无关文",
                "2099-06-03T10:00:00Z",
                "[{\"code\":\"SH600000\",\"name\":\"浦发银行\"}]",
                "银行",
                "DONE");
        insertNews(
                "未完成归类",
                "2099-06-03T11:00:00Z",
                "[{\"code\":\"SZ300024\",\"name\":\"机器人\"}]",
                null,
                "PENDING");

        List<NewsFact> news = store.findRelatedNews("SZ300024", "2099-06-01T00:00:00Z", 8);

        assertThat(news).extracting(NewsFact::newsId).containsExactly(newest, inWindow);
        assertThat(news.get(0).title()).isEqualTo("机器人新品发布");
    }

    @Test
    void findIndustryNews_matchesMainCategoryWithCap() {
        long oldest = insertNews("电子行业甲", "2099-06-02T08:00:00Z", null, "电子", "DONE");
        long second = insertNews("电子行业乙", "2099-06-03T08:00:00Z", null, "电子", "DONE");
        long first = insertNews("电子行业丙", "2099-06-04T08:00:00Z", null, "电子", "DONE");
        insertNews("银行行业文", "2099-06-04T09:00:00Z", null, "银行", "DONE");

        List<NewsFact> all = store.findIndustryNews("电子", "2099-06-01T00:00:00Z", 3);
        assertThat(all).extracting(NewsFact::newsId).containsExactly(first, second, oldest);

        List<NewsFact> capped = store.findIndustryNews("电子", "2099-06-01T00:00:00Z", 1);
        assertThat(capped).extracting(NewsFact::newsId).containsExactly(first);
    }
}
