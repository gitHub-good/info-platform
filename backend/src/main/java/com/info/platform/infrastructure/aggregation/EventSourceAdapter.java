package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事件源真实 adapter（T08，ADR-0013）：读本地 {@code anomaly_event} 表取某标的近期异动/事件记录。
 *
 * <p><b>实现偏差</b>：技术方案/Spike-1 §6.7 原方案「复用用户环境既有 event-monitor 技能取相关重大事件」，经核实该技能在当前环境不存在 （全局搜索确认）。而
 * T13 异动检测引擎已落地并将异动事件写入本地 {@code anomaly_event} 表， 故本 adapter 改读本地表——仍不直连外部源， 与原方案「本地取数」的契约要点一致（详见
 * ADR-0013）。
 *
 * <p><b>契约对齐</b>（Spike-1 §6.7）：本地调用 {@code noRetry(500ms)}；{@code sourceLabel = "事件监控"}。
 *
 * <p><b>取数与映射</b>：经 {@link AnomalyRepository} 端口（push 域）按标的查异动历史（trigger_time 降序）， 在 {@link
 * #doFetch} 内过滤「近期」时间窗（{@link #RECENT_WINDOW} 7 天，与 PRD 场景 1 新闻窗一致）并截取 {@link #MAX_ITEMS} 条，
 * 逐条映射为列表项 （anomalyType/changePct/currentPrice/triggerTime/detail，可空字段缺省不产出）， 以 {@code data.items}
 * 列表承载（同公告/新闻/政策 列表型分区契约，见 {@link NewsSourceAdapter}）。 窗口内无记录 → {@link Optional#empty()} →
 * MISSING（近期无事件，不阻断）。
 *
 * <p><b>列表项映射不经 FieldMapper JSON</b>：数据源是本地领域实体（{@link AnomalyRecord}）而非外部源原始字段， 字段名已规范（无外部命名脏数据），在
 * {@link #toItem} 内直接构建（KISS），无需 {@code field-mapping/*.json} 间接层； {@link #mappingConfig} 仅做 {@code
 * items} 透传（模板层 {@code fieldMapper.map} 作用于整张 data map）。
 *
 * <p><b>降级</b>：本地读表异常（如 DB 不可用/数据损坏解析失败）走模板默认 {@code onDegraded} → MISSING（事件分区属「有则展示、无则 missing
 * 不阻断」语义，同新闻/政策源），不阻断详情页其他分区。
 *
 * <p>与 {@link MockEventSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 */
public class EventSourceAdapter extends AbstractSourceAdapter {

    /** 近期事件时间窗：7 天（与 PRD 场景 1「最近 7 天相关新闻」窗口口径一致）。 */
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    /** 单标的最多返回条数（详情页分区展示上限，防异常热点标的长列表刷屏）。 */
    private static final int MAX_ITEMS = 10;

    /** 本地调用超时（Spike-1 §6.7 契约要点：noRetry 500ms）。 */

    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...]}}）的 items 列表原样透传。 逐条字段映射在 {@link #doFetch} 内用
     * {@link #toItem} 完成。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(new FieldMapping("items", "items", Transform.NONE));

    private final AnomalyRepository anomalyRepository;

    public EventSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            AnomalyRepository anomalyRepository) {
        super(cache, fieldMapper, runner, breaker);
        this.anomalyRepository = anomalyRepository;
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.EVENT;
    }

    @Override
    protected String sourceLabel() {
        return "事件监控";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) {
        List<AnomalyRecord> records = anomalyRepository.findBySubjectId(subject.getId());
        Instant now = Instant.now();
        List<Map<String, Object>> items =
                records.stream()
                        .filter(record -> withinRecentWindow(record, now))
                        .limit(MAX_ITEMS)
                        .map(EventSourceAdapter::toItem)
                        .toList();
        if (items.isEmpty()) {
            // 近期窗口内无事件记录 → MISSING（成功调用，非异常，不阻断）
            return Optional.empty();
        }
        return Optional.of(new RawFetch(Map.of("items", items), sourceLabel(), Instant.now()));
    }

    /**
     * 近期窗口判定（含边界）：{@code triggerTime >= now - 7d} 算近期。 trigger_time 由 DB 约束非空（V5 DDL NOT NULL），无
     * null 分支。
     */
    static boolean withinRecentWindow(AnomalyRecord record, Instant now) {
        return !record.getTriggerTime().isBefore(now.minus(RECENT_WINDOW));
    }

    /**
     * 单条异动记录 → 列表项（字段名与其他列表分区 camelCase 口径一致）： anomalyType 枚举名（前端映射展示标签）、changePct / currentPrice
     * 触发时数值（可空，缺省不产出）、triggerTime ISO-8601 整秒串、detail 人读详情（可空）。
     */
    private static Map<String, Object> toItem(AnomalyRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("anomalyType", record.getAnomalyType().name());
        record.getChangePct().ifPresent(value -> item.put("changePct", value));
        record.getCurrentPrice().ifPresent(value -> item.put("currentPrice", value));
        item.put("triggerTime", record.getTriggerTime().toString());
        record.getDetail().ifPresent(value -> item.put("detail", value));
        return Collections.unmodifiableMap(item);
    }
}
