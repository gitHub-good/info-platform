package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.ImportanceScorer;
import com.info.platform.domain.analysis.NearDuplicateDetector.DupParams;
import com.info.platform.domain.analysis.NoiseRuleEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管道运行参数（应用层，M15 T120/T121，方案 §4.8 {@code pipeline.*} 配置键的消费点）。
 *
 * <p>每次 tick 用时读取（配置中心快照，页面保存即对下一批生效）；键缺失或字段损坏回落代码缺省并记 WARN（不阻断批窗口——旧值/缺省 继续生效，对齐既有降级惯例）。已消费 {@code
 * pipeline.global}（L1 批量参数）、{@code pipeline.l0}（预筛参数）、{@code pipeline.l2}（事件提取参数）、{@code pipeline.budget}（护栏预算参数，
 * T125）四键； {@code pipeline.heat} 随 T123 消费方落地。
 */
@Service
public class PipelineSettings {

    private static final Logger log = LoggerFactory.getLogger(PipelineSettings.class);

    /** L1 批大小缺省（ADR-0046 裁决 2：20 条/次实测背书）。 */
    static final int DEFAULT_L1_BATCH_SIZE = 20;

    /** L1 批大小可配区间（方案 §3.2：10~30，>30 禁配——截断与失败爆炸半径红线）。 */
    static final int L1_BATCH_MIN = 10;

    static final int L1_BATCH_MAX = 30;

    /** 低置信兜底阈值缺省（方案 §4.3：confidence &lt; 0.45 → 市场·其他 + low_confidence=1）。 */
    static final double DEFAULT_CONFIDENCE_FLOOR = 0.45;

    /** 当日重试上限缺省（单条终败后 l1_attempts 达 3 不再进批，次日 24h 窗口再试一轮）。 */
    static final int DEFAULT_MAX_RETRIES = 3;

    /** L1 待处理回看窗口缺省（小时）——次日补跑语义（ADR-0046 裁决 5）。 */
    static final int DEFAULT_BACKFILL_HOURS = 24;

    /** L0 摄取缓冲缺省（分钟）——与摄取事务竞态的错峰（方案 §4.2 X=2min）。 */
    static final int DEFAULT_L0_BUFFER_MINUTES = 2;

    /** L0 摄取缓冲（分钟）。 */
    public int l0BufferMinutes() {
        return intOf(globalDoc(), "l0BufferMinutes", DEFAULT_L0_BUFFER_MINUTES);
    }

    /** 单 tick L0 摄取上限（防御性；正常水位 ~5 条/10min）。 */
    static final int L0_INTAKE_CAP_PER_TICK = 200;

    /** 近重复比较池上限（防御性；正常水位 ≤700/24h）。 */
    static final int NEAR_DUP_POOL_CAP = 2000;

    /** 单 tick L1 批数上限（400 = 20 批；停机恢复补跑有界，余量下 tick 自然续跑）。 */
    static final int L1_TICK_CAP = 400;

    /** L2 重要性预筛阈值缺省（方案 §4.4：score ≥ 2.5 命中）。 */
    static final double DEFAULT_L2_THRESHOLD = 2.5;

    /** L2 日配额比例缺省（REQ 场景 4：≤20%）。 */
    static final double DEFAULT_L2_QUOTA_RATIO = 0.2;

    /** L1 采样参数缺省（方案 §4.3 伪码：temperature 0.1 / maxTokens 8192）。 */
    public static final double L1_TEMPERATURE = 0.1;

    public static final int L1_MAX_TOKENS = 8192;

    /** L2 批大小缺省（方案 §4.4：batch=10 条/次调用）。 */
    static final int DEFAULT_L2_BATCH_SIZE = 10;

    /** 单 tick L2 候选上限（防御性；正常水位 ~130 事件/日）。 */
    static final int L2_TICK_CAP = 400;

    /** L2 采样参数（同 L1 管道口径：temperature 0.1 / maxTokens 8192）。 */
    public static final double L2_TEMPERATURE = 0.1;

    public static final int L2_MAX_TOKENS = 8192;

    static final String KEY_PIPELINE_GLOBAL = "pipeline.global";

    static final String KEY_PIPELINE_L0 = "pipeline.l0";

    static final String KEY_PIPELINE_L2 = "pipeline.l2";

    static final String KEY_PIPELINE_BUDGET = "pipeline.budget";

    // —— 护栏预算参数（M15 T125，方案 §3.5/§4.8 pipeline.budget 键） ——

    /** 日预算缺省（¥2/日 = 2,000,000 微元——按实测单条 ¥0.0011 × 632 条/日 ≈ 35% 水位，3 倍放量余量）。 */
    static final long DEFAULT_DAILY_BUDGET_MICROS = 2_000_000L;

    /** 降级阈值比例缺省（60%）。 */
    static final double DEFAULT_DEGRADE_RATIO = 0.6;

    /** 熔断阈值比例缺省（90%）。 */
    static final double DEFAULT_FUSE_RATIO = 0.9;

    /** 单条成本校准初值（微元 = 附录 A 实测 L1 ¥0.00046 + L2 推算 + 日报摊薄）。 */
    static final long DEFAULT_CALIBRATED_PER_ITEM_MICROS = 1_100L;

    /** 成本口径版本串初值（校准写入时升版）。 */
    static final String DEFAULT_COST_BASIS = "cost-v1:initial";

    /** 日预算（微元；热改即时生效——每 tick 现读）。 */
    public long dailyBudgetMicros() {
        long budget = longOf(budgetDoc(), "dailyBudgetMicros", DEFAULT_DAILY_BUDGET_MICROS);
        return budget <= 0 ? DEFAULT_DAILY_BUDGET_MICROS : budget;
    }

    /** 降级阈值比例（0~1 越界回落 0.6 并 WARN）。 */
    public double degradeRatio() {
        double ratio = doubleOf(budgetDoc(), "degradeRatio", DEFAULT_DEGRADE_RATIO);
        if (ratio <= 0 || ratio >= 1) {
            log.warn("pipeline.budget.degradeRatio={} 越界（0~1），回落缺省 {}", ratio, DEFAULT_DEGRADE_RATIO);
            return DEFAULT_DEGRADE_RATIO;
        }
        return ratio;
    }

    /** 熔断阈值比例（0~1 越界回落 0.9 并 WARN）。 */
    public double fuseRatio() {
        double ratio = doubleOf(budgetDoc(), "fuseRatio", DEFAULT_FUSE_RATIO);
        if (ratio <= 0 || ratio <= degradeRatio() || ratio > 1) {
            log.warn("pipeline.budget.fuseRatio={} 越界（须 >degradeRatio 且 ≤1），回落缺省 {}", ratio, DEFAULT_FUSE_RATIO);
            return DEFAULT_FUSE_RATIO;
        }
        return ratio;
    }

    /** 单条成本校准值（微元）。 */
    public long calibratedPerItemMicros() {
        return longOf(budgetDoc(), "calibratedPerItemMicros", DEFAULT_CALIBRATED_PER_ITEM_MICROS);
    }

    /** 成本口径版本串。 */
    public String costBasis() {
        JsonNode node = budgetDoc() == null ? null : budgetDoc().get("costBasis");
        return node != null && node.isTextual() && !node.asText().isBlank()
                ? node.asText()
                : DEFAULT_COST_BASIS;
    }

    private JsonNode budgetDoc() {
        return doc(KEY_PIPELINE_BUDGET);
    }

    private final RuntimeConfigService configService;

    private final ObjectMapper objectMapper;

    public PipelineSettings(RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** L1 批大小（10~30 越界回落缺省 20 并 WARN——&gt;30 禁配红线的运行时兜底）。 */
    public int l1BatchSize() {
        int size = intOf(globalDoc(), "l1BatchSize", DEFAULT_L1_BATCH_SIZE);
        if (size < L1_BATCH_MIN || size > L1_BATCH_MAX) {
            log.warn(
                    "pipeline.global.l1BatchSize={} 越界（{}~{}），回落缺省 {}",
                    size,
                    L1_BATCH_MIN,
                    L1_BATCH_MAX,
                    DEFAULT_L1_BATCH_SIZE);
            return DEFAULT_L1_BATCH_SIZE;
        }
        return size;
    }

    /** 低置信兜底阈值。 */
    public double confidenceFloor() {
        return doubleOf(globalDoc(), "confidenceFloor", DEFAULT_CONFIDENCE_FLOOR);
    }

    /** 当日重试上限。 */
    public int maxRetriesPerDay() {
        return intOf(globalDoc(), "maxRetriesPerDay", DEFAULT_MAX_RETRIES);
    }

    /** L1 待处理回看窗口（小时）。 */
    public int l1BackfillHours() {
        return intOf(globalDoc(), "l1BackfillHours", DEFAULT_BACKFILL_HOURS);
    }

    /** L0 noise 规则引擎（关键词 + 正则热改即时生效；正则损坏条目剔除并 WARN）。 */
    public NoiseRuleEngine noiseRuleEngine() {
        JsonNode doc = l0Doc();
        List<String> keywords = stringListOf(doc, "noiseKeywords");
        List<NoiseRuleEngine.NamedPattern> patterns = new ArrayList<>();
        JsonNode rawPatterns = doc == null ? null : doc.get("noisePatterns");
        if (rawPatterns != null && rawPatterns.isArray()) {
            for (JsonNode raw : rawPatterns) {
                String expr = raw == null ? null : raw.asText(null);
                if (expr == null || expr.isBlank()) {
                    continue;
                }
                try {
                    patterns.add(
                            new NoiseRuleEngine.NamedPattern(
                                    "自定义" + patterns.size(), Pattern.compile(expr)));
                } catch (PatternSyntaxException e) {
                    log.warn("pipeline.l0.noisePatterns 含非法正则（已剔除）: {}", expr);
                }
            }
        }
        if (keywords.isEmpty() && patterns.isEmpty()) {
            return NoiseRuleEngine.withDefaults();
        }
        return new NoiseRuleEngine(keywords, patterns);
    }

    /** 近重复参数（simhash 距离缺省 18 = ADR-0047 勘定）。 */
    public DupParams dupParams() {
        JsonNode doc = l0Doc();
        return new DupParams(
                intOf(doc, "simhashDistanceMax", 18),
                doubleOf(doc, "editDistanceMax", 0.25),
                intOf(doc, "minTitleLength", 8));
    }

    /** 近重复比较池回看窗口（小时）。 */
    public int nearDupWindowHours() {
        return intOf(l0Doc(), "nearDupWindowHours", DEFAULT_BACKFILL_HOURS);
    }

    // —— L2 事件提取参数（M15 T122，方案 §4.4 / §4.8 pipeline.l2 键） ——

    /** L2 批大小（缺省 10，方案 §4.4 模板契约）。 */
    public int l2BatchSize() {
        int size = intOf(l2Doc(), "l2BatchSize", DEFAULT_L2_BATCH_SIZE);
        return size <= 0 ? DEFAULT_L2_BATCH_SIZE : size;
    }

    /** 重要性预筛阈值（缺省 2.5）。 */
    public double l2Threshold() {
        return doubleOf(l2Doc(), "threshold", DEFAULT_L2_THRESHOLD);
    }

    /** 日配额比例（缺省 0.2——REQ 场景 4 命中量校准至 ≤20%）。 */
    public double l2QuotaRatio() {
        double ratio = doubleOf(l2Doc(), "quotaRatio", DEFAULT_L2_QUOTA_RATIO);
        return ratio <= 0 || ratio > 1 ? DEFAULT_L2_QUOTA_RATIO : ratio;
    }

    /** 标的池命中加成（缺省 1.5）。 */
    public double l2SubjectBonus() {
        return doubleOf(l2Doc(), "subjectBonus", ImportanceScorer.defaults().subjectBonus());
    }

    /** 源类别权重表（缺省：政策/宏观 2.0 · 快讯 1.5 · 媒体/国际/自建 1.0）。 */
    public Map<String, Double> l2SourceWeights() {
        JsonNode node = l2Doc() == null ? null : l2Doc().get("sourceWeights");
        Map<String, Double> weights = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            field -> {
                                if (field.getValue().isNumber()) {
                                    weights.put(field.getKey(), field.getValue().asDouble());
                                }
                            });
        }
        return weights.isEmpty() ? ImportanceScorer.defaults().sourceWeights() : weights;
    }

    /** 强触发词表（缺省 19 词，方案 §4.4）。 */
    public List<String> l2StrongTriggers() {
        List<String> words = stringListOf(l2Doc(), "strongTriggers");
        return words.isEmpty() ? ImportanceScorer.defaults().strongTriggers() : words;
    }

    /** 中触发词表（缺省 15 词，方案 §4.4）。 */
    public List<String> l2MediumTriggers() {
        List<String> words = stringListOf(l2Doc(), "mediumTriggers");
        return words.isEmpty() ? ImportanceScorer.defaults().mediumTriggers() : words;
    }

    /** 重要性打分参数组装（ImportanceScorer 消费形态）。 */
    public ImportanceScorer.ScorerParams l2ScorerParams() {
        return new ImportanceScorer.ScorerParams(
                l2SourceWeights(),
                l2StrongTriggers(),
                l2MediumTriggers(),
                l2SubjectBonus(),
                l2Threshold());
    }

    private JsonNode globalDoc() {
        return doc(KEY_PIPELINE_GLOBAL);
    }

    private JsonNode l0Doc() {
        return doc(KEY_PIPELINE_L0);
    }

    private JsonNode l2Doc() {
        return doc(KEY_PIPELINE_L2);
    }

    private JsonNode doc(String configKey) {
        return configService.read(configKey).map(entry -> readTree(entry.json())).orElse(null);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("管道配置解析失败（回落代码缺省）: {}", e.getMessage());
            return null;
        }
    }

    private static int intOf(JsonNode doc, String field, int defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.canConvertToInt()) {
            return defaultValue;
        }
        return node.asInt();
    }

    private static long longOf(JsonNode doc, String field, long defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.canConvertToLong()) {
            return defaultValue;
        }
        return node.asLong();
    }

    private static double doubleOf(JsonNode doc, String field, double defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.isNumber()) {
            return defaultValue;
        }
        return node.asDouble();
    }

    private static List<String> stringListOf(JsonNode doc, String field) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
