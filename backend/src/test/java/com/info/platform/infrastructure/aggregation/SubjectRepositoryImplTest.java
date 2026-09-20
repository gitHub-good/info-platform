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

/**
 * SubjectRepositoryImpl 集成测试（T01）：SQLite 共享内存库 + Flyway 建表后，测 save/findByCode/ existsByCode
 * 往返与更新乐观锁。@SpringBootTest 启动完整上下文（含 Flyway 迁移）。
 */
@SpringBootTest
class SubjectRepositoryImplTest {

    @Autowired private SubjectRepository subjectRepository;

    @Test
    void save_newSubject_thenFindById_roundTrip() {
        // Arrange
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("SH600519"))
                        .market(Market.A_SHARE)
                        .subjectType(SubjectType.STOCK)
                        .name("贵州茅台")
                        .externalCodes(Map.of("tushare", "600519.SH", "akshare", "sh600519"))
                        .industry("白酒")
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
        assertThat(found.getSubjectCode().value()).isEqualTo("SH600519");
        assertThat(found.getMarket()).isEqualTo(Market.A_SHARE);
        assertThat(found.getSubjectType()).isEqualTo(SubjectType.STOCK);
        assertThat(found.getName()).isEqualTo("贵州茅台");
        assertThat(found.getIndustry()).isEqualTo("白酒");
        assertThat(found.getStatus()).isEqualTo(SubjectStatus.ENABLED);
        assertThat(found.getExternalCodes())
                .containsEntry("tushare", "600519.SH")
                .containsEntry("akshare", "sh600519");
    }

    @Test
    void findByCode_returnsSubject() {
        // Arrange
        Subject subject =
                Subject.builder()
                        .subjectCode(SubjectCode.of("HK00700"))
                        .market(Market.HK)
                        .subjectType(SubjectType.STOCK)
                        .name("腾讯控股")
                        .externalCodes(Map.of("tushare", "00700.HK"))
                        .build();
        subjectRepository.save(subject);

        // Act
        Optional<Subject> loaded = subjectRepository.findByCode(SubjectCode.of("HK00700"));

        // Assert
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("腾讯控股");
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
