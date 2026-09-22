package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 公告源 mock adapter（T09 骨架）。真实公告 adapter（T05）落地后设 {@code adapter.mock.enabled=false} 切换。
 *
 * <p>返回固定假数据（公告列表，承载于 "items" 键），状态 OK。验证列表型分区经 FieldMapper 透传后由应用层提取。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockAnnounceSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> MAPPING =
            List.of(new FieldMapping("items", "items", Transform.NONE));

    public MockAnnounceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.ANNOUNCE;
    }

    /** T31：与真实 AnnounceSourceAdapter 对齐（上市公司公告），mock/real 支持范围一致。 */
    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        return EnumSet.of(SubjectType.STOCK);
    }

    @Override
    protected String sourceLabel() {
        return "公告源(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return MAPPING;
    }

    @Override
    protected ResilienceSpec resilienceSpec() {
        return ResilienceSpec.noRetry(Duration.ofSeconds(2));
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        List<Map<String, Object>> items =
                List.of(
                        Map.<String, Object>of(
                                "title", "2026年半年度报告",
                                "url", "https://example.com/ann/1",
                                "publishedAt", "2026-08-30"),
                        Map.<String, Object>of(
                                "title", "关于召开临时股东大会的通知",
                                "url", "https://example.com/ann/2",
                                "publishedAt", "2026-09-15"));
        return Optional.of(new RawFetch(Map.of("items", items), "公告源(mock)", Instant.now()));
    }
}
