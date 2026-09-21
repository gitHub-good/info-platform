package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
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
        // 注：SH600519/贵州茅台 由 V2 迁移脚本播种为示例标的，此处用非冲突代码验证 save→findById 往返
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH601318"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("中国平安")
                        .externalCodes(Map.of("tushare", "601318.SH", "akshare", "sh601318"))
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
        assertThat(found.getSubjectCode().value()).isEqualTo("SH601318");
        assertThat(found.getMarket()).isEqualTo(Market.A_SHARE);
        assertThat(found.getSubjectType()).isEqualTo(SubjectType.STOCK);
        assertThat(found.getName()).isEqualTo("中国平安");
        assertThat(found.getIndustry()).isEqualTo("保险");
        assertThat(found.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(found.getExternalCodes())
                .containsEntry("tushare", "601318.SH")
                .containsEntry("akshare", "sh601318");
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
        // Arrange
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH600036"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("招商银行")
                        .build();
        Subject saved = subjectRepository.save(subject);
        assertThat(saved.getVersion()).isZero();

        // Act
        saved.rename("招商银行股份有限公司");
        Subject updated = subjectRepository.save(saved);

        // Assert
        assertThat(updated.getVersion()).isEqualTo(1L);

        Optional<Subject> reloaded = subjectRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getName()).isEqualTo("招商银行股份有限公司");
        assertThat(reloaded.get().getVersion()).isEqualTo(1L);
    }
}
