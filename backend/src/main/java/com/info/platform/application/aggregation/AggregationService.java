package com.info.platform.application.aggregation;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 标的详情聚合编排服务（应用层，对齐技术方案 §4.3 流程 1）。
 *
 * <p>核心编排：先取标的主数据（不存在抛 30001）→ 按 sections 选源 → {@link CompletableFuture} 并行调各 {@link SourceAdapter}
 * → {@code allOf().get(2s)} 总超时兜底 → 收集降级（任一源 MISSING/FAILED/超时 不抛异常、不阻断其他分区）→ 组装统一 {@link
 * SubjectDetail}。
 *
 * <p>各 adapter 自带弹性超时级联收敛（下游之和 < 上游 2s 预算）；应用层仅依赖 {@code SourceAdapter} 端口 （领域层）与 {@code
 * SubjectRepository} 端口，不引基础设施实现细节。真实 adapter（T03~T08）替换 mock 后本类无需改动。
 *
 * <p>T31 扩展位：按 {@link SourceAdapter#supportedSubjectTypes()} 过滤不适用本类型的源（不调外部源，分区 missing
 * 降级）——新增标的类型只注册 adapter 支持声明，本编排类零改动。
 */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final SubjectRepository subjectRepository;
    private final Map<SourceCode, SourceAdapter> adapters;
    private final Executor executor;
    private final long overallTimeoutMillis;

    public AggregationService(
            SubjectRepository subjectRepository,
            List<SourceAdapter> adapters,
            @Qualifier("aggregationExecutor") Executor executor,
            @Value("${aggregation.detail-timeout-millis:2000}") long overallTimeoutMillis) {
        this.subjectRepository = subjectRepository;
        this.adapters =
                adapters.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        SourceAdapter::sourceCode, Function.identity()));
        this.executor = executor;
        this.overallTimeoutMillis = overallTimeoutMillis;
    }

    /**
     * 聚合取数并组装详情。
     *
     * @param subjectId 标的内部主键
     * @param sections 要取的分区（空集 = 全部 7 类）
     * @return 聚合详情（sourceStatus 标注每分区状态）
     * @throws BusinessException 30001 标的不存在
     */
    public SubjectDetail getDetail(Long subjectId, Set<SourceCode> sections) {
        Subject subject =
                subjectRepository
                        .findById(subjectId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));

        Set<SourceCode> targets =
                (sections == null || sections.isEmpty())
                        ? EnumSet.allOf(SourceCode.class)
                        : EnumSet.copyOf(sections);

        Map<SourceCode, CompletableFuture<SourceResult>> futures = new LinkedHashMap<>();
        for (SourceCode code : targets) {
            SourceAdapter adapter = adapters.get(code);
            if (adapter == null) {
                continue;
            }
            if (!adapter.supportedSubjectTypes().contains(subject.getSubjectType())) {
                // T31 扩展位：本源不支持该标的类型 → 不调外部源，分区按 missing 降级（sourceStatus 契约不变）。
                log.debug(
                        "源不适用标的类型，跳过取数 sourceCode={} subjectId={} subjectType={}",
                        code,
                        subjectId,
                        subject.getSubjectType());
                futures.put(
                        code,
                        CompletableFuture.completedFuture(
                                SourceResult.missing(code, subject.getId(), code.name())));
                continue;
            }
            futures.put(
                    code, CompletableFuture.supplyAsync(() -> adapter.fetch(subject), executor));
        }

        CompletableFuture<Void> all =
                CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
        try {
            all.get(overallTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("聚合取数总超时 subjectId={} timeoutMs={}", subjectId, overallTimeoutMillis);
        } catch (ExecutionException ee) {
            log.warn("聚合取数异常 subjectId={}", subjectId, ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("聚合取数被中断 subjectId={}", subjectId);
        }

        return assemble(subject, futures);
    }

    private SubjectDetail assemble(
            Subject subject, Map<SourceCode, CompletableFuture<SourceResult>> futures) {
        Map<String, String> sourceStatus = new LinkedHashMap<>();
        Map<String, Object> quote = null;
        Map<String, Object> finance = null;
        Map<String, Object> valuation = null;
        List<Map<String, Object>> announcements = null;
        List<Map<String, Object>> news = null;
        List<Map<String, Object>> policies = null;
        List<Map<String, Object>> events = null;

        for (Map.Entry<SourceCode, CompletableFuture<SourceResult>> entry : futures.entrySet()) {
            SourceCode code = entry.getKey();
            CompletableFuture<SourceResult> future = entry.getValue();
            String status;
            if (future.isDone()) {
                try {
                    SourceResult result = future.get();
                    status = mapStatus(result.getStatus());
                    if (result.getStatus() == SourceStatus.OK) {
                        switch (code) {
                            case QUOTE -> quote = result.getData();
                            case FINANCE -> finance = result.getData();
                            case VALUATION -> valuation = result.getData();
                            case ANNOUNCE -> announcements = extractItems(result.getData());
                            case NEWS -> news = extractItems(result.getData());
                            case POLICY -> policies = extractItems(result.getData());
                            case EVENT -> events = extractItems(result.getData());
                        }
                    }
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    status = "failed";
                }
            } else {
                future.cancel(true);
                status = "timeout";
            }
            sourceStatus.put(code.name().toLowerCase(), status);
        }

        return new SubjectDetail(
                new SubjectDetail.SubjectInfo(
                        subject.getSubjectCode().value(),
                        subject.getName(),
                        subject.getMarket().name(),
                        subject.getSubjectType().code(),
                        subject.getIndustry()),
                quote,
                finance,
                valuation,
                announcements,
                news,
                policies,
                events,
                Map.copyOf(sourceStatus));
    }

    private static String mapStatus(SourceStatus status) {
        return switch (status) {
            case OK -> "ok";
            case MISSING -> "missing";
            case FAILED -> "failed";
        };
    }

    /** 列表型分区（公告/新闻/政策/事件）原始 data 中以 "items" 键承载列表，提取为 {@code List<Map>}。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractItems(Map<String, Object> data) {
        Object items = data.get("items");
        if (items instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }
}
