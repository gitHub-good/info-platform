package com.info.platform.application.ai;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.ai.BriefFact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 幻觉校验器（应用层，T21，对齐 Spike-2 §6 + 技术方案 §4.3 流程 2）。
 *
 * <p>对 {@link com.info.platform.domain.ai.BriefContent#facts()} 逐条校验： 按 {@code metric} 映射到对应 {@link
 * SourceAdapter}（如 {@code roe}→FINANCE），回查该 adapter 取真值，计算相对误差 {@code |brief-true|/|true|}，超容忍阈值（默认
 * 5%）标 {@link FactCheckStatus#NEED_VERIFY}。校验<b>不阻断</b>生成—— 仅决定整体 status=1（全通过）/
 * status=3（有待核实），content 仍展示（降级展示，Spike-2 §6）。
 *
 * <p>校验范围：仅 {@code value 非空} 且 {@code metric 命中已知字段键} 的财务事实。定性事实（无数值）与未映射 metric （如 {@code
 * net_profit_yoy}，adapter 不产 YoY）标 {@link FactCheckStatus#SKIPPED}，不触发 status=3。
 *
 * <p>源降级（adapter 返回 MISSING/FAILED 或字段缺失）→ 标 {@code NEED_VERIFY}（源暂不可用），区分「数值不符」与「源不可用」两类原因。
 *
 * <p>依赖 {@link SourceAdapter} 端口（领域层）——应用层只经端口取真值，不引基础设施实现细节（与 {@code AggregationService} 同构）。真实
 * adapter（T03~T08）替换 mock 后本类无需改动。
 */
@Service
public class HallucinationChecker {

    private static final Logger log = LoggerFactory.getLogger(HallucinationChecker.class);

    /**
     * metric → 真值来源映射（对齐 T04 FinanceAdapter 输出字段键 + mock adapter 键）。
     *
     * <p>未来 valuation/quote 指标（如 {@code pe}/{@code pb}）可按需追加（VALUATION 源）。
     */
    private static final Map<String, MetricSource> METRIC_SOURCES = metricSources();

    private final Map<SourceCode, SourceAdapter> adapters;
    private final double toleranceRatio;

    public HallucinationChecker(
            List<SourceAdapter> adapters,
            @Value("${ai.hallucination.tolerance-ratio:0.05}") double toleranceRatio) {
        Map<SourceCode, SourceAdapter> map = new LinkedHashMap<>();
        for (SourceAdapter a : adapters == null ? List.<SourceAdapter>of() : adapters) {
            map.put(a.sourceCode(), a);
        }
        this.adapters = Map.copyOf(map);
        this.toleranceRatio = toleranceRatio;
    }

    /**
     * 校验全部事实。
     *
     * @param facts 简报的事实数组（可空）
     * @param subject 标的（取真值时传入 adapter）
     * @return 汇总结果（任一 NEED_VERIFY → needVerify=true）
     */
    public HallucinationResult check(List<BriefFact> facts, Subject subject) {
        List<FactCheck> checks = new ArrayList<>();
        boolean needVerify = false;
        if (facts == null || facts.isEmpty()) {
            return new HallucinationResult(false, List.of());
        }
        for (BriefFact fact : facts) {
            FactCheck check = checkOne(fact, subject);
            checks.add(check);
            if (check.needVerify()) {
                needVerify = true;
            }
        }
        return new HallucinationResult(needVerify, checks);
    }

    private FactCheck checkOne(BriefFact fact, Subject subject) {
        if (fact.value() == null) {
            // 定性事实无数值，不校验
            return new FactCheck(fact, FactCheckStatus.SKIPPED, "无数值，跳过");
        }
        MetricSource src = METRIC_SOURCES.get(fact.metric());
        if (src == null) {
            // metric 未命中已知字段键（如 net_profit_yoy），不在校验范围
            return new FactCheck(
                    fact, FactCheckStatus.SKIPPED, "metric=" + fact.metric() + " 未映射真值源，跳过");
        }
        SourceAdapter adapter = adapters.get(src.sourceCode());
        if (adapter == null) {
            return new FactCheck(
                    fact, FactCheckStatus.NEED_VERIFY, src.sourceCode() + " adapter 未装配，源暂不可用");
        }
        SourceResult result;
        try {
            result = adapter.fetch(subject);
        } catch (Exception e) {
            log.warn(
                    "幻觉校验取数异常 metric={} source={}: {}",
                    fact.metric(),
                    src.sourceCode(),
                    e.toString());
            return new FactCheck(
                    fact, FactCheckStatus.NEED_VERIFY, src.sourceCode() + " 取数异常，源暂不可用");
        }
        if (result == null || result.getStatus() != SourceStatus.OK) {
            return new FactCheck(
                    fact,
                    FactCheckStatus.NEED_VERIFY,
                    src.sourceCode()
                            + " 源降级（"
                            + (result == null ? "null" : result.getStatus())
                            + "），暂不可用");
        }
        Object raw = result.getData().get(src.dataKey());
        Double trueValue = toDouble(raw);
        if (trueValue == null) {
            return new FactCheck(
                    fact,
                    FactCheckStatus.NEED_VERIFY,
                    src.sourceCode() + "." + src.dataKey() + " 字段缺失，源暂不可用");
        }
        double brief = fact.value();
        double error = relativeError(brief, trueValue);
        if (error > toleranceRatio) {
            String reason =
                    String.format(
                            "数值不符：模型 %.4f vs 真值 %.4f，相对误差 %.1f%%（阈值 %.0f%%）",
                            brief, trueValue, error * 100, toleranceRatio * 100);
            return new FactCheck(fact, FactCheckStatus.NEED_VERIFY, reason);
        }
        return new FactCheck(
                fact,
                FactCheckStatus.VERIFIED,
                String.format("已核实：模型 %.4f vs 真值 %.4f，误差 %.1f%%", brief, trueValue, error * 100));
    }

    /** 相对误差 {@code |brief-true|/|true|}；真值为 0 时按方向判（不等则 1.0 超阈，等则 0）。 */
    private static double relativeError(double brief, double trueValue) {
        if (trueValue == 0.0) {
            return brief == 0.0 ? 0.0 : 1.0;
        }
        return Math.abs(brief - trueValue) / Math.abs(trueValue);
    }

    /** 真值归一为 double（adapter 字段映射后多为 BigDecimal/Number，容错 String）。 */
    private static Double toDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (raw instanceof String s) {
            String trimmed = s.trim().replaceAll("[^0-9.\\-]", "");
            if (trimmed.isEmpty() || trimmed.equals("-")) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** metric→(数据源, 字段键) 映射表构建。 */
    private static Map<String, MetricSource> metricSources() {
        Map<String, MetricSource> m = new LinkedHashMap<>();
        m.put("roe", new MetricSource(SourceCode.FINANCE, "roe"));
        m.put("gross_margin", new MetricSource(SourceCode.FINANCE, "grossProfitMargin"));
        m.put("net_profit_margin", new MetricSource(SourceCode.FINANCE, "netProfitMargin"));
        m.put("revenue", new MetricSource(SourceCode.FINANCE, "revenue"));
        m.put("net_profit", new MetricSource(SourceCode.FINANCE, "netProfit"));
        m.put("eps", new MetricSource(SourceCode.FINANCE, "eps"));
        return Map.copyOf(m);
    }

    /** metric→真值来源（数据源 + 该源 data Map 中的字段键）。 */
    private record MetricSource(SourceCode sourceCode, String dataKey) {
        MetricSource {
            Objects.requireNonNull(sourceCode);
            Objects.requireNonNull(dataKey);
        }
    }
}
