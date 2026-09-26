package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.ai.PlaceholderProvider;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.HeatCalculator;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.analysis.TrendSignalCalculator;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.push.IndustryWeeklyReportReadyEvent;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 行业周报生成服务（应用层，M17 T145/T146，REQ 拍板四/六，方案沿 DailyReportService 同构）：周窗聚合（本周 [周一 00:00,
 * 生成时刻] vs 上周等长窗热度现算 + 事件主键归并（event_item 一行一主线，跨日去重天然成立）+ 政策动向） → 规则信号层（{@link
 * TrendSignalCalculator} 纯计算）→ 单次 LLM 语言组织（briefType=9，走向判断 AI 只组织语言，<b>置信度 trend-v1 规则层锁定——AI
 * 输出置信度与规则层不一致即该行业模板兜底</b>，零新增事实）→ 五区块周报落 {@code industry_weekly_report}（UNIQUE(week_start)
 * 幂等 / FAILED 重生成替换）。
 *
 * <p><b>护栏语义</b>（沿日报先例）：FUSED 跳过（留痕，下周一补跑窗口覆盖）；DEGRADED 保留。<b>降级语义</b>：LLM 失败/输出不可解析 →
 * 纯统计模板直出 SUCCESS（narrativeDegraded=true 如实标注 + 不发完成通知）。<b>幂等语义</b>：已 SUCCESS 直返跳过（手动重生成仅
 * FAILED，30085 契约由端点把守）。
 */
@Service
public class WeeklyReportService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    /** 统计日界（Asia/Shanghai——与日报/护栏/热度口径同源）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 事件回顾上限（周窗水位 ~900/周，精选呈现 ≤20）。 */
    static final int EVENT_REVIEW_LIMIT = 20;

    /** 热度总览升/降温行业各取数（缺省拍板六-2：环比变化最显著 Top3+Top3）。 */
    static final int TOP_MOVER_LIMIT = 3;

    /** 走向判断对象数（缺省拍板六-2：Top3 升温 + Top3 降温）。 */
    static final int TREND_INDUSTRY_LIMIT = 3;

    /** 事件全量取数上限（防御性）。 */
    static final int EVENT_FETCH_CAP = 500;

    /** 免责声明固定文案（不采信模型输出值——合规红线常驻）。 */
    static final String DISCLAIMER = "AI 分析仅供参考";

    private static final int SUMMARY_MAX_LENGTH = 2000;

    private static final int WATCH_POINT_MAX_LENGTH = 100;

    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 本服务实际注入的占位符描述符（与 {@code buildContext} 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> WEEKLY_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("weekStart", "周窗起点（Asia/Shanghai 周一 yyyy-MM-dd）"),
                    new PlaceholderDescriptor("weekEnd", "周窗终点（生成时刻日期）"),
                    new PlaceholderDescriptor("heatStats", "周内各行业热度/事件计数行（双窗现算统计）"),
                    new PlaceholderDescriptor("topEvents", "周窗代表事件行（主键归并，重要度降序 ≤20 条）"),
                    new PlaceholderDescriptor("policyLines", "政策动向行（POLICY_RELEASE 周窗清单）"),
                    new PlaceholderDescriptor("trendSignals", "规则信号行（行业/信号/环比/置信度——trend-v1 锁定）"));

    private final WeeklyReportRepository weeklyRepository;
    private final DailyReportRepository dailyRepository;
    private final HeatSnapshotRepository heatSnapshotRepository;
    private final PipelineGuardService guardService;
    private final PipelineSettings settings;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WeeklyReportService(
            WeeklyReportRepository weeklyRepository,
            DailyReportRepository dailyRepository,
            HeatSnapshotRepository heatSnapshotRepository,
            PipelineGuardService guardService,
            PipelineSettings settings,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.weeklyRepository = weeklyRepository;
        this.dailyRepository = dailyRepository;
        this.heatSnapshotRepository = heatSnapshotRepository;
        this.guardService = guardService;
        this.settings = settings;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 单周生成（核心，定时与手动重试共用）：FUSED 跳过留痕；已 SUCCESS 幂等直返；周窗聚合 → 单次 LLM → UPSERT。
     *
     * @param weekStart 周一锚点（Asia/Shanghai yyyy-MM-dd，非周一拒绝）
     */
    public GenerationOutcome generateFor(String weekStart) {
        LocalDate anchor = parseMonday(weekStart);
        if (guardService.currentLevel() == GuardLevel.FUSED) {
            log.warn("行业周报跳过（管道 FUSED，下周一窗口覆盖）: weekStart={}", weekStart);
            return GenerationOutcome.skippedOf(weekStart, "skipped(fused)");
        }
        IndustryWeeklyReport existing = weeklyRepository.findByWeekStart(weekStart).orElse(null);
        if (existing != null && existing.getStatus() == ReportStatus.SUCCESS) {
            return GenerationOutcome.skippedOf(weekStart, "already-success");
        }
        try {
            return generate(anchor);
        } catch (Exception e) {
            log.error("行业周报生成失败: weekStart={} {}", weekStart, e.toString(), e);
            persistFailure(weekStart, e.toString());
            return new GenerationOutcome(weekStart, "FAILED", false, e.toString(), 0, 0, false);
        }
    }

    /**
     * 定时入口：生成当周周报（weekStart = 本周周一锚点；周日晚 20:00 CRON 触发即覆盖完整自然周）。
     *
     * @return 窗口报告（JobRunStats 留痕数据面）
     */
    public WindowReport runScheduledWindow() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        LocalDate monday =
                today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        GenerationOutcome outcome = generateFor(monday.toString());
        String detail =
                "weekStart=" + monday + ";status=" + outcome.status()
                        + (outcome.skipped() ? ";reason=" + outcome.reason() : "");
        log.info("行业周报定时窗口完成: {}", detail);
        return new WindowReport(outcome.skipped() ? 0 : 1, outcome.skipped() ? 1 : 0, detail);
    }

    // —— 生成主链 ——

    private GenerationOutcome generate(LocalDate anchor) {
        String weekStart = anchor.toString();
        Instant now = clock.instant();
        Instant windowStart = anchor.atStartOfDay(STAT_ZONE).toInstant();
        Instant windowEnd = now;
        Duration windowLength = Duration.between(windowStart, windowEnd);
        String todayDate = LocalDate.ofInstant(now, STAT_ZONE).toString();

        // ① 热度双窗现算（本周 vs 上周等长窗——快照表 62 行常驻无时序，沿 HeatSnapshotService 现算先例）
        HeatCalculator.HeatParams params = settings.heatParams();
        Map<String, HeatCalculator.IndustryHeat> weekHeat =
                HeatCalculator.compute(heatItems(windowStart, windowEnd), params, windowEnd, windowLength);
        Map<String, HeatCalculator.IndustryHeat> prevHeat =
                HeatCalculator.compute(
                        heatItems(windowStart.minus(windowLength), windowStart),
                        params,
                        windowStart,
                        windowLength);

        // ② 事件与资讯聚合（主键归并：event_item 一行一主线）
        List<DailyReportRepository.IndustryCount> newsCounts =
                dailyRepository.countNewsByIndustry(windowStart.toString(), windowEnd.toString());
        List<DailyReportRepository.ReportEvent> events =
                weeklyRepository.findEventsBetween(weekStart, todayDate, EVENT_FETCH_CAP);

        long totalNews = 0;
        Map<String, Long> newsByIndustry = new LinkedHashMap<>();
        for (DailyReportRepository.IndustryCount count : newsCounts) {
            totalNews += count.newsCount();
            if (IndustryCategory.isSwIndustry(count.category())) {
                newsByIndustry.put(count.category(), count.newsCount());
            }
        }

        // ③ 规则信号层（trend-v1 锁定——纯计算零 AI）
        Map<String, TrendSignalCalculator.TrendResult> signals = new LinkedHashMap<>();
        for (String industry : IndustryCategory.SW_INDUSTRIES) {
            HeatCalculator.IndustryHeat week = weekHeat.getOrDefault(industry, emptyHeat());
            HeatCalculator.IndustryHeat prev = prevHeat.getOrDefault(industry, emptyHeat());
            signals.put(
                    industry,
                    TrendSignalCalculator.compute(
                            new TrendSignalCalculator.TrendInput(
                                    industry,
                                    week.score(),
                                    prev.score(),
                                    week.eventCount(),
                                    prev.eventCount(),
                                    policyCountOf(events, industry))));
        }

        // ④ LLM 语言组织（AI 只写叙述与下周关注点；走向判断叙述经篡改校验）
        NarrativeAttempt attempt = requestNarrative(weekStart, todayDate, weekHeat, prevHeat, signals, events, newsByIndustry, totalNews);

        String contentJson =
                assembleContent(
                        weekStart,
                        todayDate,
                        totalNews,
                        events,
                        weekHeat,
                        prevHeat,
                        signals,
                        newsByIndustry,
                        attempt);
        weeklyRepository.upsert(
                IndustryWeeklyReport.success(
                        weekStart,
                        contentJson,
                        heatTopJson(weekHeat, prevHeat, signals),
                        attempt.error(),
                        attempt.narrative() == null ? null : attempt.promptVersion(),
                        basisOf(),
                        now));
        if (attempt.narrative() != null) {
            eventPublisher.publishEvent(new IndustryWeeklyReportReadyEvent(weekStart, false, now));
        }
        log.info(
                "行业周报生成完成: weekStart={} news={} events={} narrative={}",
                weekStart,
                totalNews,
                events.size(),
                attempt.narrative() != null ? "ok" : "degraded");
        return new GenerationOutcome(
                weekStart,
                "SUCCESS",
                false,
                null,
                totalNews,
                events.size(),
                attempt.narrative() == null);
    }

    /** 单次 LLM 叙述请求（briefType=9；失败/不可解析/越界 → 降级留痕，不抛出）。 */
    private NarrativeAttempt requestNarrative(
            String weekStart,
            String weekEnd,
            Map<String, HeatCalculator.IndustryHeat> weekHeat,
            Map<String, HeatCalculator.IndustryHeat> prevHeat,
            Map<String, TrendSignalCalculator.TrendResult> signals,
            List<DailyReportRepository.ReportEvent> events,
            Map<String, Long> newsByIndustry,
            long totalNews) {
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_WEEKLY);
            List<ChatMessage> messages =
                    promptTemplateService.render(
                            template,
                            buildContext(
                                    weekStart, weekEnd, weekHeat, signals, events, newsByIndustry, totalNews));
            LlmRequest request =
                    LlmRequest.pipeline(
                            messages,
                            BriefType.INDUSTRY_WEEKLY.key(),
                            PipelineSettings.L1_TEMPERATURE,
                            PipelineSettings.L1_MAX_TOKENS);
            LlmResponse response = llmGateway.chat(request);
            Narrative narrative = parseNarrative(response.content(), signals);
            if (narrative == null) {
                log.warn("行业周报叙述输出不可解析（降级模板直出）: weekStart={}", weekStart);
                return new NarrativeAttempt(null, "叙述输出不可解析（降级模板直出）", template.getVersion());
            }
            return new NarrativeAttempt(narrative, null, template.getVersion());
        } catch (LlmException | BusinessException e) {
            log.warn("行业周报 LLM 调用失败（降级模板直出）: weekStart={} {}", weekStart, e.toString());
            return new NarrativeAttempt(null, String.valueOf(e.getMessage() == null ? e.toString() : e.getMessage()), null);
        } catch (RuntimeException e) {
            log.warn("行业周报 LLM 调用异常（降级模板直出）: weekStart={} {}", weekStart, e.toString());
            return new NarrativeAttempt(null, e.toString(), null);
        }
    }

    /**
     * 模型叙述解析 + <b>篡改校验</b>（T146 红线）：summary/watchPoints 直取；trendNarratives 逐条校验——行业 ∈
     * 规则信号选中集 且（无置信度字段或与规则层一致）方采纳，越界/篡改条目丢弃（该行业叙述走模板兜底，置信度恒规则层值）。
     */
    private Narrative parseNarrative(
            String content, Map<String, TrendSignalCalculator.TrendResult> signals) {
        if (content == null || content.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(content.trim()));
        } catch (Exception e) {
            return null;
        }
        if (!root.isObject()) {
            return null;
        }
        String summary = textOf(root.get("summary"));
        if (summary == null) {
            return null;
        }
        Map<String, String> trendNarratives = new HashMap<>();
        JsonNode narratives = root.get("trendNarratives");
        if (narratives != null && narratives.isArray()) {
            for (JsonNode node : narratives) {
                String industry = textOf(node.get("industry"));
                String narrative = textOf(node.get("narrative"));
                if (industry == null || narrative == null || !signals.containsKey(industry)) {
                    continue; // 越界行业丢弃（零新增事实）
                }
                JsonNode confidenceNode = node.get("confidence");
                if (confidenceNode != null && !confidenceNode.isNull()) {
                    TrendSignalCalculator.TrendConfidence claimed =
                            TrendSignalCalculator.TrendConfidence.fromName(confidenceNode.asText());
                    if (claimed == null || claimed != signals.get(industry).confidence()) {
                        log.warn(
                                "走向判断 AI 篡改置信度拦截（行业走模板兜底）: industry={} claimed={} rule={}",
                                industry,
                                claimed,
                                signals.get(industry).confidence());
                        continue; // 置信度与规则层不一致 → 该行业叙述丢弃
                    }
                }
                trendNarratives.put(industry, truncate(narrative, WATCH_POINT_MAX_LENGTH * 2));
            }
        }
        List<String> watchPoints = new ArrayList<>();
        JsonNode watches = root.get("watchPoints");
        if (watches != null && watches.isArray()) {
            for (JsonNode watch : watches) {
                String point = textOf(watch);
                if (point != null) {
                    watchPoints.add(truncate(point, WATCH_POINT_MAX_LENGTH));
                }
            }
        }
        return new Narrative(truncate(summary, SUMMARY_MAX_LENGTH), trendNarratives, watchPoints);
    }

    /** content 五区块 JSON 组装（数字全部来自统计与规则层；disclaimer 固定）。 */
    private String assembleContent(
            String weekStart,
            String weekEnd,
            long totalNews,
            List<DailyReportRepository.ReportEvent> events,
            Map<String, HeatCalculator.IndustryHeat> weekHeat,
            Map<String, HeatCalculator.IndustryHeat> prevHeat,
            Map<String, TrendSignalCalculator.TrendResult> signals,
            Map<String, Long> newsByIndustry,
            NarrativeAttempt attempt) {
        ObjectNode content = objectMapper.createObjectNode();
        Narrative narrative = attempt.narrative();
        boolean degraded = narrative == null;
        content.put(
                "summary",
                degraded ? templateSummary(weekStart, weekEnd, totalNews, events) : narrative.summary());
        content.put("narrativeDegraded", degraded);
        content.put("weekStart", weekStart);
        content.put("weekEnd", weekEnd);
        content.put("totalNews", totalNews);
        content.put("totalEvents", events.size());

        // 区块 1：热度总览（Top 升/降行业 + 周环比）
        ArrayNode risers = content.putArray("topRisers");
        ArrayNode fallers = content.putArray("topFallers");
        movers(signals, true).forEach(industry -> addMover(risers, industry, weekHeat, prevHeat, signals, newsByIndustry));
        movers(signals, false).forEach(industry -> addMover(fallers, industry, weekHeat, prevHeat, signals, newsByIndustry));

        // 区块 2：事件回顾（主键归并，重要度降序 ≤20）
        ArrayNode review = content.putArray("eventReview");
        for (DailyReportRepository.ReportEvent event : events.stream().limit(EVENT_REVIEW_LIMIT).toList()) {
            ObjectNode node = review.addObject();
            node.put("eventId", event.eventId());
            node.put("newsId", event.newsId());
            node.put("eventType", event.eventType().name());
            node.put("summary", event.summary());
            ArrayNode industries = node.putArray("industries");
            event.industries().forEach(industries::add);
            node.put("direction", event.direction().name());
            node.put("importance", event.importance().name());
            node.put("quote", event.quote());
            node.put("eventDate", LocalDate.ofInstant(event.eventTime(), STAT_ZONE).toString());
            node.put("eventTime", event.eventTime() == null ? null : event.eventTime().toString());
        }

        // 区块 3：政策动向（POLICY_RELEASE 周窗清单）
        ArrayNode policies = content.putArray("policyMoves");
        for (DailyReportRepository.ReportEvent event : events.stream()
                .filter(event -> event.eventType() == EventType.POLICY_RELEASE)
                .limit(EVENT_REVIEW_LIMIT)
                .toList()) {
            ObjectNode node = policies.addObject();
            node.put("eventId", event.eventId());
            node.put("summary", event.summary());
            ArrayNode industries = node.putArray("industries");
            event.industries().forEach(industries::add);
            node.put("direction", event.direction().name());
            node.put("quote", event.quote());
            node.put("eventTime", event.eventTime() == null ? null : event.eventTime().toString());
        }

        // 区块 4：下周关注点（AI 叙述或规则模板直出）
        ArrayNode watch = content.putArray("nextWeekWatch");
        if (narrative != null && !narrative.watchPoints().isEmpty()) {
            narrative.watchPoints().forEach(watch::add);
        } else {
            templateWatchPoints(signals).forEach(watch::add);
        }

        // 区块 5：走向判断（trend-v1 规则层锁定 + AI 语言组织/模板兜底）
        ObjectNode trend = content.putObject("trendJudgement");
        trend.put("basis", TrendSignalCalculator.BASIS);
        ArrayNode items = trend.putArray("items");
        List<String> selected = trendIndustries(signals);
        for (String industry : selected) {
            TrendSignalCalculator.TrendResult result = signals.get(industry);
            HeatCalculator.IndustryHeat week = weekHeat.getOrDefault(industry, emptyHeat());
            HeatCalculator.IndustryHeat prev = prevHeat.getOrDefault(industry, emptyHeat());
            ObjectNode item = items.addObject();
            item.put("industry", industry);
            item.put("signal", result.signal().name());
            item.put("signalLabel", result.signal().displayName());
            item.put("confidence", result.confidence().name());
            item.put("confidenceLabel", result.confidence().displayName());
            item.put("deltaPct", result.deltaPct());
            item.put("weekScore", week.score());
            item.put("prevScore", prev.score());
            item.put("eventCount", week.eventCount());
            item.put("policyCount", policyCountOf(events, industry));
            String aiNarrative = narrative == null ? null : narrative.trendNarratives().get(industry);
            item.put(
                    "narrative",
                    aiNarrative != null
                            ? aiNarrative
                            : templateTrendNarrative(
                                    industry, result, week, policyCountOf(events, industry)));
            item.put("narrativeSource", aiNarrative != null ? "LLM" : "TEMPLATE");
            ArrayNode evidence = item.putArray("evidenceEventIds");
            events.stream()
                    .filter(event -> event.industries().contains(industry))
                    .limit(5)
                    .forEach(event -> evidence.add(event.eventId()));
        }
        content.put("disclaimer", DISCLAIMER);
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalStateException("周报 content 序列化失败: " + e.getMessage(), e);
        }
    }

    // —— 聚合与模板辅助 ——

    /** 窗口条目取数（仓储 WindowItem → 计算器 HeatItem，HeatSnapshotService 同款）。 */
    private List<HeatCalculator.HeatItem> heatItems(Instant from, Instant to) {
        return heatSnapshotRepository.findWindowItems(from.toString(), to.toString()).stream()
                .map(
                        item ->
                                new HeatCalculator.HeatItem(
                                        item.mainCategory(),
                                        item.publishedAt(),
                                        item.eventImportance(),
                                        item.affectedIndustries()))
                .toList();
    }

    private static HeatCalculator.IndustryHeat emptyHeat() {
        return new HeatCalculator.IndustryHeat(0.0, 0, 0);
    }

    private static long policyCountOf(List<DailyReportRepository.ReportEvent> events, String industry) {
        return events.stream()
                .filter(event -> event.eventType() == EventType.POLICY_RELEASE)
                .filter(event -> event.industries().contains(industry))
                .count();
    }

    /** 热度总览升/降温 Top（|delta| 降序各 ≤3；零变化行业不入选）。 */
    private List<String> movers(
            Map<String, TrendSignalCalculator.TrendResult> signals, boolean rising) {
        List<Map.Entry<String, TrendSignalCalculator.TrendResult>> entries = new ArrayList<>();
        for (Map.Entry<String, TrendSignalCalculator.TrendResult> entry : signals.entrySet()) {
            double delta = entry.getValue().deltaPct();
            if (delta != 0 && rising == (delta > 0)) {
                entries.add(entry);
            }
        }
        entries.sort(
                rising
                        ? java.util.Comparator.comparingDouble(
                                        (Map.Entry<String, TrendSignalCalculator.TrendResult> entry) ->
                                                entry.getValue().deltaPct())
                                .reversed()
                        : java.util.Comparator.comparingDouble(
                                (Map.Entry<String, TrendSignalCalculator.TrendResult> entry) ->
                                        entry.getValue().deltaPct()));
        return entries.stream().limit(TOP_MOVER_LIMIT).map(Map.Entry::getKey).toList();
    }

    /** 走向判断对象：升温 Top3（delta 降序）+ 降温 Top3（缺省拍板六-2，STABLE 不叙述）。 */
    private List<String> trendIndustries(Map<String, TrendSignalCalculator.TrendResult> signals) {
        List<String> heating =
                signals.entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue().signal()
                                                == TrendSignalCalculator.TrendSignal.HEATING)
                        .sorted(
                                java.util.Comparator.comparingDouble(
                                                (Map.Entry<String, TrendSignalCalculator.TrendResult>
                                                                entry)
                                                        -> entry.getValue().deltaPct())
                                        .reversed())
                        .limit(TREND_INDUSTRY_LIMIT)
                        .map(Map.Entry::getKey)
                        .toList();
        List<String> cooling =
                signals.entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue().signal()
                                                == TrendSignalCalculator.TrendSignal.COOLING)
                        .sorted(
                                java.util.Comparator.comparingDouble(
                                        (Map.Entry<String, TrendSignalCalculator.TrendResult> entry)
                                                -> entry.getValue().deltaPct()))
                        .limit(TREND_INDUSTRY_LIMIT)
                        .map(Map.Entry::getKey)
                        .toList();
        List<String> selected = new ArrayList<>(heating);
        selected.addAll(cooling);
        return selected;
    }

    private void addMover(
            ArrayNode array,
            String industry,
            Map<String, HeatCalculator.IndustryHeat> weekHeat,
            Map<String, HeatCalculator.IndustryHeat> prevHeat,
            Map<String, TrendSignalCalculator.TrendResult> signals,
            Map<String, Long> newsByIndustry) {
        ObjectNode node = array.addObject();
        node.put("industry", industry);
        node.put("score", weekHeat.getOrDefault(industry, emptyHeat()).score());
        node.put("prevScore", prevHeat.getOrDefault(industry, emptyHeat()).score());
        node.put("deltaPct", signals.get(industry).deltaPct());
        node.put("newsCount", newsByIndustry.getOrDefault(industry, 0L));
        node.put("eventCount", weekHeat.getOrDefault(industry, emptyHeat()).eventCount());
    }

    /** 降级模板 summary（结构化统计直出）。 */
    private String templateSummary(String weekStart, String weekEnd, long totalNews, List<DailyReportRepository.ReportEvent> events) {
        return String.format(
                "本周（%s ~ %s）行业面结构化统计：资讯 %d 条、事件 %d 条；本版为纯统计模板直出（AI 叙述暂不可用），"
                        + "热度总览/事件回顾/政策动向/走向判断见各区块。",
                weekStart, weekEnd, totalNews, events.size());
    }

    /** 降级模板下周关注点（规则直出：升温行业延续 + 政策动向 + 免责口径）。 */
    private List<String> templateWatchPoints(Map<String, TrendSignalCalculator.TrendResult> signals) {
        List<String> points = new ArrayList<>();
        List<String> heating = trendIndustries(signals);
        if (!heating.isEmpty()) {
            points.add("关注热度升温行业动向：" + String.join("、", heating));
        }
        points.add("下周宏观与产业政策窗口以官方发布日程为准（周报政策动向区块滚动更新）");
        points.add("以上为统计口径提示，不构成投资建议");
        return points;
    }

    /** 走向判断模板叙述（规则信号直出——AI 缺位/被拒兜底）。 */
    private String templateTrendNarrative(
            String industry,
            TrendSignalCalculator.TrendResult result,
            HeatCalculator.IndustryHeat week,
            long policyCount) {
        return String.format(
                "%s本周热度环比%+.1f%%（%s），周内事件 %d 条、政策 %d 条，置信度%s（trend-v1 规则层锁定）。",
                industry,
                result.deltaPct(),
                result.signal().displayName(),
                week.eventCount(),
                policyCount,
                result.confidence().displayName());
    }

    /** heat_top 留存 JSON（周/上周双窗值 + 信号——对账与趋势回看）。 */
    private String heatTopJson(
            Map<String, HeatCalculator.IndustryHeat> weekHeat,
            Map<String, HeatCalculator.IndustryHeat> prevHeat,
            Map<String, TrendSignalCalculator.TrendResult> signals) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (String industry : IndustryCategory.SW_INDUSTRIES) {
            ObjectNode row = rows.addObject();
            row.put("industry", industry);
            row.put("weekScore", weekHeat.getOrDefault(industry, emptyHeat()).score());
            row.put("prevScore", prevHeat.getOrDefault(industry, emptyHeat()).score());
            row.put("deltaPct", signals.get(industry).deltaPct());
            row.put("eventCount", weekHeat.getOrDefault(industry, emptyHeat()).eventCount());
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("heat_top 序列化失败（落空数组）: {}", e.getMessage());
            return "[]";
        }
    }

    /** basis = trend-v1 + heat + cost 口径串。 */
    private String basisOf() {
        return TrendSignalCalculator.BASIS + " | "
                + com.info.platform.domain.analysis.HeatCalculator.basis(settings.heatParams())
                + " | " + settings.costBasis();
    }

    /** 渲染上下文（六占位符；与 {@link #provided()} 同源同序）。 */
    private Map<String, String> buildContext(
            String weekStart,
            String weekEnd,
            Map<String, HeatCalculator.IndustryHeat> weekHeat,
            Map<String, TrendSignalCalculator.TrendResult> signals,
            List<DailyReportRepository.ReportEvent> events,
            Map<String, Long> newsByIndustry,
            long totalNews) {
        StringBuilder stats = new StringBuilder();
        for (String industry : IndustryCategory.SW_INDUSTRIES) {
            HeatCalculator.IndustryHeat week = weekHeat.getOrDefault(industry, emptyHeat());
            TrendSignalCalculator.TrendResult result = signals.get(industry);
            if (week.score() <= 0 && week.eventCount() == 0) {
                continue;
            }
            if (!stats.isEmpty()) {
                stats.append('；');
            }
            stats.append(industry)
                    .append("=热度")
                    .append(Math.round(week.score()))
                    .append("/事件")
                    .append(week.eventCount())
                    .append("/资讯")
                    .append(newsByIndustry.getOrDefault(industry, 0L))
                    .append("/环比")
                    .append(String.format("%+.1f%%", result.deltaPct()));
        }
        if (stats.isEmpty()) {
            stats.append("（本周无已归类资讯）");
        }
        StringBuilder eventLines = new StringBuilder();
        int index = 1;
        for (DailyReportRepository.ReportEvent event : events.stream().limit(EVENT_REVIEW_LIMIT).toList()) {
            if (!eventLines.isEmpty()) {
                eventLines.append('\n');
            }
            eventLines
                    .append(index++)
                    .append(". [")
                    .append(event.importance().name())
                    .append('|')
                    .append(event.direction().name())
                    .append("] ")
                    .append(String.join(",", event.industries()))
                    .append(" | ")
                    .append(event.summary());
        }
        if (eventLines.isEmpty()) {
            eventLines.append("（本周无结构化事件）");
        }
        StringBuilder policyLines = new StringBuilder();
        int policyIndex = 1;
        for (DailyReportRepository.ReportEvent event : events) {
            if (event.eventType() != EventType.POLICY_RELEASE) {
                continue;
            }
            if (!policyLines.isEmpty()) {
                policyLines.append('\n');
            }
            policyLines.append(policyIndex++).append(". ").append(event.summary());
        }
        if (policyLines.isEmpty()) {
            policyLines.append("（本周无政策发布事件）");
        }
        StringBuilder trendLines = new StringBuilder();
        for (String industry : trendIndustries(signals)) {
            TrendSignalCalculator.TrendResult result = signals.get(industry);
            if (!trendLines.isEmpty()) {
                trendLines.append('\n');
            }
            trendLines
                    .append(industry)
                    .append(": ")
                    .append(result.signal().displayName())
                    .append(' ')
                    .append(String.format("%+.1f%%", result.deltaPct()))
                    .append(" 置信度")
                    .append(result.confidence().name())
                    .append("（规则层锁定，AI 不可改）");
        }
        if (trendLines.isEmpty()) {
            trendLines.append("（本周无显著升温/降温行业）");
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("weekStart", weekStart);
        ctx.put("weekEnd", weekEnd);
        ctx.put("heatStats", stats.toString());
        ctx.put("topEvents", eventLines.toString());
        ctx.put("policyLines", policyLines.toString());
        ctx.put("trendSignals", trendLines.toString());
        return ctx;
    }

    private void persistFailure(String weekStart, String error) {
        try {
            weeklyRepository.upsert(
                    IndustryWeeklyReport.failed(
                            weekStart, truncate(error, ERROR_MESSAGE_MAX_LENGTH), clock.instant()));
        } catch (Exception persistError) {
            log.error("行业周报 FAILED 行落库失败（交 JobExecutor FAILED 留痕）: {}", persistError.toString());
        }
    }

    private static LocalDate parseMonday(String weekStart) {
        LocalDate anchor;
        try {
            anchor = LocalDate.parse(weekStart);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("weekStart 须为 yyyy-MM-dd 周一锚点: " + weekStart);
        }
        if (anchor.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 须为周一（周窗锚点）: " + weekStart);
        }
        return anchor;
    }

    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String stripCodeFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak > 0) {
                text = text.substring(firstLineBreak + 1);
            }
            int closing = text.lastIndexOf("```");
            if (closing >= 0) {
                text = text.substring(0, closing);
            }
        }
        return text;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 模型叙述（解析产物；trendNarratives 经篡改校验后的净集）。 */
    private record Narrative(
            String summary, Map<String, String> trendNarratives, List<String> watchPoints) {}

    /** 叙述请求结果（narrative 与 error 互斥；error 为降级留痕）。 */
    private record NarrativeAttempt(Narrative narrative, String error, String promptVersion) {}

    /** 单周生成结果（Job 留痕与测试断言面）。 */
    public record GenerationOutcome(
            String weekStart,
            String status,
            boolean skipped,
            String reason,
            long totalNews,
            long totalEvents,
            boolean narrativeDegraded) {

        static GenerationOutcome skippedOf(String weekStart, String reason) {
            return new GenerationOutcome(weekStart, "SKIPPED", true, reason, 0, 0, false);
        }
    }

    /** 定时窗口报告。 */
    public record WindowReport(int generated, int skipped, String detail) {}

    /** T145：本服务仅服务场景 9（行业周报）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.INDUSTRY_WEEKLY);
    }

    /** T145：注册表读取实际注入清单（与渲染 ctx.put 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return WEEKLY_PLACEHOLDERS;
    }
}
