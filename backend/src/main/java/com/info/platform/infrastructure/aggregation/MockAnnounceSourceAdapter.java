package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 公告源 mock adapter（T09 骨架；T36 起经 {@link RoutingSourceAdapter} 按 {@code datasource.ANNOUNCE.mode}
 * 路由生效）。
 *
 * <p>返回固定假数据（公告列表，承载于 "items" 键），状态 OK。验证列表型分区经 FieldMapper 透传后由应用层提取。
 *
 * <p>M12 T90：{@code doFetch} 附带分页元数据（total/paginationSupported/moreUrl，与真实 adapter 契约对齐，
 * sectionPagination 首屏总数在 mock 模式下同样可提取）；{@code fetchPage} 覆写为全量条目切片 + total=条数 +
 * {@code paginationSupported:true}（PRD 场景 5「mock 下不报错」口径，不模拟真实深翻）。
 */
public class MockAnnounceSourceAdapter extends AbstractSourceAdapter {

    private static final List<FieldMapping> MAPPING =
            List.of(
                    new FieldMapping("items", "items", Transform.NONE),
                    new FieldMapping("total", "total", Transform.NONE),
                    new FieldMapping("paginationSupported", "paginationSupported", Transform.NONE),
                    new FieldMapping("moreUrl", "moreUrl", Transform.NONE));

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
    protected Optional<RawFetch> doFetch(Subject subject) {
        return Optional.of(pageFetch(allItems(), allItems().size()));
    }

    /** mock 分页：全量条目按 page/size 切片 + total=全量条数 + paginationSupported:true（不模拟真实深翻）。 */
    @Override
    public SourceResult fetchPage(Subject subject, int page, int size) {
        return runGuarded(
                subject,
                () -> Optional.of(pageFetch(slicePage(allItems(), page, size), allItems().size())));
    }

    private static List<Map<String, Object>> allItems() {
        return List.of(
                Map.<String, Object>of(
                        "title", "2026年半年度报告",
                        "url", "https://example.com/ann/1",
                        "publishedAt", "2026-08-30"),
                Map.<String, Object>of(
                        "title", "关于召开临时股东大会的通知",
                        "url", "https://example.com/ann/2",
                        "publishedAt", "2026-09-15"));
    }

    private static List<Map<String, Object>> slicePage(
            List<Map<String, Object>> items, int page, int size) {
        int from = Math.min((page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());
        return items.subList(from, to);
    }

    private static RawFetch pageFetch(List<Map<String, Object>> items, long total) {
        Map<String, Object> data =
                Map.of(
                        "items", List.copyOf(items),
                        "total", total,
                        "paginationSupported", true,
                        "moreUrl", "https://example.com/announcements");
        return new RawFetch(data, "公告源(mock)", Instant.now());
    }
}
