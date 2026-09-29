package com.info.platform.application.newspulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.PipelineSettings;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.newspulse.NewsPulseRepository;
import com.info.platform.domain.newspulse.NewsPulseRepository.PulseRow;
import com.info.platform.domain.newspulse.NewsPulseRepository.WindowItem;
import com.info.platform.domain.newspulse.NewsPulseWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 资讯脉搏应用服务（V3.2 M28）：多时间窗（30m~24h）资讯归纳分析。
 *
 * <p>双产物同快照：①<b>规则统计</b>恒产出（L1 行业分布 + 标的回联市场归集 A股/港股/美股——硬数据零成本）； ②<b>LLM
 * 结构化分析</b>（overview/keyEvents/hotTracks/sentiment，brief_type=11 模板）失败/不可解析降级为 null
 * 不阻塞统计段。快照版本化追加（表 V36），Job 按窗口时长错峰刷新、手动刷新带 最小间隔守卫。
 *
 * <p>归集口径（M29 T254 显式四值化）：市场由 matched_subjects 市场字段优先、代码前缀推导兜底（SH/SZ→A股、HK→港股、US→美股，
 * 其余不计桶），未回联标的的条目计入「未关联」桶（国际/宏观/市场类资讯的诚实呈现）。
 */
@Service
public class NewsPulseService {

    private static final Logger log = LoggerFactory.getLogger(NewsPulseService.class);

    /** LLM 上下文条目上限（重要性降序截断——成本护栏，窗口全量计数另取）。 */
    static final int LLM_ITEMS_CAP = 150;

    /** 行业分布统计保留档数。 */
    private static final int INDUSTRY_STATS_CAP = 15;

    /** 每市场保留标的数。 */
    private static final int MARKET_TOP_SUBJECTS = 8;

    /** 手动刷新最小间隔（分钟）——成本护栏。 */
    private static final long MANUAL_REFRESH_MIN_INTERVAL_MINUTES = 5;

    private final NewsPulseRepository repository;
    private final LlmGateway llmGateway;
    private final com.info.platform.application.ai.PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NewsPulseService(
            NewsPulseRepository repository,
            LlmGateway llmGateway,
            com.info.platform.application.ai.PromptTemplateService promptTemplateService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 各窗口最新快照（从未分析缺席——视图层补 null 占位）。 */
    public List<PulseRow> latestEachWindow() {
        return repository.findLatestEachWindow();
    }

    /** 单窗口最新快照。 */
    public PulseRow latest(String windowCode) {
        return repository.findLatest(windowCode).orElse(null);
    }

    /** Job 判定：窗口是否需要刷新（无快照，或距上次分析截止 ≥ 窗口时长 × 0.9——30m 档每 tick 必刷）。 */
    public boolean stale(NewsPulseWindow window) {
        return repository
                .findLatest(window.code())
                .map(
                        row -> {
                            Instant lastEnd = Instant.parse(row.windowEnd());
                            return clock.instant()
                                    .isAfter(
                                            lastEnd.plus(
                                                    window.duration()
                                                            .multipliedBy(9)
                                                            .dividedBy(10)));
                        })
                .orElse(true);
    }

    /**
     * 分析一个时间窗并落快照。
     *
     * @param manual true = 手动刷新（带最小间隔守卫；false = Job 轮次）
     */
    public PulseRow analyze(NewsPulseWindow window, boolean manual) {
        Instant windowEnd = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant windowStart = windowEnd.minus(window.duration());
        if (manual) {
            repository
                    .findLatest(window.code())
                    .filter(
                            row ->
                                    windowEnd.isBefore(
                                            Instant.parse(row.windowEnd())
                                                    .plus(
                                                            Duration.ofMinutes(
                                                                    MANUAL_REFRESH_MIN_INTERVAL_MINUTES))))
                    .ifPresent(
                            row -> {
                                throw new BusinessException(
                                        ErrorCode.PARAM_INVALID,
                                        "刷新过于频繁（距上次分析不足 "
                                                + MANUAL_REFRESH_MIN_INTERVAL_MINUTES
                                                + " 分钟）");
                            });
        }

        long total = repository.countWindowItems(iso(windowStart), iso(windowEnd));
        List<WindowItem> items =
                repository.findWindowItems(iso(windowStart), iso(windowEnd), LLM_ITEMS_CAP);
        List<IndustryCount> industryStats = rollupIndustry(items);
        List<MarketStat> marketStats = rollupMarket(items);
        // 全窗口分类口径：截断列表只做 LLM 上下文，不参与覆盖率分子分母
        int classifiedCount =
                (int)
                        Math.min(
                                total,
                                repository.countWindowClassified(iso(windowStart), iso(windowEnd)));

        AnalysisAttempt attempt =
                total == 0
                        ? new AnalysisAttempt(null, null, null, "NO_NEWS")
                        : requestAnalysis(
                                window,
                                windowStart,
                                windowEnd,
                                total,
                                classifiedCount,
                                industryStats,
                                marketStats,
                                items);

        PulseRow row =
                new PulseRow(
                        null,
                        window.code(),
                        iso(windowStart),
                        iso(windowEnd),
                        (int) total,
                        classifiedCount,
                        toJson(industryStats),
                        toJson(marketStats),
                        attempt.analysis(),
                        attempt.model(),
                        attempt.promptVersion(),
                        manual ? "MANUAL" : "JOB",
                        attempt.analysis() == null,
                        attempt.reason(),
                        null);
        PulseRow saved = repository.insert(row);
        log.info(
                "资讯脉搏快照落库 window={} news={} classified={} degraded={} reason={}（Job 留痕摘要）",
                window.code(),
                total,
                classifiedCount,
                attempt.analysis() == null,
                attempt.reason());
        return saved;
    }

    // ---- LLM 段 ----

    /** 单次 LLM 分析请求；失败/不可解析降级 null（统计段照常）。 */
    private AnalysisAttempt requestAnalysis(
            NewsPulseWindow window,
            Instant windowStart,
            Instant windowEnd,
            long total,
            int classifiedCount,
            List<IndustryCount> industryStats,
            List<MarketStat> marketStats,
            List<WindowItem> items) {
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.NEWS_PULSE);
            Map<String, String> ctx = new HashMap<>();
            ctx.put("window", window.label());
            ctx.put("windowStart", iso(windowStart));
            ctx.put("windowEnd", iso(windowEnd));
            ctx.put("newsCount", String.valueOf(total));
            ctx.put("classifiedCount", String.valueOf(classifiedCount));
            ctx.put("industryStats", industryStats.toString());
            ctx.put("marketStats", marketStats.toString());
            ctx.put("maxItems", String.valueOf(LLM_ITEMS_CAP));
            ctx.put("items", renderItems(items));
            List<ChatMessage> messages = promptTemplateService.render(template, ctx);
            LlmRequest request =
                    LlmRequest.pipeline(
                            messages,
                            BriefType.NEWS_PULSE.key(),
                            PipelineSettings.L1_TEMPERATURE,
                            PipelineSettings.L1_MAX_TOKENS);
            LlmResponse response = llmGateway.chat(request);
            String analysis = sanitizeAnalysis(response.content());
            if (analysis == null) {
                return new AnalysisAttempt(null, null, template.getVersion(), "输出不可解析（降级纯统计）");
            }
            return new AnalysisAttempt(analysis, response.model(), template.getVersion(), null);
        } catch (LlmException e) {
            log.warn("资讯脉搏 LLM 调用失败（降级纯统计）: window={} {}", window.code(), e.getMessage());
            return new AnalysisAttempt(null, null, null, String.valueOf(e.getMessage()));
        } catch (BusinessException e) {
            log.warn("资讯脉搏前置失败（降级纯统计）: window={} {}", window.code(), e.getMessage());
            return new AnalysisAttempt(null, null, null, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("资讯脉搏 LLM 异常（降级纯统计）: window={} {}", window.code(), e.toString());
            return new AnalysisAttempt(null, null, null, e.toString());
        }
    }

    /** LLM 输出净化：剥 ```json 围栏 → 解析校验顶层结构（四键齐备）→ 原样存 JSON 文本。 */
    private String sanitizeAnalysis(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node == null
                    || !node.isObject()
                    || !node.has("overview")
                    || !node.has("keyEvents")
                    || !node.has("hotTracks")
                    || !node.has("sentiment")) {
                return null;
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /** 条目渲染（[L1] 标题（标的:名1,名2）——一行一条，重要性降序即输入序）。 */
    private String renderItems(List<WindowItem> items) {
        StringBuilder sb = new StringBuilder();
        for (WindowItem item : items) {
            sb.append('[')
                    .append(item.mainCategory() == null ? "未分类" : item.mainCategory())
                    .append("] ")
                    .append(item.title());
            List<String> subjectNames = subjectNamesOf(item.matchedSubjectsJson());
            if (!subjectNames.isEmpty()) {
                sb.append("（标的:").append(String.join(",", subjectNames)).append('）');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---- 规则统计 ----

    /** L1 行业分布（count 降序截断 15 档）。 */
    private List<IndustryCount> rollupIndustry(List<WindowItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WindowItem item : items) {
            String category = item.mainCategory() == null ? "未分类" : item.mainCategory();
            counts.merge(category, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(INDUSTRY_STATS_CAP)
                .map(entry -> new IndustryCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 市场归集：matched_subjects 市场字段优先、代码前缀推导兜底（A股/港股/美股，无标的条目计入「未关联」——M29 T254 显式四值化）。 */
    private List<MarketStat> rollupMarket(List<WindowItem> items) {
        Map<String, Integer> newsByMarket = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> subjectCountByMarket = new LinkedHashMap<>();
        for (WindowItem item : items) {
            List<MatchedRef> subjects = subjectsOf(item.matchedSubjectsJson());
            if (subjects.isEmpty()) {
                newsByMarket.merge("未关联", 1, Integer::sum);
                continue;
            }
            java.util.HashSet<String> itemMarkets = new java.util.HashSet<>();
            for (MatchedRef subject : subjects) {
                String market = marketOf(subject);
                if (market == null) {
                    continue; // 前缀不可识别且无市场字段 → 不计桶（C13 显式四值化，不再误归「美股」）
                }
                itemMarkets.add(market);
                subjectCountByMarket
                        .computeIfAbsent(market, key -> new LinkedHashMap<>())
                        .merge(subject.name(), 1, Integer::sum);
            }
            for (String market : itemMarkets) {
                newsByMarket.merge(market, 1, Integer::sum);
            }
        }
        List<MarketStat> result = new ArrayList<>();
        for (String market : List.of("A股", "港股", "美股", "未关联")) {
            int newsCount = newsByMarket.getOrDefault(market, 0);
            List<String> topSubjects =
                    subjectCountByMarket.getOrDefault(market, Map.of()).entrySet().stream()
                            .sorted(
                                    Map.Entry.<String, Integer>comparingByValue(
                                            Comparator.reverseOrder()))
                            .limit(MARKET_TOP_SUBJECTS)
                            .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
                            .toList();
            result.add(new MarketStat(market, newsCount, topSubjects));
        }
        return result;
    }

    /**
     * 标的市场判定（M29 T254，方案 §4 C13）：{@code matched_subjects} 市场字段优先（SubjectMatcher 派生留痕时更准，消歧优于前缀）、
     * 代码前缀推导兜底（SH/SZ→A股、HK→港股、US→美股）；两路均不可识别返回 null（不计桶——不再把未知前缀误归「美股」）。
     */
    static String marketOf(MatchedRef subject) {
        String byField = marketLabelOfField(subject.market());
        return byField != null ? byField : marketOfCode(subject.code());
    }

    /** 市场字段（subject_master.market 枚举名形态）→ 桶标签；INDEX/SECTOR 等非个股市场不计桶。 */
    private static String marketLabelOfField(String market) {
        if (market == null || market.isBlank()) {
            return null;
        }
        return switch (market.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "A_SHARE" -> "A股";
            case "HK" -> "港股";
            case "US" -> "美股";
            default -> null;
        };
    }

    /** 代码前缀推导（SH/SZ→A股、HK→港股、US+大写 ticker→美股；其余 null 不计桶）。 */
    static String marketOfCode(String code) {
        if (code == null) {
            return null;
        }
        if (code.startsWith("SH") || code.startsWith("SZ")) {
            return "A股";
        }
        if (code.startsWith("HK")) {
            return "港股";
        }
        if (code.startsWith("US")) {
            return "美股";
        }
        return null;
    }

    private List<MatchedRef> subjectsOf(String matchedSubjectsJson) {
        List<MatchedRef> result = new ArrayList<>();
        if (matchedSubjectsJson == null || matchedSubjectsJson.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(matchedSubjectsJson);
            if (node != null && node.isArray()) {
                for (JsonNode element : node) {
                    String code = element.path("code").asText(null);
                    String name = element.path("name").asText(null);
                    if (code != null && name != null) {
                        result.add(new MatchedRef(code, name, element.path("market").asText(null)));
                    }
                }
            }
        } catch (Exception e) {
            // 回联 JSON 损坏：按无标的处理（统计面兜底不放大）
        }
        return result;
    }

    private List<String> subjectNamesOf(String matchedSubjectsJson) {
        return subjectsOf(matchedSubjectsJson).stream().map(MatchedRef::name).toList();
    }

    /** 回联标的投影（code/name + 可选 market 留痕键——T253 持久化三键形态，market 存在则优先）。 */
    private record MatchedRef(String code, String name, String market) {}

    private String iso(Instant instant) {
        return instant.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---- 统计投影（JSON 序列化形态 + toString 渲染形态）----

    /** L1 行业计数（industry_stats 元素）。 */
    public record IndustryCount(String industry, int count) {
        @Override
        public String toString() {
            return industry + "=" + count;
        }
    }

    /** 市场归集（market_stats 元素）。 */
    public record MarketStat(String market, int newsCount, List<String> topSubjects) {
        @Override
        public String toString() {
            return market
                    + ":"
                    + newsCount
                    + (topSubjects.isEmpty() ? "" : "（" + String.join(",", topSubjects) + "）");
        }
    }

    /** LLM 段产出（analysis 为 null 即降级，reason 留痕）。 */
    private record AnalysisAttempt(
            String analysis, String model, String promptVersion, String reason) {}
}
