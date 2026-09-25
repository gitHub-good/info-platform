package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.SourceCode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** SectionPageSettingsImpl 单测（M12 T90）：公告/新闻页大小运行时读取——DB 值优先、键缺失/非数回落 DataSourceDefaults 代码缺省。 */
class SectionPageSettingsImplTest {

    private final ConfigCenter configCenter = Mockito.mock(ConfigCenter.class);

    @Test
    void announcePageSize_readsRuntimeValueOverDefault() {
        when(configCenter.dataSource(SourceCode.ANNOUNCE))
                .thenReturn(
                        new RuntimeDataSource(
                                SourceCode.ANNOUNCE,
                                true,
                                RuntimeDataSource.Mode.REAL,
                                2000,
                                0,
                                300,
                                30,
                                java.util.Map.of("announcePageSize", 25)));

        assertThat(new SectionPageSettingsImpl(configCenter).announcePageSize()).isEqualTo(25);
    }

    @Test
    void newsPageSize_fallsBackToCodeDefault_whenKeyMissing() {
        when(configCenter.dataSource(SourceCode.NEWS))
                .thenReturn(
                        new RuntimeDataSource(
                                SourceCode.NEWS,
                                true,
                                RuntimeDataSource.Mode.REAL,
                                2000,
                                0,
                                120,
                                30,
                                java.util.Map.of()));

        // 键缺失 → DataSourceDefaults 代码缺省（M12：newsPageSize 20）
        assertThat(new SectionPageSettingsImpl(configCenter).newsPageSize()).isEqualTo(20);
    }

    @Test
    void announcePageSize_fallsBackToCodeDefault_whenParamNotNumeric() {
        when(configCenter.dataSource(SourceCode.ANNOUNCE))
                .thenReturn(
                        new RuntimeDataSource(
                                SourceCode.ANNOUNCE,
                                true,
                                RuntimeDataSource.Mode.REAL,
                                2000,
                                0,
                                300,
                                30,
                                java.util.Map.of("announcePageSize", "not-a-number")));

        assertThat(new SectionPageSettingsImpl(configCenter).announcePageSize()).isEqualTo(10);
    }
}
