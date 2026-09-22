package com.info.platform.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * PolicyRepositoryImpl 集成测试（T24）：SQLite 共享内存库 + Flyway V10 建表后，测 saveAll 往返（JSON 行业数组
 * JacksonTypeHandler）、existsBySourceUrl 去重、findRecent 游标分页（newest-first id DESC）/ 行业过滤（JSON LIKE） /
 * 时间窗过滤。@SpringBootTest 启动完整上下文（含 Flyway 迁移）；@Transactional 每用例结束回滚隔离（同 AnomalyRepositoryImplTest）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PolicyRepositoryImplTest {

    @Autowired private PolicyRepository repository;

    private static PolicyItem newItem(
            String title, String date, String url, List<String> industries) {
        return PolicyItem.create(title, "国务院政策", LocalDate.parse(date), null, industries, url);
    }

    private static String todayMinus(int days) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(days).toString();
    }

    @Test
    void saveAll_andFindById_roundtrip_jsonIndustries() {
        // Arrange + Act：落库一条含多行业标签的政策
        PolicyItem p = newItem("国务院关于白酒与银行的意见", "2026-09-20", "https://gov/a", List.of("白酒", "银行"));
        List<PolicyItem> saved = repository.saveAll(List.of(p));

        // Assert：id/时间戳回填；JSON 行业数组往返无损；aiTendency 默认未判
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getId()).isNotNull();
        assertThat(saved.get(0).getCreatedAt()).isNotNull();

        PolicyItem loaded = repository.findById(saved.get(0).getId()).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("国务院关于白酒与银行的意见");
        assertThat(loaded.getRelatedIndustries()).containsExactly("白酒", "银行");
        assertThat(loaded.getAiTendency()).isEqualTo(AiTendency.UNJUDGED);
        assertThat(loaded.getSource()).isEqualTo("国务院政策");
        assertThat(loaded.getSourceUrl()).isEqualTo("https://gov/a");
        assertThat(loaded.getPublishedAt().toString()).isEqualTo("2026-09-20");
    }

    @Test
    void saveAll_emptyList_noOp() {
        assertThat(repository.saveAll(List.of())).isEmpty();
    }

    @Test
    void existsBySourceUrl_dedup() {
        // Arrange + Act
        repository.saveAll(List.of(newItem("政策A", todayMinus(1), "https://gov/a", List.of())));

        // Assert：已存在 url 命中，其他 url 不命中
        assertThat(repository.existsBySourceUrl("https://gov/a")).isTrue();
        assertThat(repository.existsBySourceUrl("https://gov/other")).isFalse();
        assertThat(repository.existsBySourceUrl(null)).isFalse();
        assertThat(repository.existsBySourceUrl("  ")).isFalse();
    }

    @Test
    void findRecent_daysWindowAndCursor_newestFirst() {
        // Arrange：按插入序落 3 条（id 升序），均近 7 天内
        repository.saveAll(
                List.of(
                        newItem("政策1", todayMinus(1), "https://gov/1", List.of("白酒")),
                        newItem("政策2", todayMinus(2), "https://gov/2", List.of("银行")),
                        newItem("政策3", todayMinus(3), "https://gov/3", List.of("互联网"))));

        // Act + Assert：首页（cursor=null，limit=2）newest-first → 政策3, 政策2
        List<PolicyItem> page1 = repository.findRecent(7, null, null, 2);
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).getTitle()).isEqualTo("政策3");
        assertThat(page1.get(1).getTitle()).isEqualTo("政策2");

        // 翻页（cursor=page1 末条 id）→ 政策1；不满页 → 服务层据 size<limit 判无下一页
        Long cursor = page1.get(1).getId();
        List<PolicyItem> page2 = repository.findRecent(7, null, cursor, 2);
        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).getTitle()).isEqualTo("政策1");
    }

    @Test
    void findRecent_industryFilter_likesJsonArray() {
        // Arrange：白酒与银行各一条
        repository.saveAll(
                List.of(
                        newItem("白酒政策", todayMinus(1), "https://gov/w", List.of("白酒")),
                        newItem("银行政策", todayMinus(1), "https://gov/b", List.of("银行"))));

        // Act + Assert：industry=白酒 仅命中白酒条目（JSON 文本 LIKE %"白酒"%，引号作 token 边界）
        List<PolicyItem> hit = repository.findRecent(7, "白酒", null, 20);
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).getTitle()).isEqualTo("白酒政策");
    }

    @Test
    void findRecent_industrySubstring_doesNotFalseMatch() {
        // Arrange：行业标签含「银行」，查询「商业银行」（不存在）→ 不应误命中「银行」
        repository.saveAll(List.of(newItem("银行政策", todayMinus(1), "https://gov/b", List.of("银行"))));

        // Act + Assert：查询不存在的「商业银行」行业 → 空结果（引号 token 边界防误命中）
        List<PolicyItem> hit = repository.findRecent(7, "商业银行", null, 20);
        assertThat(hit).isEmpty();
    }

    @Test
    void findRecent_outsideDaysWindow_excluded() {
        // Arrange：30 天前的政策（超出 7 天窗）
        repository.saveAll(List.of(newItem("旧政策", todayMinus(30), "https://gov/old", List.of())));

        // Act + Assert：7 天窗排除 30 天前条目
        List<PolicyItem> hit = repository.findRecent(7, null, null, 20);
        assertThat(hit).isEmpty();
    }

    @Test
    void findRecent_notFoundById_empty() {
        assertThat(repository.findById(999999L)).isEmpty();
    }
}
