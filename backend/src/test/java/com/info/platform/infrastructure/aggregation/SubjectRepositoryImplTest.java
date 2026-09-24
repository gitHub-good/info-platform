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
 * SubjectRepositoryImpl 集成测试（T01）：SQLite 共享内存库 + Flyway 建表后，测 save/findByCode/ existsByCode
 * 往返与更新乐观锁。@SpringBootTest 启动完整上下文（含 Flyway 迁移）。 @ActiveProfiles("test") 注入测试 profile 的认证配置（T17
 * 后上下文含 JWT 密钥等，需 test profile 提供）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectRepositoryImplTest {

    @Autowired private SubjectRepository subjectRepository;

    @Test
    void save_newSubject_thenFindById_roundTrip() {
        // Arrange
        // 注：V2/V17 迁移已播种 SH600519/SH601318 等种子标的，此处用非冲突代码验证 save→findById 往返
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601628"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("中国人寿")
                        .externalCodes(Map.of("tushare", "601628.SH", "akshare", "sh601628"))
                        .industry("保险")
                        .status(SubjectStatus.ENABLED)
                        .build();

        // Act
        Subject saved = subjectRepository.save(subject);

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<Subject> loaded = subjectRepository.findById(saved.getId());
        assertThat(loaded).isPresent();
        Subject found = loaded.get();
        assertThat(found.getSubjectCode().value()).isEqualTo("SH601628");
        assertThat(found.getMarket()).isEqualTo(Market.A_SHARE);
        assertThat(found.getSubjectType()).isEqualTo(SubjectType.STOCK);
        assertThat(found.getName()).isEqualTo("中国人寿");
        assertThat(found.getIndustry()).isEqualTo("保险");
        assertThat(found.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(found.getExternalCodes())
                .containsEntry("tushare", "601628.SH")
                .containsEntry("akshare", "sh601628");
    }

    @Test
    void seededSubject_hasEastmoneySecidInExternalCodes() {
        // V2 播种的示例标的：external_codes 含东财 secid 键（T03 行情取数键），验证 JSON TypeHandler 往返
        Optional<Subject> loaded = subjectRepository.findByCode(SubjectCode.of("SH600519"));

        assertThat(loaded).as("V2 应播种贵州茅台").isPresent();
        Subject maotai = loaded.get();
        assertThat(maotai.getName()).isEqualTo("贵州茅台");
        assertThat(maotai.getExternalCodes())
                .containsEntry("eastmoney", "1.600519")
                .containsEntry("tushare", "600519.SH");
    }

    @Test
    void findByCode_returnsSubject() {
        // Arrange
        // 注：HK00700/腾讯控股 由 V2 迁移脚本播种为示例标的，此处用非冲突代码验证 findByCode
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("HK09988"))
                        .market(Market.HK)
                        .subjectType(SubjectType.STOCK)
                        .name("阿里巴巴")
                        .externalCodes(Map.of("tushare", "09988.HK"))
                        .build();
        subjectRepository.save(subject);

        // Act
        Optional<Subject> loaded = subjectRepository.findByCode(SubjectCode.of("HK09988"));

        // Assert
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("阿里巴巴");
        assertThat(loaded.get().getMarket()).isEqualTo(Market.HK);
    }

    @Test
    void existsByCode_returnsTrueAfterSave() {
        // Arrange
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("INDEX_SH000001"))
                        .market(Market.INDEX)
                        .subjectType(SubjectType.INDEX)
                        .name("上证指数")
                        .build();
        subjectRepository.save(subject);

        // Act
        boolean exists = subjectRepository.existsByCode(SubjectCode.of("INDEX_SH000001"));

        // Assert
        assertThat(exists).isTrue();
    }

    @Test
    void save_updateExistingSubject_incrementsVersion() {
        // Arrange（SH600036 招商银行已由 V17 播种，换非冲突代码验证更新乐观锁）
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601169"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("北京银行")
                        .build();
        Subject saved = subjectRepository.save(subject);
        assertThat(saved.getVersion()).isZero();

        // Act
        saved.rename("北京银行股份有限公司");
        Subject updated = subjectRepository.save(saved);

        // Assert
        assertThat(updated.getVersion()).isEqualTo(1L);

        Optional<Subject> reloaded = subjectRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("北京银行股份有限公司");
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }

    @Test
    void findFirstActive_returnsSeedSubject_numericStatusMatch() {
        // T36 连通性测试探针：status 列为整型（SubjectStatus.code），按枚举名匹配会空手而归（冒烟发现的缺陷回归）
        Optional<Subject> first = subjectRepository.findFirstActive();

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(first.orElseThrow().getSubjectCode().value()).isEqualTo("SH600519");
    }

    // ---- searchEnabled 模糊搜索（体检 P1-2，V2+V17 种子为数据基础） ----

    @Test
    void searchEnabled_byNamePartial_returnsSeedSubject() {
        List<Subject> found = subjectRepository.searchEnabled("五粮", 20);

        assertThat(found).extracting(s -> s.getSubjectCode().value()).contains("SZ000858");
    }

    @Test
    void searchEnabled_byCode_caseInsensitive_matchesUpperCaseCode() {
        // SQLite LIKE 对 ASCII 大小写不敏感：小写查询命中大写代码
        List<Subject> found = subjectRepository.searchEnabled("sh600519", 20);

        assertThat(found).extracting(s -> s.getSubjectCode().value()).contains("SH600519");
    }

    @Test
    void searchEnabled_matchesBothCodeAndName() {
        // 代码 contains（300 命指数沪深300 SH000300）与名称 contains（宁德时代）各命中一条
        List<Subject> byCodePart = subjectRepository.searchEnabled("000300", 20);
        List<Subject> byName = subjectRepository.searchEnabled("宁德时代", 20);

        assertThat(byCodePart).extracting(s -> s.getSubjectCode().value()).contains("SH000300");
        assertThat(byName).hasSize(1).first().extracting(s -> s.getName()).isEqualTo("宁德时代");
    }

    @Test
    void searchEnabled_noMatch_returnsEmptyList() {
        assertThat(subjectRepository.searchEnabled("不存在标的xyz", 20)).isEmpty();
    }

    @Test
    void searchEnabled_respectsLimit() {
        // 名称含「银行」的种子 ≥ 4 只（招商/工商/农业/兴业/浦发/平安…），limit=2 只返回 2 条
        List<Subject> found = subjectRepository.searchEnabled("银行", 2);

        assertThat(found).hasSize(2);
    }

    @Test
    void searchEnabled_escapesLikeWildcards_literalPercentMatchesNothing() {
        // 用户输入 % 不构成通配符（不整表扫描），按字面匹配 → 无命中
        assertThat(subjectRepository.searchEnabled("%", 50)).isEmpty();
    }

    @Test
    void searchEnabled_v17SeedHasEastmoneySecid() {
        // V17 扩容种子带东财 secid（行情取数键），抽查两只
        Optional<Subject> cmb = subjectRepository.findByCode(SubjectCode.of("SH600036"));
        assertThat(cmb).isPresent();
        assertThat(cmb.orElseThrow().getExternalCodes()).containsEntry("eastmoney", "1.600036");

        Optional<Subject> catl = subjectRepository.findByCode(SubjectCode.of("SZ300750"));
        assertThat(catl).isPresent();
        assertThat(catl.orElseThrow().getExternalCodes()).containsEntry("eastmoney", "0.300750");
    }

    // ---- findAllById 批量取数（体检 P1-2 自选清单行情列） ----

    @Test
    void findAllById_returnsKnownSeeds_sortedByIdSkipsUnknown() {
        // 茅台(id=1, V2) + 宁德时代(V17)；999999 不存在跳过；乱序入参按 id 升序返回
        Optional<Subject> catl = subjectRepository.findByCode(SubjectCode.of("SZ300750"));
        assertThat(catl).isPresent();
        List<Long> ids = List.of(catl.orElseThrow().getId(), 1L, 999_999L);

        List<Subject> found = subjectRepository.findAllById(ids);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getSubjectCode().value()).isEqualTo("SH600519");
        assertThat(found.get(1).getSubjectCode().value()).isEqualTo("SZ300750");
    }

    @Test
    void findAllById_emptyOrAllUnknown_returnsEmptyList() {
        assertThat(subjectRepository.findAllById(List.of())).isEmpty();
        assertThat(subjectRepository.findAllById(List.of(999_998L, 999_999L))).isEmpty();
    }

    @Test
    void findAllById_deduplicatesRepeatedIds() {
        List<Subject> found = subjectRepository.findAllById(List.of(1L, 1L, 1L));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSubjectCode().value()).isEqualTo("SH600519");
    }
}
