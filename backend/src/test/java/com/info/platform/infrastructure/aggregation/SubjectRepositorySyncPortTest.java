package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * SubjectRepository 同步端口集成测试（T51，技术方案增补 §4.4 关键 SQL ①②③⑤）：SQLite 共享内存库 + Flyway V18 建列后 直连断言——
 * 批量幂等新增计数 / 桶基线双条件圈定 / 快照更新不碰 status 与 missing_streak / 回归清零零写入 / 缺失计数仅启用标的 / V18 存量行默认 0。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectRepositorySyncPortTest {

    @Autowired private SubjectRepository subjectRepository;

    // ---- SQL ① insertIgnoreBatch ----

    @Test
    void insertIgnoreBatch_mixedList_returnsActualInsertedCount() {
        // Arrange: HK00700 为 V2 种子（UNIQUE 冲突被 IGNORE），另两只为新代码
        List<Subject> batch =
                List.of(
                        newSubject("HK01810", "小米集团-W"),
                        newSubject("HK08475", "朝聚眼科"),
                        newSubject("HK00700", "腾讯控股"));

        // Act
        int inserted = subjectRepository.insertIgnoreBatch(batch);

        // Assert: 冲突行不计入（幂等重跑计数归零的前提）
        assertThat(inserted).isEqualTo(2);
        Optional<Subject> exists = subjectRepository.findByCode(SubjectCode.of("HK01810"));
        assertThat(exists).isPresent();
        assertThat(exists.orElseThrow().getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(exists.orElseThrow().getMissingStreak()).isZero();
        assertThat(exists.orElseThrow().getExternalCodes())
                .containsEntry("eastmoney", "116.01810")
                .containsEntry("tushare", "01810.HK");
    }

    @Test
    void insertIgnoreBatch_rerunSameList_insertsNothing() {
        List<Subject> batch =
                List.of(newSubject("SH601988", "中国银行"), newSubject("SZ003816", "中国广核"));

        assertThat(subjectRepository.insertIgnoreBatch(batch)).isEqualTo(2);
        // 幂等：同批重跑全被 IGNORE，计数 0（V17 先例语义）
        assertThat(subjectRepository.insertIgnoreBatch(batch)).isZero();
    }

    @Test
    void insertIgnoreBatch_emptyList_noOp() {
        assertThat(subjectRepository.insertIgnoreBatch(List.of())).isZero();
    }

    // ---- SQL ⑤ loadBucket ----

    @Test
    void loadBucket_filtersByMarketAndType() {
        // Act: A_SHARE+STOCK 桶——V2/V17 种子的 A 股股票在内，港股/指数/板块行不在
        List<Subject> bucket = subjectRepository.loadBucket(Market.A_SHARE, SubjectType.STOCK);

        // Assert
        assertThat(bucket)
                .extracting(s -> s.getSubjectCode().value())
                .contains("SH600519", "SZ300750")
                .doesNotContain("HK00700", "SH000001");
        assertThat(bucket).allSatisfy(s -> assertThat(s.getMarket()).isEqualTo(Market.A_SHARE));
        assertThat(bucket)
                .allSatisfy(s -> assertThat(s.getSubjectType()).isEqualTo(SubjectType.STOCK));
        // 不筛状态（停用行也要进 diff 基线做比对与「已停用不再计数」判定）
        Subject disabledRow =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601996"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("停用行也进桶基线")
                        .status(SubjectStatus.DISABLED)
                        .build();
        subjectRepository.save(disabledRow);
        assertThat(subjectRepository.loadBucket(Market.A_SHARE, SubjectType.STOCK))
                .extracting(s -> s.getSubjectCode().value())
                .contains("SH601996");
    }

    // ---- V18 迁移 ----

    @Test
    void v18_migration_existingRowsMissingStreakDefaultsZero() {
        // V18 ADD COLUMN NOT NULL DEFAULT 0：存量种子（含指数桶 V17 行）无观察态
        List<Subject> seeds = subjectRepository.loadBucket(Market.INDEX, SubjectType.INDEX);
        assertThat(seeds).isNotEmpty();
        assertThat(seeds).allSatisfy(s -> assertThat(s.getMissingStreak()).isZero());
    }

    // ---- SQL ② updateSnapshot ----

    @Test
    void updateSnapshot_changesNameIndustryCodes_bumpsVersion_keepsStatusAndStreak() {
        // Arrange: 先把 streak 推到 1（缺失一轮），验证快照更新不动它
        Subject subject = subjectRepository.save(newSubject("SH601999", "原始名称"));
        assertThat(subjectRepository.incrementMissingStreak("SH601999")).isEqualTo(1);
        long versionBefore =
                subjectRepository.findByCode(SubjectCode.of("SH601999")).orElseThrow().getVersion();

        // Act: 名称/行业/取数键三列更新（industry null → 有值、codes 补 eastmoney 键）
        int affected =
                subjectRepository.updateSnapshot(
                        "SH601999",
                        "新名称",
                        "新行业",
                        Map.of("eastmoney", "1.601999", "tushare", "601999.SH"));

        // Assert
        assertThat(affected).isEqualTo(1);
        Subject updated = subjectRepository.findByCode(SubjectCode.of("SH601999")).orElseThrow();
        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getIndustry()).isEqualTo("新行业");
        assertThat(updated.getExternalCodes())
                .containsEntry("eastmoney", "1.601999")
                .containsEntry("tushare", "601999.SH");
        assertThat(updated.getVersion()).isEqualTo(versionBefore + 1);
        // REQ 红线：更新不碰 status / missing_streak
        assertThat(updated.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(updated.getMissingStreak()).isEqualTo(1);
    }

    @Test
    void updateSnapshot_unknownCode_returnsZero() {
        int affected =
                subjectRepository.updateSnapshot(
                        "SH999999", "名称", null, Map.of("eastmoney", "1.999999"));
        assertThat(affected).isZero();
    }

    // ---- 回归清零 ----

    @Test
    void clearMissingStreak_resetsOnlyWhenPositive() {
        subjectRepository.save(newSubject("SZ000999", "招商证券"));
        // streak=0 时清零：零写入（affected=0）
        assertThat(subjectRepository.clearMissingStreak("SZ000999")).isZero();

        // 推到 2 后清零：affected=1 且归 0
        subjectRepository.incrementMissingStreak("SZ000999");
        assertThat(subjectRepository.incrementMissingStreak("SZ000999")).isEqualTo(1);
        assertThat(subjectRepository.clearMissingStreak("SZ000999")).isEqualTo(1);
        assertThat(
                        subjectRepository
                                .findByCode(SubjectCode.of("SZ000999"))
                                .orElseThrow()
                                .getMissingStreak())
                .isZero();
    }

    // ---- SQL ③ incrementMissingStreak ----

    @Test
    void incrementMissingStreak_advancesForEnabled_only() {
        // 启用标的：逐轮 +1
        subjectRepository.save(newSubject("SH600999", "浙能电力"));
        assertThat(subjectRepository.incrementMissingStreak("SH600999")).isEqualTo(1);
        assertThat(subjectRepository.incrementMissingStreak("SH600999")).isEqualTo(1);
        assertThat(
                        subjectRepository
                                .findByCode(SubjectCode.of("SH600999"))
                                .orElseThrow()
                                .getMissingStreak())
                .isEqualTo(2);

        // 停用标的：WHERE status=1 守卫 → 0 受影响、计数不动（已停用不再计数）
        Subject disabled =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601998"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("已停用标的")
                        .status(SubjectStatus.DISABLED)
                        .build();
        subjectRepository.save(disabled);
        assertThat(subjectRepository.incrementMissingStreak("SH601998")).isZero();
        assertThat(
                        subjectRepository
                                .findByCode(SubjectCode.of("SH601998"))
                                .orElseThrow()
                                .getMissingStreak())
                .isZero();

        // 不存在的代码：0
        assertThat(subjectRepository.incrementMissingStreak("SH999998")).isZero();
    }

    // ---- helpers ----

    private static Subject newSubject(String code, String name) {
        boolean hk = code.startsWith("HK");
        return Subject.builder()
                .subjectCode(SubjectCode.of(code))
                .market(hk ? Market.HK : Market.A_SHARE)
                .subjectType(SubjectType.STOCK)
                .name(name)
                .externalCodes(
                        Map.of(
                                "eastmoney",
                                (hk ? "116." : "1.") + code.substring(2),
                                "tushare",
                                code.substring(2) + (hk ? ".HK" : ".SH")))
                .build();
    }
}
