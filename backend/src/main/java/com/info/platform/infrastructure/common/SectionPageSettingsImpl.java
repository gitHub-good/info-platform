package com.info.platform.infrastructure.common;

import com.info.platform.application.aggregation.SectionPageSettings;
import com.info.platform.domain.aggregation.SourceCode;
import org.springframework.stereotype.Component;

/**
 * {@link SectionPageSettings} 实现（M12 T90）：每请求读 {@code datasource.{CODE}} 快照 params（LIVE 级热生效），
 * 键缺失/解析异常回落 {@link DataSourceDefaults} 代码内置缺省（与 {@code AggregationRuntimeSettingsImpl} 同款模式）。
 */
@Component
public class SectionPageSettingsImpl implements SectionPageSettings {

    /** 键链整体缺失时的最终代码缺省（与 DataSourceDefaults 种子同值，防漂移兜底）。 */
    private static final int DEFAULT_ANNOUNCE_PAGE_SIZE = 10;

    private static final int DEFAULT_NEWS_PAGE_SIZE = 20;

    private final ConfigCenter configCenter;

    public SectionPageSettingsImpl(ConfigCenter configCenter) {
        this.configCenter = configCenter;
    }

    @Override
    public int announcePageSize() {
        return configCenter
                .dataSource(SourceCode.ANNOUNCE)
                .paramInt(
                        "announcePageSize",
                        DataSourceDefaults.paramInt(
                                SourceCode.ANNOUNCE,
                                "announcePageSize",
                                DEFAULT_ANNOUNCE_PAGE_SIZE));
    }

    @Override
    public int newsPageSize() {
        return configCenter
                .dataSource(SourceCode.NEWS)
                .paramInt(
                        "newsPageSize",
                        DataSourceDefaults.paramInt(
                                SourceCode.NEWS, "newsPageSize", DEFAULT_NEWS_PAGE_SIZE));
    }
}
