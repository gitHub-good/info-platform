package com.info.platform.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * PolicyRepositoryImpl 集成测试（T24）：SQLite 共享内存库 + Flyway V10 建表后，测 saveAll 往返（JSON 行业数组
 * JacksonTypeHandler）、existsBySourceUrl 去重、findRecent 游标分页（newest-first id DESC）/ 行业过滤（JSON LIKE） /
 * 时间窗过滤。@SpringBootTest 启动完整上下文（含 Flyway 迁移）；@Transactional 每用例结束回滚隔离（同 AnomalyRepositoryImplTest）。
 *
 * <p>P0-3 回归（系统体检 20260924）：V16 尝试留痕列 + findRecentUnjudged 重试上限/退避过滤 + recordTendencyAttempt
 * 留痕——失败条目试满上限后不再被 Job 扫中（止血无限重试烧 LLM 预算）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PolicyRepositoryImplTest {

    @Autowired private PolicyRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

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

    @Test
    void findRecentUnjudged_returnsOnlyAiTendencyZero_newestFirst() {
        // Arrange：3 条近期政策（saveAll 默认 ai_tendency=0），将首条标为利好
        List<PolicyItem> saved =
                repository.saveAll(
                        List.of(
                                newItem("政策A", todayMinus(1), "https://gov/u1", List.of("白酒")),
                                newItem("政策B", todayMinus(1), "https://gov/u2", List.of("银行")),
                                newItem("政策C", todayMinus(1), "https://gov/u3", List.of("互联网"))));
        repository.updateAiTendency(saved.get(0).getId(), AiTendency.BULLISH);

        // Act（上限 3 / 截止 now：未留痕条目天然入选）
        List<PolicyItem> pending = repository.findRecentUnjudged(7, 20, 3, Instant.now());

        // Assert：仅返 ai_tendency=0 的政策B、C（newest-first id DESC）；已判的政策A排除
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getId()).isEqualTo(saved.get(2).getId());
        assertThat(pending.get(1).getId()).isEqualTo(saved.get(1).getId());
        assertThat(pending)
                .allSatisfy(p -> assertThat(p.getAiTendency()).isEqualTo(AiTendency.UNJUDGED));
    }

    @Test
    void findRecentUnjudged_respectsDaysWindow() {
        // Arrange：8 天前的未判政策不应扫到（窗口 7 天）
        repository.saveAll(List.of(newItem("旧政策", todayMinus(8), "https://gov/old-u", List.of())));

        // Act + Assert
        assertThat(repository.findRecentUnjudged(7, 20, 3, Instant.now())).isEmpty();
    }

    @Test
    void updateAiTendency_setsTendencyAndTimestamp() {
        // Arrange
        Long id =
                repository
                        .saveAll(List.of(newItem("政策X", todayMinus(1), "https://gov/x", List.of())))
                        .get(0)
                        .getId();

        // Act：标为利空
        boolean updated = repository.updateAiTendency(id, AiTendency.BEARISH);

        // Assert：影响 1 行；回读 ai_tendency=2 利空
        assertThat(updated).isTrue();
        PolicyItem loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getAiTendency()).isEqualTo(AiTendency.BEARISH);
        assertThat(loaded.getTitle()).isEqualTo("政策X"); // 不可变字段未被触碰
    }

    @Test
    void updateAiTendency_unjudgedOrNullId_noOp() {
        // Arrange
        Long id =
                repository
                        .saveAll(List.of(newItem("政策Y", todayMinus(1), "https://gov/y", List.of())))
                        .get(0)
                        .getId();

        // Act + Assert：传 UNJUDGED 或 null id 视为无操作
        assertThat(repository.updateAiTendency(id, AiTendency.UNJUDGED)).isFalse();
        assertThat(repository.updateAiTendency(null, AiTendency.NEUTRAL)).isFalse();
        assertThat(repository.findById(id).orElseThrow().getAiTendency())
                .isEqualTo(AiTendency.UNJUDGED);
    }

    @Test
    void updateAiTendency_nonExistentId_returnsFalse() {
        assertThat(repository.updateAiTendency(999999L, AiTendency.NEUTRAL)).isFalse();
    }

    @Test
    void countCreatedSince_rollingWindowByCreatedAt_t42() {
        // Arrange：saveAll 落库时 created_at=now（口径裁定按入库时间，非 published_at）
        repository.saveAll(
                List.of(
                        newItem("新政策A", "2026-01-01", "https://gov/a2", List.of()),
                        newItem("新政策B", "2026-01-02", "https://gov/b2", List.of())));

        // Act / Assert：滚动窗口含刚入库行；未来边界为 0
        assertThat(repository.countCreatedSince(Instant.now().minusSeconds(3600))).isEqualTo(2);
        assertThat(repository.countCreatedSince(Instant.now().plusSeconds(60))).isZero();
    }

    @Test
    void findLatestCreatedSince_newestFirstLimited_t42() {
        // Arrange
        repository.saveAll(
                List.of(
                        newItem("政策一", "2026-09-20", "https://gov/l1", List.of()),
                        newItem("政策二", "2026-09-21", "https://gov/l2", List.of()),
                        newItem("政策三", "2026-09-22", "https://gov/l3", List.of())));

        // Act：滚动 1h 窗口 + limit 2（同秒 created_at 由 id DESC 兜底，后入库在前）
        List<PolicyItem> latest =
                repository.findLatestCreatedSince(Instant.now().minusSeconds(3600), 2);

        // Assert
        assertThat(latest).hasSize(2);
        assertThat(latest.get(0).getTitle()).isEqualTo("政策三");
        assertThat(latest.get(1).getTitle()).isEqualTo("政策二");
    }

    // ==================== P0-3（系统体检 20260924）：尝试留痕 + 重试上限/退避 ====================

    @Test
    void v16_tendencyAttemptColumns_existWithDefaults() {
        // Arrange：落库一条新政策（不触碰留痕列）
        Long id =
                repository
                        .saveAll(List.of(newItem("政策V16", todayMinus(1), "https://gov/v16", List.of())))
                        .get(0)
                        .getId();

        // Act + Assert：V16 迁移后两列存在且取默认值（attempts=0、last_attempt_at=NULL）——
        // 迁移缺失时本用例以「no such column」红（P0-3 修前红证据）
        Integer attempts =
                jdbcTemplate.queryForObject(
                        "SELECT tendency_attempts FROM policy_item WHERE id = ?",
                        Integer.class,
                        id);
        String lastAttemptAt =
                jdbcTemplate.queryForObject(
                        "SELECT tendency_last_attempt_at FROM policy_item WHERE id = ?",
                        String.class,
                        id);
        assertThat(attempts).isZero();
        assertThat(lastAttemptAt).isNull();
    }

    @Test
    void recordTendencyAttempt_incrementsCounterAndTimestamps() {
        // Arrange
        Long id =
                repository
                        .saveAll(List.of(newItem("政策R", todayMinus(1), "https://gov/r", List.of())))
                        .get(0)
                        .getId();

        // Act：两次失败判断各留痕一次
        int first = repository.recordTendencyAttempt(id);
        int second = repository.recordTendencyAttempt(id);

        // Assert：原子自增到 2；last_attempt_at 回填 ISO-8601 文本且随最新一次刷新
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        Integer attempts =
                jdbcTemplate.queryForObject(
                        "SELECT tendency_attempts FROM policy_item WHERE id = ?",
                        Integer.class,
                        id);
        String lastAttemptAt =
                jdbcTemplate.queryForObject(
                        "SELECT tendency_last_attempt_at FROM policy_item WHERE id = ?",
                        String.class,
                        id);
        assertThat(attempts).isEqualTo(2);
        assertThat(lastAttemptAt).isNotBlank();
        assertThat(Instant.parse(lastAttemptAt))
                .isAfterOrEqualTo(Instant.now().minusSeconds(60));
    }

    @Test
    void recordTendencyAttempt_nullOrUnknownId_zeroRows() {
        // Arrange + Act + Assert：null 防御返 0；不存在 id 返 0（条目已删，调用方记日志即可）
        assertThat(repository.recordTendencyAttempt(null)).isZero();
        assertThat(repository.recordTendencyAttempt(999999L)).isZero();
    }

    @Test
    void findRecentUnjudged_excludesItemsAtAttemptCap_p03() {
        // Arrange：3 条未判政策，政策A 失败留痕 3 次（达上限），B 留痕 2 次，C 未留痕
        List<PolicyItem> saved =
                repository.saveAll(
                        List.of(
                                newItem("政策A", todayMinus(1), "https://gov/cap-a", List.of()),
                                newItem("政策B", todayMinus(1), "https://gov/cap-b", List.of()),
                                newItem("政策C", todayMinus(1), "https://gov/cap-c", List.of())));
        for (int i = 0; i < 3; i++) {
            repository.recordTendencyAttempt(saved.get(0).getId());
        }
        for (int i = 0; i < 2; i++) {
            repository.recordTendencyAttempt(saved.get(1).getId());
        }

        // Act：上限 3、退避截止 now（所有留痕条目均已超窗，仅考验上限过滤）
        List<PolicyItem> pending = repository.findRecentUnjudged(7, 20, 3, Instant.now());

        // Assert：达上限的政策A 停扫（保持 UNJUDGED 不再消耗 LLM 预算）；未达上限的 B、C 仍入选
        assertThat(pending)
                .extracting(PolicyItem::getId)
                .containsExactly(saved.get(2).getId(), saved.get(1).getId());
    }

    @Test
    void findRecentUnjudged_backoffWindow_recentAttemptExcluded_oldAttemptIncluded_p03() {
        // Arrange：政策X 上次尝试在 30min 前（退避窗 2h 内）、政策Y 上次尝试在 3h 前、政策Z 从未尝试
        List<PolicyItem> saved =
                repository.saveAll(
                        List.of(
                                newItem("政策X", todayMinus(1), "https://gov/bw-x", List.of()),
                                newItem("政策Y", todayMinus(1), "https://gov/bw-y", List.of()),
                                newItem("政策Z", todayMinus(1), "https://gov/bw-z", List.of())));
        Instant now = Instant.now();
        jdbcTemplate.update(
                "UPDATE policy_item SET tendency_attempts = 1, tendency_last_attempt_at = ? WHERE id = ?",
                now.minusSeconds(1800).toString(),
                saved.get(0).getId());
        jdbcTemplate.update(
                "UPDATE policy_item SET tendency_attempts = 1, tendency_last_attempt_at = ? WHERE id = ?",
                now.minusSeconds(3 * 3600).toString(),
                saved.get(1).getId());

        // Act：退避截止 = now - 2h
        List<PolicyItem> pending = repository.findRecentUnjudged(7, 20, 3, now.minusSeconds(2 * 3600));

        // Assert：窗内的政策X 不重试；超窗的政策Y 与从未尝试的政策Z 入选
        assertThat(pending)
                .extracting(PolicyItem::getId)
                .containsExactly(saved.get(2).getId(), saved.get(1).getId());
    }

    @Test
    void findRecentUnjudged_successPathUnaffected_attemptColumnsIgnoredOnceJudged_p03() {
        // Arrange：留痕满上限的条目随后判断成功——已判条目本就出扫，留痕列不影响成功路径
        Long id =
                repository
                        .saveAll(List.of(newItem("政策S", todayMinus(1), "https://gov/s", List.of())))
                        .get(0)
                        .getId();
        for (int i = 0; i < 3; i++) {
            repository.recordTendencyAttempt(id);
        }
        repository.updateAiTendency(id, AiTendency.NEUTRAL);

        // Act + Assert：倾向落库成功，扫描不返回（ai_tendency≠0 主过滤）
        assertThat(repository.findById(id).orElseThrow().getAiTendency()).isEqualTo(AiTendency.NEUTRAL);
        assertThat(repository.findRecentUnjudged(7, 20, 3, Instant.now())).isEmpty();
    }
}
