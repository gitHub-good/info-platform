package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源适配器模板基类（ADR-0003 + ADR-0005 + ADR-0010）。
 *
 * <p>模板方法 {@link #fetch} 编排「缓存命中?直返 → 熔断闸门 → 超时/重试 doFetch → 字段映射 → 写缓存 → 返回」， 失败/超时走
 * 降级钩子。子类（T03~T08 各源）只实现四件事：
 *
 * <ol>
 *   <li>{@link #doFetch} —— 调外部源取原始字段；返回 {@code Optional.empty()} 表示源当日无数据（→ MISSING），抛异常表示取数失败（→
 *       降级）
 *   <li>{@link #mappingConfig} —— 本源字段映射配置（源字段→目标字段+转换）
 *   <li>{@link #resilienceSpec} —— 本源超时/重试配置（差异化，如行情 1.5s 重试 0）
 *   <li>{@link #sourceCode} / {@link #sourceLabel} —— 自我标识与展示标注
 * </ol>
 *
 * <p>降级（超时/异常/熔断）默认返回 {@link SourceResult#missing}（不阻断聚合）； 子类可覆写 {@link #onDegraded} 改为 FAILED
 * 或空列表语义。具体记 {@code data_source_event} 在 T16 落地，本批留抽象钩子。
 */
public abstract class AbstractSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractSourceAdapter.class);

    private final SourceCache cache;
    private final FieldMapper fieldMapper;
    private final ResilienceRunner resilienceRunner;
    private final CircuitBreaker circuitBreaker;

    protected AbstractSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner resilienceRunner,
            CircuitBreaker circuitBreaker) {
        this.cache = cache;
        this.fieldMapper = fieldMapper;
        this.resilienceRunner = resilienceRunner;
        this.circuitBreaker = circuitBreaker;
    }

    /** 模板方法：缓存命中直返，否则熔断闸门 → 超时/重试取数 → 字段映射 → 写缓存 → 返回。 final 锁定编排，子类只扩展 doFetch/映射/弹性。 */
    @Override
    public final SourceResult fetch(Subject subject) {
        SourceCode code = sourceCode();
        Long subjectId = subject.getId();

        SourceResult cached = cache.getIfPresent(code, subjectId);
        if (cached != null) {
            return cached;
        }

        if (!circuitBreaker.allowRequest(code)) {
            log.info("熔断开启，降级 sourceCode={} subjectId={}", code, subjectId);
            return onDegraded(subject, "circuit-open");
        }

        Optional<RawFetch> raw;
        try {
            raw = resilienceRunner.run(() -> doFetch(subject), resilienceSpec(), code);
            circuitBreaker.recordSuccess(code);
        } catch (ResilienceException e) {
            circuitBreaker.recordFailure(code);
            return onDegraded(subject, e.getReason());
        }

        if (raw.isEmpty()) {
            // 源当日无数据 → MISSING（成功调用，非异常，不阻断）
            return SourceResult.missing(code, subjectId, sourceLabel());
        }

        RawFetch fetched = raw.get();
        Map<String, Object> mapped = fieldMapper.map(fetched.data(), mappingConfig());
        SourceResult result =
                SourceResult.ok(code, subjectId, mapped, fetched.source(), fetched.updatedAt());
        cache.put(code, subjectId, result);
        return result;
    }

    /**
     * 子类实现：调外部源取原始字段。
     *
     * <p>返回 {@code Optional.empty()} 表示源当日无数据（→ MISSING）；抛异常表示取数失败（→ 经弹性降级）。 不必关心缓存/超时/映射， 由模板统一编排。
     */
    protected abstract Optional<RawFetch> doFetch(Subject subject) throws Exception;

    /** 子类提供本源字段映射配置（源字段→目标字段+转换）。 */
    protected abstract List<FieldMapping> mappingConfig();

    /** 子类提供本源弹性配置（超时/重试，差异化）。 */
    protected abstract ResilienceSpec resilienceSpec();

    /** 来源标注（展示用），子类提供，如 "行情源"。 */
    protected abstract String sourceLabel();

    /**
     * 降级钩子：超时/异常/熔断时调用。默认返回 MISSING（不阻断聚合）。
     *
     * <p>子类可覆写为 FAILED，或按业务语义保持 MISSING（如公告/新闻源当日无数据与异常均兜底为 MISSING）。
     */
    protected SourceResult onDegraded(Subject subject, String reason) {
        log.warn(
                "数据源降级 sourceCode={} subjectId={} reason={}",
                sourceCode(),
                subject.getId(),
                reason);
        return SourceResult.missing(sourceCode(), subject.getId(), sourceLabel());
    }
}
