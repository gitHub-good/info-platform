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
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.push.IndustryReportReadyEvent;
import java.time.Clock;
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
 * 行业日报生成服务（应用层，M15 T124，方案 §4.5）：前一日（Asia/Shanghai 日界）L1 聚合统计 + L2 事件精选 + 热度快照 → 统计注入模板（AI
 * 只写叙述，数字全部来自统计 SQL——幻觉防线）→ 单次 LLM 调用（brief_type=7，scene="7"，管道工厂绕缓存）→ 结构化日报落 {@code
 * industry_daily_report}（UNIQUE(report_date) 幂等 / FAILED 重生成替换）。
 *
 * <p><b>护栏语义</b>（裁决 5 / 方案 §4.6）：FUSED 跳过（留痕，定时补跑窗口 [前日, 昨日] 次日覆盖）；DEGRADED 保留（日报存活）； 生成前后各查一次
 * level——途中越线不回滚（单次 ¥0.01 量级，无撕裂风险）。 <b>降级语义</b>：LLM 失败/输出不可解析 → 纯统计版
 * SUCCESS（narrativeDegraded=true 如实标注 + error_message 留痕 + 不发完成通知——当日可用性优先，不误导晨读）。 <b>幂等语义</b>： 已
 * SUCCESS 直返跳过（手动重生成仅 FAILED，30077 契约由端点把守）。
 */
@Service
public class DailyReportService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    /** 统计日界（Asia/Shanghai——与护栏/热度/统计口径同源，方案 §8 日界风险条目）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 事件精选上限（任务契约：重要度高者优先 ≤10 条）。 */
    static final int TOP_EVENT_LIMIT = 10;

    /** topIndustries 上限（方案 §4.5：Top5 行业）。 */
    static final int TOP_INDUSTRY_LIMIT = 5;

    /** 事件全量取数上限（防御性；正常水位 ~130/日）。 */
    static final int EVENT_FETCH_CAP = 500;

    /** 免责声明固定文案（方案 §4.5——AI 产出永久红线，不采信模型输出值）。 */
    static final String DISCLAIMER = "AI 分析仅供参考";

    /** 空数据日固定叙述（零资讯零事件日不调 LLM——无从叙述且省成本）。 */
    static final String EMPTY_DAY_SUMMARY = "昨日无新增已归类资讯与事件（数据冷清日），热度榜无新增变化。";

    /** 降级日固定叙述（纯统计版如实标注）。 */
    static final String DEGRADED_SUMMARY = "本日报为纯统计版：AI 叙述生成暂不可用，以下数字与事件精选全部来自统计 SQL。";

    /** 叙述/点评/错误截断上限（防御线——模型超长输出不撑爆列）。 */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    private static final int COMMENTARY_MAX_LENGTH = 500;

    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** 本服务实际注入的占位符描述符（与 {@code buildContext} 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> REPORT_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("reportDate", "报告覆盖日（Asia/Shanghai yyyy-MM-dd）"),
                    new PlaceholderDescriptor("industryStats", "昨日各行业资讯/事件计数行（统计 SQL 产出，含容器）"),
                    new PlaceholderDescriptor("topEvents", "代表事件行（重要度降序 ≤10 条，含类型/方向/行业/摘要/原文引用）"));

    private final DailyReportRepository reportRepository;
    private final HeatSnapshotRepository heatSnapshotRepository;
    private final PipelineGuardService guardService;
    private final PipelineSettings settings;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DailyReportService(
            DailyReportRepository reportRepository,
            HeatSnapshotRepository heatSnapshotRepository,
            PipelineGuardService guardService,
            PipelineSettings settings,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.reportRepository = reportRepository;
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
     * 单日生成（核心，定时与手动重试共用）：FUSED 跳过留痕；已 SUCCESS 幂等直返；统计 → 单次 LLM → UPSERT。
     *
     * @param reportDate 覆盖日（Asia/Shanghai yyyy-MM-dd）
     */
    public GenerationOutcome generateFor(String reportDate) {
        LocalDate date = parseReportDate(reportDate);
        if (guardService.currentLevel() == GuardLevel.FUSED) {
            log.warn("行业日报跳过（管道 FUSED，次日定时窗口补）: date={}", reportDate);
            return GenerationOutcome.skippedOf(reportDate, "skipped(fused)");
        }
        IndustryDailyReport existing = reportRepository.findByReportDate(reportDate).orElse(null);
        if (existing != null && existing.getStatus() == ReportStatus.SUCCESS) {
            return GenerationOutcome.skippedOf(reportDate, "already-success");
        }
        try {
            return generate(date);
        } catch (Exception e) {
            // 统计/落库阶段失败 → FAILED 行留痕（retry 端点可重生成；FAILED 行落库自身失败则上抛交 JobExecutor 留痕）
            log.error("行业日报生成失败: date={} {}", reportDate, e.toString(), e);
            persistFailure(reportDate, e.toString());
            return new GenerationOutcome(reportDate, "FAILED", false, e.toString(), 0, 0, false);
        }
    }

    /**
     * 定时入口：补跑窗口 [前日, 昨日] 逐日生成（oldest first——FUSED 次日补语义；SUCCESS 跳过；每轮恒定 ≤2 次生成）。
     *
     * @return 窗口报告（JobRunStats 留痕数据面）
     */
    public WindowReport runScheduledWindow() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        List<LocalDate> window = List.of(today.minusDays(2), today.minusDays(1));
        int generated = 0;
        int skipped = 0;
        StringBuilder detail = new StringBuilder();
        for (LocalDate date : window) {
            GenerationOutcome outcome = generateFor(date.toString());
            if (outcome.skipped()) {
                skipped++;
                detail.append(date).append("=skip(").append(outcome.reason()).append("); ");
            } else {
                generated++;
                detail.append(date)
                        .append("=")
                        .append(outcome.status())
                        .append(outcome.narrativeDegraded() ? "(degraded)" : "")
                        .append("; ");
            }
        }
        log.info("行业日报定时窗口完成: {}", detail);
        return new WindowReport(generated, skipped, detail.toString().trim());
    }

    // —— 生成主链 ——

    private GenerationOutcome generate(LocalDate date) {
        String reportDate = date.toString();
        Instant dayStart = date.atStartOfDay(STAT_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(STAT_ZONE).toInstant();

        List<DailyReportRepository.IndustryCount> newsCounts =
                reportRepository.countNewsByIndustry(dayStart.toString(), dayEnd.toString());
        List<DailyReportRepository.ReportEvent> events =
                reportRepository.findEventsByDate(reportDate, EVENT_FETCH_CAP);
        List<DailyReportRepository.ReportEvent> topEvents =
                events.stream().limit(TOP_EVENT_LIMIT).toList();
        List<IndustryHeatSnapshot> board = heatSnapshotRepository.findBoard(HeatWindow.H24);

        Map<String, Long> newsByIndustry = new LinkedHashMap<>();
        Map<String, Long> containerCounts = new LinkedHashMap<>();
        long totalNews = 0;
        for (DailyReportRepository.IndustryCount count : newsCounts) {
            totalNews += count.newsCount();
            if (IndustryCategory.isSwIndustry(count.category())) {
                newsByIndustry.put(count.category(), count.newsCount());
            } else {
                containerCounts.put(count.category(), count.newsCount());
            }
        }
        Map<String, Long> eventCountByIndustry = eventCountByIndustry(events);

        Narrative narrative = null;
        String llmError = null;
        String promptVersion = null;
        boolean dataEmpty = totalNews == 0 && events.isEmpty();
        if (!dataEmpty) {
            NarrativeAttempt attempt =
                    requestNarrative(reportDate, newsCounts, eventCountByIndustry, topEvents);
            narrative = attempt.narrative();
            llmError = attempt.error();
            promptVersion = attempt.promptVersion();
        }

        // 生成后复查护栏（方案 §4.6：途中越线不回滚，仅留痕）
        GuardLevel levelAfter = guardService.currentLevel();

        String contentJson =
                assembleContent(
                        newsByIndustry,
                        containerCounts,
                        totalNews,
                        events.size(),
                        topEvents,
                        eventCountByIndustry,
                        board,
                        narrative,
                        dataEmpty);
        reportRepository.upsert(
                IndustryDailyReport.success(
                        reportDate,
                        contentJson,
                        heatTopJson(board),
                        llmError,
                        promptVersion,
                        basisOf(board),
                        clock.instant()));
        if (narrative != null) {
            eventPublisher.publishEvent(
                    new IndustryReportReadyEvent(reportDate, false, clock.instant()));
        }
        log.info(
                "行业日报生成完成: date={} status=SUCCESS news={} events={} narrative={} levelAfter={}",
                reportDate,
                totalNews,
                events.size(),
                narrative != null ? "ok" : "degraded",
                levelAfter);
        return new GenerationOutcome(
                reportDate,
                "SUCCESS",
                false,
                null,
                totalNews,
                events.size(),
                narrative == null && !dataEmpty);
    }

    /** 单次 LLM 叙述请求（scene=7，管道工厂；失败/不可解析 → 纯统计版降级，不抛出）。 */
    private NarrativeAttempt requestNarrative(
            String reportDate,
            List<DailyReportRepository.IndustryCount> newsCounts,
            Map<String, Long> eventCountByIndustry,
            List<DailyReportRepository.ReportEvent> topEvents) {
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_DAILY);
            List<ChatMessage> messages =
                    promptTemplateService.render(
                            template,
                            buildContext(reportDate, newsCounts, eventCountByIndustry, topEvents));
            LlmRequest request =
                    LlmRequest.pipeline(
                            messages,
                            BriefType.INDUSTRY_DAILY.key(),
                            PipelineSettings.L1_TEMPERATURE,
                            PipelineSettings.L1_MAX_TOKENS);
            LlmResponse response = llmGateway.chat(request);
            Narrative narrative = parseNarrative(response.content());
            if (narrative == null) {
                log.warn("行业日报叙述输出不可解析（降级纯统计版）: date={}", reportDate);
                return new NarrativeAttempt(null, "叙述输出不可解析（降级纯统计版）", template.getVersion());
            }
            return new NarrativeAttempt(narrative, null, template.getVersion());
        } catch (LlmException e) {
            log.warn("行业日报 LLM 调用失败（降级纯统计版）: date={} {}", reportDate, e.getMessage());
            return new NarrativeAttempt(null, String.valueOf(e.getMessage()), null);
        } catch (BusinessException e) {
            // 模板缺失/配置类失败：叙述段降级（error 留痕），统计段照常产出
            log.warn("行业日报叙述前置失败（降级纯统计版）: date={} {}", reportDate, e.getMessage());
            return new NarrativeAttempt(null, String.valueOf(e.getMessage()), null);
        } catch (RuntimeException e) {
            // 网关运行期异常（超时/序列化等）：叙述段降级不放大——日报当日可用性优先（方案 §4.5 降级语义）
            log.warn("行业日报 LLM 调用异常（降级纯统计版）: date={} {}", reportDate, e.toString());
            return new NarrativeAttempt(null, e.toString(), null);
        }
    }

    /** 渲染上下文（reportDate + industryStats + topEvents；与 {@link #provided()} 同源同序）。 */
    private Map<String, String> buildContext(
            String reportDate,
            List<DailyReportRepository.IndustryCount> newsCounts,
            Map<String, Long> eventCountByIndustry,
            List<DailyReportRepository.ReportEvent> topEvents) {
        StringBuilder stats = new StringBuilder();
        for (DailyReportRepository.IndustryCount count : newsCounts) {
            if (!stats.isEmpty()) {
                stats.append('；');
            }
            stats.append(count.category())
                    .append("=资讯")
                    .append(count.newsCount())
                    .append("/事件")
                    .append(eventCountByIndustry.getOrDefault(count.category(), 0L));
        }
        if (stats.isEmpty()) {
            stats.append("（昨日无已归类资讯）");
        }
        StringBuilder eventLines = new StringBuilder();
        int index = 1;
        for (DailyReportRepository.ReportEvent event : topEvents) {
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
            if (event.quote() != null && !event.quote().isBlank()) {
                eventLines.append(" | 引用：").append(event.quote());
            }
        }
        if (eventLines.isEmpty()) {
            eventLines.append("（昨日无结构化事件）");
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("reportDate", reportDate);
        ctx.put("industryStats", stats.toString());
        ctx.put("topEvents", eventLines.toString());
        return ctx;
    }

    /**
     * 模型叙述解析：对象 schema {summary, topIndustries[{industry,commentary}], watchPoints[]}；不可解析返回
     * null（降级）。
     */
    private Narrative parseNarrative(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String stripped = stripCodeFence(content.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(stripped);
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
        Map<String, String> commentaries = new HashMap<>();
        JsonNode tops = root.get("topIndustries");
        if (tops != null && tops.isArray()) {
            for (JsonNode top : tops) {
                String industry = textOf(top.get("industry"));
                String commentary = textOf(top.get("commentary"));
                if (industry != null && commentary != null && IndustryCategory.isValid(industry)) {
                    commentaries.put(industry, commentary);
                }
            }
        }
        List<String> watchPoints = new ArrayList<>();
        JsonNode watches = root.get("watchPoints");
        if (watches != null && watches.isArray()) {
            for (JsonNode watch : watches) {
                String point = textOf(watch);
                if (point != null) {
                    watchPoints.add(point);
                }
            }
        }
        return new Narrative(summary, commentaries, watchPoints);
    }

    /** content JSON 组装（§4.5 步骤 3：统计数字 + AI 叙述合并；disclaimer 固定不采信模型值）。 */
    private String assembleContent(
            Map<String, Long> newsByIndustry,
            Map<String, Long> containerCounts,
            long totalNews,
            long totalEvents,
            List<DailyReportRepository.ReportEvent> topEvents,
            Map<String, Long> eventCountByIndustry,
            List<IndustryHeatSnapshot> board,
            Narrative narrative,
            boolean dataEmpty) {
        ObjectNode content = objectMapper.createObjectNode();
        boolean degraded = narrative == null && !dataEmpty;
        content.put(
                "summary",
                truncate(
                        narrative != null
                                ? narrative.summary()
                                : dataEmpty ? EMPTY_DAY_SUMMARY : DEGRADED_SUMMARY,
                        SUMMARY_MAX_LENGTH));
        content.put("narrativeDegraded", degraded);
        ArrayNode watchPoints = content.putArray("watchPoints");
        if (narrative != null) {
            narrative.watchPoints().forEach(watchPoints::add);
        }
        ObjectNode industryCounts = content.putObject("industryCounts");
        newsByIndustry.forEach(industryCounts::put);
        ObjectNode containers = content.putObject("containerCounts");
        containerCounts.forEach(containers::put);
        content.put("totalNews", totalNews);
        content.put("totalEvents", totalEvents);

        ArrayNode tops = content.putArray("topIndustries");
        for (String industry : topIndustries(newsByIndustry, board)) {
            ObjectNode top = tops.addObject();
            top.put("industry", industry);
            top.put("newsCount", newsByIndustry.getOrDefault(industry, 0L));
            top.put("eventCount", eventCountByIndustry.getOrDefault(industry, 0L));
            IndustryHeatSnapshot snapshot = snapshotOf(board, industry);
            top.put("heatScore", snapshot == null ? 0.0 : snapshot.getHeatScore());
            top.put("deltaPct", snapshot == null ? 0.0 : snapshot.getDeltaPct());
            String commentary = narrative == null ? null : narrative.commentaries().get(industry);
            top.put(
                    "commentary",
                    commentary == null
                            ? (narrative == null ? "" : "（模型未给出该行业点评）")
                            : truncate(commentary, COMMENTARY_MAX_LENGTH));
            ArrayNode refEventIds = top.putArray("refEventIds");
            topEvents.stream()
                    .filter(event -> event.industries().contains(industry))
                    .forEach(event -> refEventIds.add(event.eventId()));
        }

        ArrayNode events = content.putArray("events");
        for (DailyReportRepository.ReportEvent event : topEvents) {
            ObjectNode node = events.addObject();
            node.put("eventId", event.eventId());
            node.put("newsId", event.newsId());
            node.put("eventType", event.eventType().name());
            node.put("summary", event.summary());
            ArrayNode industries = node.putArray("industries");
            event.industries().forEach(industries::add);
            node.put("direction", event.direction().name());
            node.put("importance", event.importance().name());
            node.put("quote", event.quote());
            node.set("figures", figuresOf(event.figuresJson()));
            node.put("eventTime", event.eventTime() == null ? null : event.eventTime().toString());
            // T163 trace-v1：来源名 + 原文外链（新报告起升 A 级；历史 content 无此两字段由前端判空降级）
            node.put("sourceName", event.sourceName());
            node.put("newsUrl", event.newsUrl());
        }
        content.put("disclaimer", DISCLAIMER);
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalStateException("日报 content 序列化失败: " + e.getMessage(), e);
        }
    }

    /** Top 行业序（快照 H24 热度降序；快照缺失回落昨日资讯计数 Top——日报不因快照缺位空转）。 */
    private List<String> topIndustries(
            Map<String, Long> newsByIndustry, List<IndustryHeatSnapshot> board) {
        if (!board.isEmpty()) {
            return board.stream()
                    .map(IndustryHeatSnapshot::getIndustry)
                    .limit(TOP_INDUSTRY_LIMIT)
                    .toList();
        }
        return newsByIndustry.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Long>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_INDUSTRY_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 各行业事件计数（affected ∋ 行业，申万白名单口径——容器不收事件）。 */
    private Map<String, Long> eventCountByIndustry(List<DailyReportRepository.ReportEvent> events) {
        Map<String, Long> counts = new HashMap<>();
        for (DailyReportRepository.ReportEvent event : events) {
            for (String industry : event.industries()) {
                if (IndustryCategory.isSwIndustry(industry)) {
                    counts.merge(industry, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /** heat_top 留存 JSON（生成时点 H24 榜快照——对账与趋势，不随快照滚动丢失）。 */
    private String heatTopJson(List<IndustryHeatSnapshot> board) {
        ArrayNode rows = objectMapper.createArrayNode();
        for (IndustryHeatSnapshot snapshot : board) {
            ObjectNode row = rows.addObject();
            row.put("industry", snapshot.getIndustry());
            row.put("heatScore", snapshot.getHeatScore());
            row.put("prevScore", snapshot.getPrevScore());
            row.put("deltaPct", snapshot.getDeltaPct());
            row.put("newsCount", snapshot.getNewsCount());
            row.put("eventCount", snapshot.getEventCount());
            row.put("snapshotAt", snapshot.getSnapshotAt().toString());
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("heat_top 序列化失败（落空数组）: {}", e.getMessage());
            return "[]";
        }
    }

    /** basis = heat + cost 双口径串（DDL 注释契约，对账脚注直读）。 */
    private String basisOf(List<IndustryHeatSnapshot> board) {
        String heatBasis =
                board.isEmpty()
                        ? "heat-v1:none"
                        : board.get(0).getBasis() + ";window=H24@" + board.get(0).getSnapshotAt();
        return heatBasis + " | " + settings.costBasis();
    }

    private static IndustryHeatSnapshot snapshotOf(
            List<IndustryHeatSnapshot> board, String industry) {
        return board.stream()
                .filter(row -> row.getIndustry().equals(industry))
                .findFirst()
                .orElse(null);
    }

    private JsonNode figuresOf(String figuresJson) {
        if (figuresJson == null || figuresJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(figuresJson);
            return parsed.isArray() ? parsed : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private void persistFailure(String reportDate, String error) {
        try {
            reportRepository.upsert(
                    IndustryDailyReport.failed(
                            reportDate,
                            truncate(error, ERROR_MESSAGE_MAX_LENGTH),
                            clock.instant()));
        } catch (Exception persistError) {
            log.error("行业日报 FAILED 行落库失败（交 JobExecutor FAILED 留痕）: {}", persistError.toString());
        }
    }

    private static LocalDate parseReportDate(String reportDate) {
        try {
            return LocalDate.parse(reportDate);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("reportDate 须为 yyyy-MM-dd: " + reportDate);
        }
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

    /** 模型叙述（解析产物；commentaries 按行业名对齐）。 */
    private record Narrative(
            String summary, Map<String, String> commentaries, List<String> watchPoints) {}

    /** 叙述请求结果（narrative 与 error 互斥；error 为降级留痕）。 */
    private record NarrativeAttempt(Narrative narrative, String error, String promptVersion) {}

    /** 单日生成结果（Job 留痕与测试断言面）。 */
    public record GenerationOutcome(
            String reportDate,
            String status,
            boolean skipped,
            String reason,
            long totalNews,
            long totalEvents,
            boolean narrativeDegraded) {

        static GenerationOutcome skippedOf(String reportDate, String reason) {
            return new GenerationOutcome(reportDate, "SKIPPED", true, reason, 0, 0, false);
        }
    }

    /** 定时窗口报告。 */
    public record WindowReport(int generated, int skipped, String detail) {}

    /** T46：本服务仅服务场景 7（行业日报）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.INDUSTRY_DAILY);
    }

    /** T46：注册表读取实际注入清单（与渲染 ctx.put 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return REPORT_PLACEHOLDERS;
    }
}
