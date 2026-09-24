package com.info.platform.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyListFilter;
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

    private static PolicyItem newSummaryItem(
            String title, String date, String url, String summary, List<String> industries) {
        return PolicyItem.create(title, "国务院政策", LocalDate.parse(date), summary, industries, url);
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
                        .saveAll(
                                List.of(
                                        newItem(
                                                "政策V16",
                                                todayMinus(1),
                                                "https://gov/v16",
                                                List.of())))
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
        assertThat(Instant.parse(lastAttemptAt)).isAfterOrEqualTo(Instant.now().minusSeconds(60));
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
        List<PolicyItem> pending =
                repository.findRecentUnjudged(7, 20, 3, now.minusSeconds(2 * 3600));

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
        assertThat(repository.findById(id).orElseThrow().getAiTendency())
                .isEqualTo(AiTendency.NEUTRAL);
        assertThat(repository.findRecentUnjudged(7, 20, 3, Instant.now())).isEmpty();
    }

    // ==================== M9 T60：页码模式（findPage/countByFilter，真实 SQLite） ====================

    @Test
    void findPage_paginatesNewestFirst_andCountMatches() {
        // Arrange：按插入序落 5 条（id 升序），均近 7 天内
        for (int i = 1; i <= 5; i++) {
            repository.saveAll(
                    List.of(newItem("政策" + i, todayMinus(1), "https://gov/p" + i, List.of())));
        }
        PolicyListFilter filter = new PolicyListFilter(7, null, null);

        // Act + Assert：第 1 页（size=2）newest-first → 政策5、政策4；计数 5
        List<PolicyItem> page1 = repository.findPage(filter, 1, 2);
        assertThat(page1).extracting(PolicyItem::getTitle).containsExactly("政策5", "政策4");
        assertThat(repository.countByFilter(filter)).isEqualTo(5);

        // 第 2 页 → 政策3、政策2；第 3 页 → 政策1（不满页）
        assertThat(repository.findPage(filter, 2, 2))
                .extracting(PolicyItem::getTitle)
                .containsExactly("政策3", "政策2");
        assertThat(repository.findPage(filter, 3, 2))
                .extracting(PolicyItem::getTitle)
                .containsExactly("政策1");
    }

    @Test
    void findPage_outOfRangePage_returnsEmptyList_countUnchanged() {
        // Arrange：2 条数据，page=99 越界
        for (int i = 1; i <= 2; i++) {
            repository.saveAll(
                    List.of(newItem("政策" + i, todayMinus(1), "https://gov/o" + i, List.of())));
        }
        PolicyListFilter filter = new PolicyListFilter(7, null, null);

        // Act + Assert：offset 语义天然空列表（200 + 空列表 + 如实回显的契约由上层承接）；total 仍为真实值
        assertThat(repository.findPage(filter, 99, 20)).isEmpty();
        assertThat(repository.countByFilter(filter)).isEqualTo(2);
    }

    @Test
    void findPage_respectsDaysWindow_andIndustryCombo() {
        // Arrange：窗内白酒/银行各 1 条 + 窗外白酒 1 条 → 组合过滤计数按 AND 语义
        repository.saveAll(
                List.of(
                        newItem("白酒新政策", todayMinus(1), "https://gov/c1", List.of("白酒")),
                        newItem("银行新政策", todayMinus(2), "https://gov/c2", List.of("银行")),
                        newItem("白酒旧政策", todayMinus(30), "https://gov/c3", List.of("白酒"))));

        // Act + Assert：7 天窗 + 白酒 → 仅 1 条；7 天窗全量 → 2 条
        assertThat(repository.countByFilter(new PolicyListFilter(7, "白酒", null))).isEqualTo(1);
        assertThat(repository.findPage(new PolicyListFilter(7, "白酒", null), 1, 20))
                .extracting(PolicyItem::getTitle)
                .containsExactly("白酒新政策");
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, null))).isEqualTo(2);
    }

    @Test
    void findPage_daysClamped_zeroToDefault_over90To90() {
        // Arrange：95 天前 1 条（永不入窗）+ 90 天前 1 条（恰在最大窗边界，>= 含端点）+ 8 天前 1 条
        repository.saveAll(
                List.of(
                        newItem("超窗政策", todayMinus(95), "https://gov/cl1", List.of()),
                        newItem("边界政策", todayMinus(90), "https://gov/cl2", List.of()),
                        newItem("窗内政策", todayMinus(8), "https://gov/cl3", List.of())));

        // Act + Assert：days=0 取默认 7（8 天前不可见）；days=30 仅 8 天前可见；
        // days=200 截 90 → 边界政策（90 天前）恰入窗（>= 含端点）、超窗政策出窗
        assertThat(repository.countByFilter(new PolicyListFilter(0, null, null))).isZero();
        assertThat(repository.countByFilter(new PolicyListFilter(30, null, null))).isEqualTo(1);
        assertThat(repository.countByFilter(new PolicyListFilter(200, null, null))).isEqualTo(2);
        assertThat(repository.countByFilter(new PolicyListFilter(90, null, null))).isEqualTo(2);
    }

    @Test
    void findPage_firstPageEqualsCursorFirstPage_sameParams_regressionAnchor() {
        // Arrange：25 条近 7 天政策 → 回归锚点：同参数下页码第 1 页与游标首页内容一致（§3.5 两模式同序同过滤）
        for (int i = 1; i <= 25; i++) {
            repository.saveAll(
                    List.of(
                            newItem(
                                    "锚点政策" + i,
                                    todayMinus(1),
                                    "https://gov/anchor" + i,
                                    List.of("白酒"))));
        }

        // Act
        List<PolicyItem> pagedFirst =
                repository.findPage(new PolicyListFilter(7, "白酒", null), 1, 20);
        List<PolicyItem> cursorFirst = repository.findRecent(7, "白酒", null, 20);

        // Assert：逐条 id 一致（newest-first 同序）
        assertThat(pagedFirst)
                .extracting(PolicyItem::getId)
                .containsExactlyElementsOf(cursorFirst.stream().map(PolicyItem::getId).toList());
    }

    @Test
    void findPage_deepPage_over100Rows_lastPagePartial() {
        // Arrange：105 条（5 整页 size=20 + 末页 5 条），验证 LIMIT/OFFSET 深页正确性（ADR-0035 从简实现）
        List<PolicyItem> batch = new java.util.ArrayList<>(105);
        for (int i = 1; i <= 105; i++) {
            batch.add(newItem("深页政策" + i, todayMinus(1), "https://gov/deep" + i, List.of()));
        }
        repository.saveAll(batch);
        PolicyListFilter filter = new PolicyListFilter(7, null, null);

        // Act + Assert：total=105；第 6 页（末页）恰 5 条且为最旧的 5 条（id 最小）；第 7 页空
        assertThat(repository.countByFilter(filter)).isEqualTo(105);
        List<PolicyItem> lastPage = repository.findPage(filter, 6, 20);
        assertThat(lastPage).hasSize(5);
        assertThat(lastPage.get(0).getTitle()).isEqualTo("深页政策5");
        assertThat(lastPage.get(4).getTitle()).isEqualTo("深页政策1");
        assertThat(repository.findPage(filter, 7, 20)).isEmpty();
    }

    // ==================== M9 T62：关键词搜索（LIKE ESCAPE，真实 SQLite） ====================

    @Test
    void findPage_keyword_matchesTitleOrSummary_nullSummaryNotHit() {
        // Arrange：标题命中 1 条 + 摘要命中 1 条 + 无关 1 条（summary 为 NULL）
        repository.saveAll(
                List.of(
                        newItem("半导体产业规划", todayMinus(1), "https://gov/k1", List.of()),
                        newSummaryItem(
                                "其他产业规划", todayMinus(1), "https://gov/k2", "措施涉及半导体设备", List.of()),
                        newItem("白酒产业规划", todayMinus(1), "https://gov/k3", List.of())));

        // Act + Assert：title OR summary 任一命中 → 2 条；NULL summary 不命中（SQL 天然语义）
        PolicyListFilter filter = new PolicyListFilter(7, null, "半导体");
        assertThat(repository.countByFilter(filter)).isEqualTo(2);
        assertThat(repository.findPage(filter, 1, 20))
                .extracting(PolicyItem::getTitle)
                .containsExactly("其他产业规划", "半导体产业规划"); // newest-first（后插入在前）
    }

    @Test
    void findPage_keyword_noMatch_returnsEmpty() {
        repository.saveAll(List.of(newItem("白酒政策", todayMinus(1), "https://gov/k9", List.of())));

        assertThat(repository.findPage(new PolicyListFilter(7, null, "半导体"), 1, 20)).isEmpty();
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "导体半"))).isZero();
    }

    @Test
    void findPage_keyword_wildcardLiterals_escapedAsPlainText() {
        // Arrange：标题/摘要含通配符字面量 % _ \，及「形似通配」的对照行
        repository.saveAll(
                List.of(
                        newItem("增长100%的规划", todayMinus(1), "https://gov/w1", List.of()),
                        newItem("增长100x的规划", todayMinus(1), "https://gov/w2", List.of()),
                        newItem("a_b 合作协议", todayMinus(1), "https://gov/w3", List.of()),
                        newItem("axb 合作协议", todayMinus(1), "https://gov/w4", List.of()),
                        newSummaryItem(
                                "路径说明",
                                todayMinus(1),
                                "https://gov/w5",
                                "目录 C:\\data",
                                List.of())));

        // Act + Assert：keyword 含 % 仅字面命中（对照行 100x 不命中——% 未被当任意串通配）
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "100%"))).isEqualTo(1);
        assertThat(repository.findPage(new PolicyListFilter(7, null, "100%"), 1, 20))
                .extracting(PolicyItem::getTitle)
                .containsExactly("增长100%的规划");

        // keyword 含 _ 仅字面命中（axb 不命中——_ 未被当单字通配）
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "a_b"))).isEqualTo(1);
        assertThat(repository.findPage(new PolicyListFilter(7, null, "a_b"), 1, 20))
                .extracting(PolicyItem::getTitle)
                .containsExactly("a_b 合作协议");

        // keyword 含 \ 仅字面命中（summary 命中路径）
        assertThat(repository.findPage(new PolicyListFilter(7, null, "C:\\data"), 1, 20))
                .extracting(PolicyItem::getTitle)
                .containsExactly("路径说明");
    }

    @Test
    void findPage_keyword_asciiCaseInsensitive_chineseExact() {
        // Arrange：ASCII 大小写混合标题 + 中文标题（SQLite LIKE 口径：ASCII 不区分大小写、非 ASCII 按字节原样，ADR-0035）
        repository.saveAll(
                List.of(
                        newItem("Chip Export Policy", todayMinus(1), "https://gov/c1", List.of()),
                        newItem("白酒消费税调整", todayMinus(1), "https://gov/c2", List.of())));

        // Act + Assert：英文关键词大小写互命中
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "chip"))).isEqualTo(1);
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "CHIP"))).isEqualTo(1);
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "export policy")))
                .isEqualTo(1);

        // 中文关键词按字节精确匹配（「白酒消费」子串命中；乱序不命中）
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "白酒消费"))).isEqualTo(1);
        assertThat(repository.countByFilter(new PolicyListFilter(7, null, "费税白酒"))).isZero();
    }

    @Test
    void findPage_daysIndustryKeywordPage_fullCombo_andSemantics() {
        // Arrange：电子行业内 3 条含「半导体」（1 条 30 天前出 days=7 窗）+ 其他行业 1 条含「半导体」+ 电子行业不含关键词 1 条
        repository.saveAll(
                List.of(
                        newSummaryItem(
                                "半导体规划一", todayMinus(1), "https://gov/x1", null, List.of("电子")),
                        newSummaryItem("半导体规划二", todayMinus(2), null, "含半导体摘要", List.of("电子")),
                        newSummaryItem("半导体旧规划", todayMinus(30), null, null, List.of("电子")),
                        newSummaryItem("半导体银行指引", todayMinus(1), null, null, List.of("银行")),
                        newSummaryItem("电子其他政策", todayMinus(1), null, null, List.of("电子"))));

        // Act：days=7 + industry=电子 + keyword=半导体 → 2 条（窗内 + 行业 + 关键词 AND 交集）
        PolicyListFilter combo = new PolicyListFilter(7, "电子", "半导体");
        assertThat(repository.countByFilter(combo)).isEqualTo(2);

        // 分页：size=1 第 1 页 newest-first（规划二后插入在前），第 2 页剩 1 条，第 3 页空
        assertThat(repository.findPage(combo, 1, 1))
                .extracting(PolicyItem::getTitle)
                .containsExactly("半导体规划二");
        assertThat(repository.findPage(combo, 2, 1))
                .extracting(PolicyItem::getTitle)
                .containsExactly("半导体规划一");
        assertThat(repository.findPage(combo, 3, 1)).isEmpty();

        // 放宽 days=30 → 3 条（旧规划入窗）
        assertThat(repository.countByFilter(new PolicyListFilter(30, "电子", "半导体"))).isEqualTo(3);
    }
}
