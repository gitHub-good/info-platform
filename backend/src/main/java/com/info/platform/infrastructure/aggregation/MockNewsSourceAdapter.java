package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 新闻源 mock adapter（T09 骨架，验证 FAILED 降级路径）。
 *
 * <p>T36 起经 {@link RoutingSourceAdapter} 按 {@code datasource.NEWS.mode} 路由生效。 本 mock 的 doFetch 抛异常，
 * 经模板弹性降级后 {@link #onDegraded} 覆写为 {@link SourceResult#failed}（默认返回 MISSING，此处演示 FAILED 语义），
 * 验证「单源异常不阻断其他分区」。
 *
 * <p>M12 T92：{@code fetchPage} 覆写——page=1 返回两条命中 + {@code hasMore:true}；page≥2 空列表 +
 * {@code hasMore:false}（流耗尽信号，PRD 场景 5「mock 下不报错」口径，不模拟真实深翻； 首屏 doFetch 仍走 FAILED
 * 演示路径不受影响）。
 */
public class MockNewsSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(
                    new FieldMapping("items", "items", Transform.NONE),
                    new FieldMapping("size", "size", Transform.NONE),
                    new FieldMapping("hasMore", "hasMore", Transform.NONE));

    public MockNewsSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        super(cache, fieldMapper, runner, breaker);
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.NEWS;
    }

    @Override
    protected String sourceLabel() {
        return "新闻源(mock)";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return List.of();
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        throw new RuntimeException("新闻源暂时不可用(mock)");
    }

    /** mock 分页：page=1 两条命中 + hasMore:true；page≥2 空列表 + hasMore:false（不模拟真实深翻）。 */
    @Override
    public SourceResult fetchPage(Subject subject, int page, int size) {
        return runGuarded(subject, () -> mockPage(subject, page));
    }

    private Optional<RawFetch> mockPage(Subject subject, int page) {
        if (page > 1) {
            return Optional.of(
                    new RawFetch(
                            Map.of("items", List.of(), "size", 2, "hasMore", false),
                            sourceLabel(),
                            Instant.now()));
        }
        List<Map<String, Object>> items =
                List.of(
                        Map.<String, Object>of(
                                "externalId", "sinacn-mock-1",
                                "title", "贵州茅台相关新闻(mock)",
                                "publishedAt", "2026-09-21T10:00:00",
                                "url", "https://example.com/news/1"),
                        Map.<String, Object>of(
                                "externalId", "sinacn-mock-2",
                                "title", "白酒板块动态(mock)",
                                "publishedAt", "2026-09-21T09:00:00",
                                "url", "https://example.com/news/2"));
        return Optional.of(
                new RawFetch(
                        Map.of("items", items, "size", 2, "hasMore", true),
                        sourceLabel(),
                        Instant.now()));
    }

    @Override
    protected SourceResult onDegraded(Subject subject, String reason) {
        return SourceResult.failed(SourceCode.NEWS, subject.getId(), "新闻源(mock)");
    }
}
