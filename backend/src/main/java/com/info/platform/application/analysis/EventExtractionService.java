package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.ImpactCacheState;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.ImportanceScorer;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.L2Status;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.feed.AiExclusion;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L2 事件提取服务（应用层，M15 T122，方案 §4.4）：重要性预筛（{@link ImportanceScorer} 纯函数——源权重/强弱触发词/标的加成） →
 * 配额截断（≤quotaRatio × 当日 L1 DONE 数；尾部 DEFERRED 如实统计；旧账优先）→ 批量 LLM 提取（briefType=6，10 条/批， {@code
 * cacheable=false} 绕缓存 + 对半拆批递归）→ {@code event_item} UPSERT + {@code news_analysis.l2_status} 推进。
 *
 * <p><b>失败处理</b>同 L1 形态（ADR-0046 裁决 2）：整体解析失败对半拆批至单条（单条终败 FAILED）；网络类失败 attempts++ 本 tick 放弃；{@code
 * affected_industries} 越界值丢弃该元素不整条失败。
 *
 * <p>T46（ADR-0022）：实现 {@link PlaceholderProvider} 自述 3 键（today/batchSize/items）——治理页对照区单一事实源。
 */
@Service
public class EventExtractionService implements PlaceholderProvider {

    private static final Logger log = LoggerFactory.getLogger(EventExtractionService.class);

    /** 统计日界（Asia/Shanghai——event_date 与当日配额窗口口径，V22 source_daily_stats 先例）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 本服务实际注入的占位符描述符（与 buildContext 的 ctx.put 同源同序维护，同源单测守护）。 */
    private static final List<PlaceholderDescriptor> EXTRACT_PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("today", "今日日期（Asia/Shanghai yyyy-MM-dd）"),
                    new PlaceholderDescriptor("batchSize", "本批待提取条目数"),
                    new PlaceholderDescriptor(
                            "items", "待提取条目 JSON 行（每条 {id,title,summary,main,candidates}）"));

    private final NewsAnalysisRepository repository;
    private final EventItemRepository eventRepository;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final SubjectMatcher subjectMatcher;
    private final AiExclusionResolver exclusionResolver;
    private final PipelineSettings settings;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ImpactChainService impactChainService;

    public EventExtractionService(
            NewsAnalysisRepository repository,
            EventItemRepository eventRepository,
            LlmGateway llmGateway,
            PromptTemplateService promptTemplateService,
            SubjectMatcher subjectMatcher,
            AiExclusionResolver exclusionResolver,
            PipelineSettings settings,
            Clock clock,
            ObjectMapper objectMapper,
            ImpactChainService impactChainService) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.subjectMatcher = subjectMatcher;
        this.exclusionResolver = exclusionResolver;
        this.settings = settings;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.impactChainService = impactChainService;
    }

    /**
     * L2 段入口（批窗口第三段）：候选重扫打分 → 配额截断 → 分批提取。
     *
     * <p>候选 = 当日 SKIP/SELECTED/FAILED（attempts 未满）存量重扫 + 24h 窗口内 DEFERRED 旧账（次日低峰先还，方案 §4.4）。
     *
     * @return 段级计数（tick 明细数据面）
     */
    public L2Report runL2Window() {
        Instant now = clock.instant();
        String todayStart = todayStartIso(now);
        List<Long> excludeSourceIds =
                exclusionResolver.excludedSourceIds(AiExclusion.L2); // L2 档源不产事件（照常归类，REQ 拍板五-1）
        List<NewsAnalysisRepository.L2Candidate> candidates =
                repository.findL2Candidates(
                        todayStart,
                        now.minus(java.time.Duration.ofHours(settings.l1BackfillHours()))
                                .toString(),
                        settings.maxRetriesPerDay(),
                        excludeSourceIds,
                        PipelineSettings.L2_TICK_CAP);
        if (candidates.isEmpty()) {
            return new L2Report(0, 0, 0, 0, 0);
        }
        Map<Long, Double> scores = scoreCandidates(candidates);
        repository.updateImportanceScores(scores);

        List<NewsAnalysisRepository.L2Candidate> hits = selectHits(candidates, scores);
        if (hits.isEmpty()) {
            return new L2Report(0, 0, 0, 0, 0);
        }
        Quota quota = currentQuota(todayStart);
        int selectCount = (int) Math.min(hits.size(), quota.remaining());
        List<NewsAnalysisRepository.L2Candidate> selected = hits.subList(0, selectCount);
        List<NewsAnalysisRepository.L2Candidate> deferred =
                hits.subList(selected.size(), hits.size());

        markStates(selected, deferred);
        BatchOutcome outcome = extractInBatches(selected);
        int deferredCount = countTodaysNew(deferred);
        return new L2Report(
                selected.size(),
                outcome.extracted(),
                outcome.noEvent(),
                outcome.failed(),
                deferredCount);
    }

    /**
     * 提取一批（≤ l2BatchSize；解析失败与部分无效内部拆批递归，同 L1 形态）。
     *
     * @param items 待提取条目
     * @return 本批结果计数（含递归子批聚合）
     */
    public BatchOutcome extractBatch(List<NewsAnalysisRepository.L2Candidate> items) {
        if (items == null || items.isEmpty()) {
            return new BatchOutcome(0, 0, 0);
        }
        Map<Long, NewsAnalysisRepository.L2Candidate> byId = new LinkedHashMap<>();
        for (NewsAnalysisRepository.L2Candidate item : items) {
            byId.put(item.newsId(), item);
        }
        LlmResponse response;
        String promptVersion;
        Map<Long, List<SubjectMatcher.MatchedSubject>> candidatesByNews = new HashMap<>();
        try {
            PromptTemplate template =
                    promptTemplateService.loadActiveTemplate(BriefType.L2_EXTRACT);
            promptVersion = template.getVersion();
            for (NewsAnalysisRepository.L2Candidate item : items) {
                candidatesByNews.put(
                        item.newsId(), subjectMatcher.match(item.title(), item.summary()));
            }
            List<ChatMessage> messages =
                    promptTemplateService.render(template, buildContext(items, candidatesByNews));
            LlmRequest request =
                    LlmRequest.pipeline(
                            messages,
                            BriefType.L2_EXTRACT.key(),
                            PipelineSettings.L2_TEMPERATURE,
                            PipelineSettings.L2_MAX_TOKENS);
            response = llmGateway.chat(request);
        } catch (LlmException | BusinessException e) {
            // 网络类失败：attempts++ 本 tick 放弃（下 tick 重扫窗口自然重试）
            List<Long> newsIds = new ArrayList<>(byId.keySet());
            repository.markL2Failed(newsIds);
            log.warn(
                    "L2 批量调用失败（本 tick 放弃 {} 条）: {}",
                    newsIds.size(),
                    String.valueOf(e.getMessage()));
            return new BatchOutcome(0, 0, newsIds.size());
        }
        return applyResponse(new ArrayList<>(items), candidatesByNews, response, promptVersion);
    }

    /** 解析对齐 + 落库 + 未覆盖余量处理（整体无进展 → 对半拆批递归；单条终败 → FAILED）。 */
    private BatchOutcome applyResponse(
            List<NewsAnalysisRepository.L2Candidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> candidatesByNews,
            LlmResponse response,
            String promptVersion) {
        ExtractionOutput output = parseOutput(response.content());
        Set<Long> covered = output.coveredIds();
        int extracted = 0;
        int noEvent = 0;
        List<NewsAnalysisRepository.L2Candidate> broken = new ArrayList<>();
        for (NewsAnalysisRepository.L2Candidate item : items) {
            if (output.skippedIds().contains(item.newsId())) {
                repository.applyL2Result(
                        new NewsAnalysisRepository.L2Write(item.newsId(), L2Status.NO_EVENT));
                noEvent++;
                continue;
            }
            EventRow row = output.rowOf(item.newsId());
            if (row == null || !row.isValid()) {
                broken.add(item); // 缺 id / 重复 id / 非法枚举
                continue;
            }
            persist(item, row, candidatesByNews.get(item.newsId()), promptVersion);
            extracted++;
        }
        if (broken.isEmpty()) {
            return new BatchOutcome(extracted, noEvent, 0);
        }
        if (broken.size() == items.size()) {
            // 无进展：单条终败记 FAILED；多条对半拆批递归（ADR-0046 裁决 2）
            if (items.size() == 1) {
                repository.markL2Failed(List.of(items.get(0).newsId()));
                log.warn("L2 单条提取终败（attempts+1）: newsId={}", items.get(0).newsId());
                return new BatchOutcome(extracted, noEvent, 1);
            }
            int mid = broken.size() / 2;
            return extractBatch(broken.subList(0, mid))
                    .plus(extractBatch(broken.subList(mid, broken.size())));
        }
        // 部分进展：余量重试（有效行已落库）
        log.info("L2 批量部分无效（{} 条进重试批）", broken.size());
        return new BatchOutcome(extracted, noEvent, 0).plus(extractBatch(broken));
    }

    /** 事件落库：event_item UPSERT + l2_status → EXTRACTED（行业越界元素丢弃、eventTime 回退 published_at）。 */
    private void persist(
            NewsAnalysisRepository.L2Candidate item,
            EventRow row,
            List<SubjectMatcher.MatchedSubject> matched,
            String promptVersion) {
        List<String> industries = new ArrayList<>();
        for (String industry : row.industries()) {
            if (IndustryCategory.isSwIndustry(industry)) {
                industries.add(industry);
            } else {
                log.debug("L2 affected 越界元素丢弃: newsId={} industry={}", item.newsId(), industry);
            }
        }
        Instant eventTime = row.eventTime() != null ? row.eventTime() : item.publishedAt();
        EventItem saved =
                eventRepository.upsert(
                        EventItem.create(
                                item.newsId(),
                                row.type(),
                                row.summary(),
                                industries,
                                row.direction(),
                                row.importance(),
                                mergeFigures(row.figures()),
                                mergeSubjects(row.subjects(), matched),
                                row.quote(),
                                eventTime,
                                LocalDate.ofInstant(eventTime, STAT_ZONE).toString(),
                                promptVersion));
        repository.applyL2Result(
                new NewsAnalysisRepository.L2Write(item.newsId(), L2Status.EXTRACTED));
        autoGenerateImpactChain(saved);
    }

    /**
     * HIGH 事件落库自动生成影响链（M17 T144，REQ 拍板五-5「挂 L2 落库后」裁量）：纯规则渲染不阻塞 tick； 生成失败段式容错不回退 L2
     * 落库（影响链可用性优先级低于事件本身）。
     */
    private void autoGenerateImpactChain(EventItem saved) {
        if (saved == null || saved.getImportance() != Importance.HIGH) {
            return;
        }
        try {
            impactChainService.generateFor(saved, ImpactCacheState.AUTO);
        } catch (RuntimeException e) {
            log.warn(
                    "HIGH 事件影响链自动生成失败（不阻断 L2，查询侧自愈兜底）: newsId={} {}",
                    saved.getNewsId(),
                    e.toString());
        }
    }

    /** 模型 subjects 与池回联并集（回联命中优先——code 非 null 可跳标的详情；去重 by code+name）。 */
    private static List<EventItem.SubjectRef> mergeSubjects(
            List<EventItem.SubjectRef> modelSubjects, List<SubjectMatcher.MatchedSubject> matched) {
        Map<String, EventItem.SubjectRef> merged = new LinkedHashMap<>();
        if (matched != null) {
            for (SubjectMatcher.MatchedSubject subject : matched) {
                merged.put(
                        subject.code() + "|" + subject.name(),
                        new EventItem.SubjectRef(
                                subject.code(), subject.name(), subject.industry()));
            }
        }
        for (EventItem.SubjectRef subject : modelSubjects) {
            merged.putIfAbsent(
                    subject.code() + "|" + subject.name(), subject); // 池外公司保模型输出（code 可空）
        }
        return List.copyOf(merged.values());
    }

    private static List<EventItem.KeyFigure> mergeFigures(List<EventItem.KeyFigure> figures) {
        return figures == null ? List.of() : figures;
    }

    /** 渲染上下文（today + batchSize + items JSON 行；与 {@link #provided()} 同源同序）。 */
    private Map<String, String> buildContext(
            List<NewsAnalysisRepository.L2Candidate> items,
            Map<Long, List<SubjectMatcher.MatchedSubject>> candidatesByNews) {
        StringBuilder lines = new StringBuilder();
        for (NewsAnalysisRepository.L2Candidate item : items) {
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append(renderItemLine(item, candidatesByNews.get(item.newsId())));
        }
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("today", LocalDate.ofInstant(clock.instant(), STAT_ZONE).toString());
        ctx.put("batchSize", String.valueOf(items.size()));
        ctx.put("items", lines.toString());
        return ctx;
    }

    /**
     * 单条 items
     * 行：{"id":..,"title":..,"summary":..,"main":归类主分类,"candidates":[{code,name,industry}]}。
     */
    private String renderItemLine(
            NewsAnalysisRepository.L2Candidate item, List<SubjectMatcher.MatchedSubject> matched) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", item.newsId());
        line.put("title", item.title());
        line.put("summary", item.summary());
        line.put("main", item.mainCategory());
        List<Map<String, String>> candidates = new ArrayList<>();
        if (matched != null) {
            for (SubjectMatcher.MatchedSubject subject : matched) {
                Map<String, String> candidate = new LinkedHashMap<>();
                candidate.put("code", subject.code());
                candidate.put("name", subject.name());
                candidate.put("industry", subject.industry());
                candidates.add(candidate);
            }
        }
        line.put("candidates", candidates);
        return toJson(line);
    }

    /** 解析模型输出：{@code {"events":[..],"skipped":[id..]}}；围栏/裸 events 容错；不可解析返回空输出（整体拆批路径）。 */
    private ExtractionOutput parseOutput(String content) {
        if (content == null || content.isBlank()) {
            return ExtractionOutput.empty();
        }
        String stripped = stripCodeFence(content).trim();
        JsonNode root;
        try {
            root = objectMapper.readTree(stripped);
        } catch (Exception e) {
            return ExtractionOutput.empty();
        }
        JsonNode events = root.isArray() ? root : root.get("events");
        JsonNode skipped = root.get("skipped");
        if (events == null || !events.isArray()) {
            return ExtractionOutput.empty();
        }
        Set<Long> skippedIds = new java.util.HashSet<>();
        if (skipped != null && skipped.isArray()) {
            for (JsonNode id : skipped) {
                if (id != null && id.canConvertToLong()) {
                    skippedIds.add(id.asLong());
                }
            }
        }
        Map<Long, List<EventRow>> rowsById = new HashMap<>();
        for (JsonNode element : events) {
            EventRow row = EventRow.of(element);
            if (row != null) {
                rowsById.computeIfAbsent(row.id(), key -> new ArrayList<>()).add(row);
            }
        }
        return new ExtractionOutput(rowsById, skippedIds);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("L2 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private String todayStartIso(Instant now) {
        return LocalDate.ofInstant(now, STAT_ZONE).atStartOfDay(STAT_ZONE).toInstant().toString();
    }

    /** 打分（标题/摘要/源类别/池回联命中）。 */
    private Map<Long, Double> scoreCandidates(List<NewsAnalysisRepository.L2Candidate> candidates) {
        ImportanceScorer.ScorerParams params = settings.l2ScorerParams();
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (NewsAnalysisRepository.L2Candidate candidate : candidates) {
            boolean subjectMatched =
                    !subjectMatcher.match(candidate.title(), candidate.summary()).isEmpty();
            scores.put(
                    candidate.newsId(),
                    ImportanceScorer.score(
                            candidate.title(),
                            candidate.summary(),
                            candidate.sourceCategory(),
                            subjectMatched,
                            params));
        }
        return scores;
    }

    /** 命中阈值者排序：旧账（DEFERRED）优先 → 分降序 → id 升序（方案 §4.4 配额语义）。 */
    private List<NewsAnalysisRepository.L2Candidate> selectHits(
            List<NewsAnalysisRepository.L2Candidate> candidates, Map<Long, Double> scores) {
        ImportanceScorer.ScorerParams params = settings.l2ScorerParams();
        List<NewsAnalysisRepository.L2Candidate> hits = new ArrayList<>();
        for (NewsAnalysisRepository.L2Candidate candidate : candidates) {
            if (ImportanceScorer.hitsThreshold(scores.get(candidate.newsId()), params)) {
                hits.add(candidate);
            }
        }
        hits.sort(
                Comparator.comparing(
                                (NewsAnalysisRepository.L2Candidate c) ->
                                        c.currentL2() == L2Status.DEFERRED ? 0 : 1)
                        .thenComparing(c -> scores.get(c.newsId()), Comparator.reverseOrder())
                        .thenComparing(NewsAnalysisRepository.L2Candidate::newsId));
        return hits;
    }

    private Quota currentQuota(String todayStartIso) {
        long quota =
                (long)
                        Math.floor(
                                settings.l2QuotaRatio()
                                        * repository.countL1DoneSince(todayStartIso));
        long consumed = repository.countL2ProcessedSince(todayStartIso);
        return new Quota(quota, Math.max(0, quota - consumed));
    }

    private void markStates(
            List<NewsAnalysisRepository.L2Candidate> selected,
            List<NewsAnalysisRepository.L2Candidate> deferred) {
        if (!selected.isEmpty()) {
            List<Long> selectedIds =
                    selected.stream().map(NewsAnalysisRepository.L2Candidate::newsId).toList();
            repository.markL2Selected(selectedIds);
        }
        List<Long> todaysDeferred = new ArrayList<>();
        for (NewsAnalysisRepository.L2Candidate candidate : deferred) {
            if (candidate.currentL2() != L2Status.DEFERRED) {
                todaysDeferred.add(candidate.newsId()); // 旧账未入选保持 DEFERRED 不重复改写
            }
        }
        if (!todaysDeferred.isEmpty()) {
            repository.markL2Deferred(todaysDeferred);
            log.info("L2 配额截断（DEFERRED 如实统计）: deferred={} 条", todaysDeferred.size());
        }
    }

    private BatchOutcome extractInBatches(List<NewsAnalysisRepository.L2Candidate> selected) {
        int batchSize = settings.l2BatchSize();
        BatchOutcome total = new BatchOutcome(0, 0, 0);
        for (int from = 0; from < selected.size(); from += batchSize) {
            total =
                    total.plus(
                            extractBatch(
                                    selected.subList(
                                            from, Math.min(from + batchSize, selected.size()))));
        }
        return total;
    }

    private static int countTodaysNew(List<NewsAnalysisRepository.L2Candidate> deferred) {
        int count = 0;
        for (NewsAnalysisRepository.L2Candidate candidate : deferred) {
            if (candidate.currentL2() != L2Status.DEFERRED) {
                count++;
            }
        }
        return count;
    }

    /** L2 段计数（JobRunStats 段式明细数据面）。 */
    public record L2Report(int selected, int extracted, int noEvent, int failed, int deferred) {

        /** 段式明细串（方案 §4.7：l2=extracted:n; no_event:n; failed:n; deferred:n）。 */
        public String detail() {
            return "l2=extracted:"
                    + extracted
                    + "; no_event:"
                    + noEvent
                    + "; failed:"
                    + failed
                    + "; deferred:"
                    + deferred;
        }
    }

    /** 批结果计数。 */
    public record BatchOutcome(int extracted, int noEvent, int failed) {

        public BatchOutcome plus(BatchOutcome other) {
            return new BatchOutcome(
                    extracted + other.extracted(),
                    noEvent + other.noEvent(),
                    failed + other.failed());
        }
    }

    /** 配额视图（当日总额度与剩余额度）。 */
    private record Quota(long total, long remaining) {}

    /** 解析中间态：id → 事件行（重复 id = 矛盾输出不可信）+ skipped 集合。 */
    private record ExtractionOutput(Map<Long, List<EventRow>> rowsById, Set<Long> skippedIds) {

        static ExtractionOutput empty() {
            return new ExtractionOutput(Map.of(), Set.of());
        }

        Set<Long> coveredIds() {
            Set<Long> covered = new java.util.HashSet<>(skippedIds);
            covered.addAll(rowsById.keySet());
            return covered;
        }

        EventRow rowOf(long id) {
            List<EventRow> rows = rowsById.get(id);
            return rows != null && rows.size() == 1 ? rows.get(0) : null;
        }
    }

    /** 模型事件行（白名单校验在 {@link #isValid}——type/direction/importance 任一非法进重试批）。 */
    private record EventRow(
            long id,
            EventType type,
            String summary,
            List<String> industries,
            Direction direction,
            Importance importance,
            List<EventItem.KeyFigure> figures,
            List<EventItem.SubjectRef> subjects,
            String quote,
            Instant eventTime) {

        static EventRow of(JsonNode node) {
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode id = node.get("id");
            JsonNode type = node.get("type");
            JsonNode summary = node.get("summary");
            EventType eventType = EventType.fromName(textOrNull(type));
            Direction direction = Direction.fromName(textOrNull(node.get("direction")));
            Importance importance = Importance.fromName(textOrNull(node.get("importance")));
            if (id == null
                    || !id.canConvertToLong()
                    || eventType == null
                    || direction == null
                    || importance == null
                    || summary == null
                    || !summary.isTextual()) {
                return null;
            }
            return new EventRow(
                    id.asLong(),
                    eventType,
                    summary.asText(),
                    stringListOf(node.get("industries")),
                    direction,
                    importance,
                    figureListOf(node.get("figures")),
                    subjectListOf(node.get("subjects")),
                    textOrNull(node.get("quote")),
                    instantOrNull(node.get("eventTime")));
        }

        boolean isValid() {
            return type != null && direction != null && importance != null && summary != null;
        }

        private static List<String> stringListOf(JsonNode array) {
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode element : array) {
                if (element != null && element.isTextual()) {
                    values.add(element.asText());
                }
            }
            return values;
        }

        private static List<EventItem.KeyFigure> figureListOf(JsonNode array) {
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<EventItem.KeyFigure> figures = new ArrayList<>();
            for (JsonNode element : array) {
                if (element == null || !element.isObject()) {
                    continue;
                }
                figures.add(
                        new EventItem.KeyFigure(
                                textOrNull(element.get("label")),
                                textOrNull(element.get("value")),
                                textOrNull(element.get("unit"))));
            }
            return figures;
        }

        private static List<EventItem.SubjectRef> subjectListOf(JsonNode array) {
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<EventItem.SubjectRef> subjects = new ArrayList<>();
            for (JsonNode element : array) {
                if (element == null || !element.isObject()) {
                    continue;
                }
                String name = textOrNull(element.get("name"));
                if (name == null || name.isBlank()) {
                    continue; // 无名主体不可回溯，丢弃
                }
                subjects.add(
                        new EventItem.SubjectRef(
                                textOrNull(element.get("code")),
                                name,
                                textOrNull(element.get("industry"))));
            }
            return subjects;
        }

        private static Instant instantOrNull(JsonNode node) {
            String text = textOrNull(node);
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(text);
            } catch (Exception e) {
                return null; // 模型给出非 ISO 时间 → 回退 published_at（不整条失败）
            }
        }

        private static String textOrNull(JsonNode node) {
            return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
        }
    }

    /** T46：本服务仅服务场景 6（事件提取）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.L2_EXTRACT);
    }

    /** T46：注册表读取实际注入清单（与 {@code buildContext} 的 ctx.put 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return EXTRACT_PLACEHOLDERS;
    }
}
