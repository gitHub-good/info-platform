package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 分区子端点取数服务（M12 T90~T92，方案 §4.3 / ADR-0037 决策 2）。
 *
 * <p>「看更多」翻页取数编排：resolveSubject（404 口径与聚合同源）→ 委派 {@link SourceAdapter#fetchPage}（绕过
 * SourceCache 直调源，三态语义与聚合同一段 runGuarded 骨架产出）→ 组装分区页视图。超时护栏复用
 * {@code aggregation.global} 总超时（{@link AggregationRuntimeSettings}，与 {@link AggregationService} 同口径）—— 超时/异常不抛
 * HTTP 错，按分区降级态返回（sourceStatus=timeout/failed + 空列表，前端保留当前内容 + 重试）。
 *
 * <p>页大小缺省按端点语义解析（公告 = 运行时 {@code announcePageSize}；显式值边界校验在接口层 PageQuery）。
 */
@Service
public class SubjectSectionPageService {

    private static final Logger log = LoggerFactory.getLogger(SubjectSectionPageService.class);

    /** 公告子端点页码上限（产品裁决：详情页分区是概览+近期重点，深回溯走源站；外呼放大 5 页封顶）。 */
    static final int ANNOUNCE_MAX_PAGE = 5;

    /** 事件子端点页大小缺省（原 EventSourceAdapter.MAX_ITEMS=10 语义升级为页大小，方案 §4.1.2）。 */
    static final int EVENT_DEFAULT_SIZE = 10;

    private final SubjectRepository subjectRepository;
    private final Map<SourceCode, SourceAdapter> adapters;
    private final Executor executor;
    private final AggregationRuntimeSettings runtimeSettings;
    private final SectionPageSettings sectionPageSettings;

    public SubjectSectionPageService(
            SubjectRepository subjectRepository,
            List<SourceAdapter> adapters,
            @Qualifier("aggregationExecutor") Executor executor,
            AggregationRuntimeSettings runtimeSettings,
            SectionPageSettings sectionPageSettings) {
        this.subjectRepository = subjectRepository;
        this.adapters =
                adapters.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        SourceAdapter::sourceCode, Function.identity()));
        this.executor = executor;
        this.runtimeSettings = runtimeSettings;
        this.sectionPageSettings = sectionPageSettings;
    }

    /**
     * 公告分区分页取数（方案 §4.1.1）。
     *
     * @param subjectId 标的内部主键
     * @param page 页码（接口层已校验 1~5）
     * @param size 页大小（接口层已校验 1~50；null = 运行时 announcePageSize 缺省）
     * @return 公告分区页视图（失败/超时为降级态，不抛 HTTP 错）
     * @throws BusinessException 30001 标的不存在
     */
    public AnnouncementPageView announcements(
            Long subjectId, int page, Integer size) {
        Subject subject = resolveSubject(subjectId);
        int resolvedSize = size == null ? sectionPageSettings.announcePageSize() : size;
        long startedAt = System.currentTimeMillis();
        SourceResult result = fetchSectionPage(SourceCode.ANNOUNCE, subject, page, resolvedSize);
        log.debug(
                "公告分区子端点取数 subjectId={} page={} size={} status={} costMs={}",
                subjectId,
                page,
                resolvedSize,
                result == null ? "timeout" : result.getStatus(),
                System.currentTimeMillis() - startedAt);
        return new AnnouncementPageView(
                itemsOf(result),
                page,
                resolvedSize,
                longOf(result, "total"),
                Boolean.TRUE.equals(valueOf(result, "paginationSupported")),
                stringOf(valueOf(result, "moreUrl")),
                statusOf(result),
                sourceOf(result));
    }

    /**
     * 事件分区分页取数（M12 T91，方案 §4.1.2）：本地 7 天窗 count + LIMIT/OFFSET 切片，零外呼。
     *
     * @param subjectId 标的内部主键
     * @param page 页码（≥1，无 5 页上限——本地数据翻完即止）
     * @param size 页大小（接口层已校验 1~50；null = 缺省 10）
     * @return 事件分区页视图（空窗/失败/超时为降级态，不抛 HTTP 错）
     * @throws BusinessException 30001 标的不存在
     */
    public EventPageView events(Long subjectId, int page, Integer size) {
        Subject subject = resolveSubject(subjectId);
        int resolvedSize = size == null ? EVENT_DEFAULT_SIZE : size;
        long startedAt = System.currentTimeMillis();
        SourceResult result = fetchSectionPage(SourceCode.EVENT, subject, page, resolvedSize);
        log.debug(
                "事件分区子端点取数 subjectId={} page={} size={} status={} costMs={}",
                subjectId,
                page,
                resolvedSize,
                result == null ? "timeout" : result.getStatus(),
                System.currentTimeMillis() - startedAt);
        Long total = longOf(result, "total");
        return new EventPageView(
                itemsOf(result),
                page,
                resolvedSize,
                total == null ? 0L : total,
                statusOf(result),
                sourceOf(result));
    }

    /** 标的解析（404 口径与聚合端点同源）。 */
    private Subject resolveSubject(Long subjectId) {
        return subjectRepository
                .findById(subjectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));
    }

    /**
     * 超时护栏下执行分区分页取数：总超时读 {@code aggregation.global}（与聚合详情同口径）； 超时返回 null（调用方按
     * sourceStatus=timeout 组装降级视图），异常按 failed（fetchPage 契约上不抛，防御性兜底）。
     */
    private SourceResult fetchSectionPage(
            SourceCode code, Subject subject, int page, int size) {
        SourceAdapter adapter = adapters.get(code);
        if (adapter == null) {
            log.warn("未装配 {} SourceAdapter，分区分页整体降级 sourceCode={}", code, code);
            return null;
        }
        CompletableFuture<SourceResult> future =
                CompletableFuture.supplyAsync(() -> adapter.fetchPage(subject, page, size), executor);
        try {
            return future.get(runtimeSettings.detailTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn(
                    "分区子端点取数总超时 sourceCode={} subjectId={} page={} timeoutMs={}",
                    code,
                    subject.getId(),
                    page,
                    runtimeSettings.detailTimeoutMillis());
            return null;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("分区子端点取数异常 sourceCode={} subjectId={}", code, subject.getId(), ex);
            return null;
        }
    }

    /** OK → data.items；降级/超时/键缺失 → 空列表。 */
    private static List<Map<String, Object>> itemsOf(SourceResult result) {
        if (result == null || result.getStatus() != SourceStatus.OK) {
            return List.of();
        }
        return result.getData().get("items") instanceof List<?> list ? castItems(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castItems(List<?> list) {
        return (List<Map<String, Object>>) list;
    }

    private static Object valueOf(SourceResult result, String key) {
        return result != null && result.getStatus() == SourceStatus.OK
                ? result.getData().get(key)
                : null;
    }

    private static Long longOf(SourceResult result, String key) {
        return valueOf(result, key) instanceof Number number ? number.longValue() : null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    /** 状态标注：null（护栏超时/异常）→ timeout；其余与聚合 mapStatus 同码。 */
    private static String statusOf(SourceResult result) {
        if (result == null) {
            return "timeout";
        }
        return switch (result.getStatus()) {
            case OK -> "ok";
            case MISSING -> "missing";
            case FAILED -> "failed";
        };
    }

    private static String sourceOf(SourceResult result) {
        return result == null ? "" : result.getSource();
    }
}
