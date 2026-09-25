package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

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
 *   <li>{@link #resilienceSpec} —— 本源超时/重试配置（T36 起默认读运行时配置，子类可覆写）
 *   <li>{@link #sourceCode} / {@link #sourceLabel} —— 自我标识与展示标注
 * </ol>
 *
 * <p>降级（超时/异常/熔断）默认返回 {@link SourceResult#missing}（不阻断聚合）； 子类可覆写 {@link #onDegraded} 改为 FAILED
 * 或空列表语义。
 *
 * <h2>T16 · 数据源缺失事件记录（旁路）</h2>
 *
 * <p>模板降级路径四类触发点经 {@link #recordEvent} 旁路记 {@code data_source_event}（{@link DataSourceEventType}）：
 * doFetch 返回 empty → {@link DataSourceEventType#MISSING}； {@code ResilienceRunner} 超时（{@link
 * ResilienceException.FailureKind#TIMEOUT}）→ {@link DataSourceEventType#TIMEOUT}； doFetch
 * 抛异常/中断（{@link ResilienceException.FailureKind#ERROR}/{@code INTERRUPTED}）→ {@link
 * DataSourceEventType#ERROR}； 熔断开启 → {@link DataSourceEventType#LIMITED}。记录为<b>旁路</b>：不影响 {@code
 * onDegraded} 返回值（仍 MISSING/FAILED）、不影响主流程，记录自身异常不外抛（try-catch 记 ERROR 日志）。 {@link
 * DataSourceEventRecorder} 为可选依赖（字段注入 {@code @Autowired(required=false)}），缺失时旁路静默跳过——现有 T03~T07 各
 * adapter {@code 零改动}自动获得记录能力（Spring 装配时注入， 纯构造单测场景为 null 即无操作）。
 */
public abstract class AbstractSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractSourceAdapter.class);

    private final SourceCache cache;
    private final FieldMapper fieldMapper;
    private final ResilienceRunner resilienceRunner;
    private final CircuitBreaker circuitBreaker;

    /**
     * 数据源事件记录器（可选依赖）。Spring 装配的真实/mock adapter 由容器注入； 纯构造单测场景（{@code new FakeSourceAdapter(...)}）保持
     * null， {@link #recordEvent} 静默跳过——既不破坏现有测试，也让 T16 记录能力对生产环境透明启用。
     */
    @Autowired(required = false)
    DataSourceEventRecorder dataSourceEventRecorder;

    /**
     * 配置中心（T36 热化）：弹性超时/重试改用时读取 {@code datasource.{CODE}}（LIVE 级）。 纯构造单测场景保持 null——{@link
     * #resilienceSpec} 回落 {@link DataSourceDefaults} 代码缺省（与改造前各类 TIMEOUT 常量同值），既有测试零改动。
     */
    @Autowired(required = false)
    ConfigCenter configCenter;

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

    /**
     * 模板方法：缓存命中直返，否则走 {@link #fetchFresh} 全链路并写缓存。 final 锁定编排，子类只扩展 doFetch/映射/弹性。
     *
     * <p>P1-5b 失败负缓存：{@link SourceCache#put} 按结果状态分档 TTL——FAILED/MISSING 以短 TTL 负缓存，命中负缓存的请求
     * <b>快速返回降级态</b>（不再吃满超时预算），记 DEBUG（不刷 {@code data_source_event}，避免写放大； 事件留痕由首次真实降级路径经 60s
     * 节流记录）。final 锁定编排，子类只扩展 doFetch/映射/弹性。
     */
    @Override
    public final SourceResult fetch(Subject subject) {
        SourceCode code = sourceCode();
        Long subjectId = subject.getId();

        SourceResult cached = cache.getIfPresent(code, subjectId);
        if (cached != null) {
            if (cached.getStatus() != SourceStatus.OK) {
                log.debug(
                        "命中数据源负缓存（快速降级） sourceCode={} subjectId={} status={}",
                        code,
                        subjectId,
                        cached.getStatus());
            }
            return cached;
        }

        SourceResult result = fetchFresh(subject);
        cache.put(code, subjectId, result);
        return result;
    }

    /**
     * 绕过缓存的完整取数链路（T36 连通性测试口径）：熔断闸门 → 超时/重试 doFetch → 字段映射 → 返回（不读写缓存）。
     *
     * <p>事件旁路（含 OK 心跳）照常触发——测试抓取的结果即为该源「最近一次抓取结果」。package-private：仅供同包 {@code
     * DataSourceConfigFacadeImpl} 连通性测试调用，不进对外契约。
     */
    SourceResult fetchFresh(Subject subject) {
        return runGuarded(subject, () -> doFetch(subject));
    }

    /**
     * 取数守护骨架（M12 ADR-0037 决策 2 从 fetchFresh 提取）：熔断闸门 → 超时/重试取数动作 → 事件旁路 → 字段映射 → 返回，
     * <b>不读写缓存</b>。
     *
     * <p>{@link #fetchFresh}（首屏聚合，外层另包 SourceCache）与各子类 {@code fetchPage}（分区翻页，绕缓存直调源）共用本骨架——
     * 三态语义（ok/missing/failed/timeout）与降级不阻断原则由<b>同一段代码产出</b>，口径一致性由构造保证（ADR-0037 理由第 3 条）。
     *
     * @param subject 标的（降级/事件留痕的上下文）
     * @param fetch 取数动作（返回 empty 表示源无数据 → MISSING；抛异常 → 经弹性降级）
     */
    protected SourceResult runGuarded(Subject subject, Callable<Optional<RawFetch>> fetch) {
        SourceCode code = sourceCode();
        Long subjectId = subject.getId();

        if (!circuitBreaker.allowRequest(code)) {
            log.info("熔断开启，降级 sourceCode={} subjectId={}", code, subjectId);
            recordEvent(code, DataSourceEventType.LIMITED, subjectId, "circuit-open");
            return onDegraded(subject, "circuit-open");
        }

        Optional<RawFetch> raw;
        try {
            raw = resilienceRunner.run(fetch::call, resilienceSpec(), code);
            circuitBreaker.recordSuccess(code);
        } catch (ResilienceException e) {
            circuitBreaker.recordFailure(code);
            DataSourceEventType eventType =
                    e.getKind() == ResilienceException.FailureKind.TIMEOUT
                            ? DataSourceEventType.TIMEOUT
                            : DataSourceEventType.ERROR;
            recordEvent(code, eventType, subjectId, e.getReason());
            return onDegraded(subject, e.getReason());
        }

        if (raw.isEmpty()) {
            // 源当日无数据 → MISSING（成功调用，非异常，不阻断）。§5 可观测：数据源缺失记 WARN。
            log.warn("数据源当日无数据 sourceCode={} subjectId={}", code, subjectId);
            recordEvent(code, DataSourceEventType.MISSING, subjectId, "no-data");
            return SourceResult.missing(code, subjectId, sourceLabel());
        }

        RawFetch fetched = raw.get();
        Map<String, Object> mapped = fieldMapper.map(fetched.data(), mappingConfig());
        SourceResult result =
                SourceResult.ok(code, subjectId, mapped, fetched.source(), fetched.updatedAt());
        recordOkIfDue(code, subjectId);
        return result;
    }

    /** 成功心跳旁路（T36，方案 §4.3 OK(5)）：60s/源内存节流，失败静默（同 {@link #recordEvent} 零侵入约定）。 */
    private void recordOkIfDue(SourceCode code, Long subjectId) {
        DataSourceEventRecorder recorder = this.dataSourceEventRecorder;
        if (recorder == null) {
            return;
        }
        try {
            recorder.recordOkIfDue(code, subjectId);
        } catch (Exception e) {
            log.error("记录数据源成功心跳失败 sourceCode={} subjectId={}", code, subjectId, e);
        }
    }

    /**
     * 子类实现：调外部源取原始字段。
     *
     * <p>返回 {@code Optional.empty()} 表示源当日无数据（→ MISSING）；抛异常表示取数失败（→ 经弹性降级）。 不必关心缓存/超时/映射， 由模板统一编排。
     */
    protected abstract Optional<RawFetch> doFetch(Subject subject) throws Exception;

    /** 子类提供本源字段映射配置（源字段→目标字段+转换）。 */
    protected abstract List<FieldMapping> mappingConfig();

    /**
     * 本源弹性配置（超时/重试）。
     *
     * <p>T36 热化（ADR-0017 / 方案 §4.3）：默认<b>用时读取</b>运行时配置 {@code datasource.{CODE}}（LIVE
     * 级，页面保存下一次取数即生效）； 配置中心缺失（纯构造单测）回落 {@link DataSourceDefaults} 代码缺省。 重试 &gt; 0 时启用指数退避（基数 {@link
     * DataSourceDefaults#RETRY_BACKOFF_BASE_MILLIS}）。 子类仍可覆写注入固定值（测试替身先例）。
     */
    protected ResilienceSpec resilienceSpec() {
        RuntimeDataSource config =
                configCenter == null
                        ? RuntimeDataSource.fallback(sourceCode(), RuntimeDataSource.Mode.MOCK)
                        : configCenter.dataSource(sourceCode());
        Duration timeout = Duration.ofMillis(Math.max(1, config.timeoutMillis()));
        return config.retries() > 0
                ? ResilienceSpec.of(
                        timeout,
                        config.retries(),
                        Duration.ofMillis(DataSourceDefaults.RETRY_BACKOFF_BASE_MILLIS))
                : ResilienceSpec.noRetry(timeout);
    }

    /** 来源标注（展示用），子类提供，如 "行情源"。 */
    protected abstract String sourceLabel();

    /**
     * 旁路记录一条失败类 {@code data_source_event}（T16；P1-5b 起经 {@code recordFailureIfDue} 60s/源节流， 对齐 OK
     * 心跳口径——防故障源高频降级时每请求一行的事件写放大，体检实测 24h 异常 1950 次）。
     *
     * <p>零侵入契约：recorder 为空（纯构造单测场景）静默跳过； 记录自身异常不外抛（try-catch 记 ERROR 日志）——不影响 {@link #onDegraded}
     * 返回值与主流程。recorder 内部亦已 catch 兜底，此处为 defense-in-depth 双保险。
     */
    private void recordEvent(
            SourceCode code, DataSourceEventType type, Long subjectId, String detail) {
        DataSourceEventRecorder recorder = this.dataSourceEventRecorder;
        if (recorder == null) {
            return;
        }
        try {
            recorder.recordFailureIfDue(code, type, subjectId, detail);
        } catch (Exception e) {
            log.error(
                    "记录数据源事件失败 sourceCode={} type={} subjectId={} detail={}",
                    code,
                    type,
                    subjectId,
                    detail,
                    e);
        }
    }

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
