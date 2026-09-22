package com.info.platform.infrastructure.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link DataSourceConfigFacade} 实现（T36，方案 §4.4.2）。落在基础设施层的原因见端口 Javadoc。
 *
 * <p>读：runtime_config 快照经 {@link ConfigCenter}（键缺失回落代码缺省视图）；健康徽章数据读 {@code data_source_event}（最近一条含
 * OK 心跳 + 24h 异常计数）。 写：当前文档合并请求字段（PATCH 语义）→ {@link RuntimeConfigService#write}（校验 + 乐观防呆 + 换快照 +
 * 发事件）——保存即热生效（LIVE 级， 路由/弹性/缓存/总超时消费点用时读取）。 连通性测试：单源绕缓存试拉一次（mock 模式本地校验 + 提示；EVENT
 * 源本地表读取），不改变任何配置。
 */
@Component
public class DataSourceConfigFacadeImpl implements DataSourceConfigFacade {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfigFacadeImpl.class);

    /** 生效级别常量（LlmConfigView 先例：字符串契约，前端按 RESTART 渲染徽章）。 */
    private static final String EFFECTIVE_LIVE = "LIVE";

    static final String KEY_AGGREGATION_GLOBAL = "aggregation.global";

    private static final Map<String, String> SOURCE_EFFECTIVE_MODES =
            Map.of(
                    "enabled", EFFECTIVE_LIVE,
                    "mode", EFFECTIVE_LIVE,
                    "timeoutMillis", EFFECTIVE_LIVE,
                    "retries", EFFECTIVE_LIVE,
                    "cacheTtlSeconds", EFFECTIVE_LIVE,
                    "params", EFFECTIVE_LIVE);

    private static final Map<String, String> AGGREGATION_EFFECTIVE_MODES =
            Map.of("detailTimeoutMillis", EFFECTIVE_LIVE);

    /** 卡片标题（PRD 故事 3 的 7 源中文名；EVENT 本地只读卡标识）。 */
    private static final Map<SourceCode, String> SOURCE_LABELS =
            Map.of(
                    SourceCode.QUOTE, "行情源",
                    SourceCode.FINANCE, "财务源",
                    SourceCode.VALUATION, "估值源",
                    SourceCode.ANNOUNCE, "公告源",
                    SourceCode.NEWS, "新闻源",
                    SourceCode.POLICY, "政策源",
                    SourceCode.EVENT, "事件源");

    private final RuntimeConfigService configService;
    private final ConfigCenter configCenter;
    private final DataSourceEventRepository eventRepository;
    private final SubjectRepository subjectRepository;
    private final List<RoutingSourceAdapter> routingAdapters;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DataSourceConfigFacadeImpl(
            RuntimeConfigService configService,
            ConfigCenter configCenter,
            DataSourceEventRepository eventRepository,
            SubjectRepository subjectRepository,
            List<RoutingSourceAdapter> routingAdapters,
            Clock clock,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.configCenter = configCenter;
        this.eventRepository = eventRepository;
        this.subjectRepository = subjectRepository;
        this.routingAdapters = List.copyOf(routingAdapters);
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceConfigView view() {
        List<SourceCardView> sources = new ArrayList<>();
        for (SourceCode code : SourceCode.values()) {
            sources.add(cardOf(code));
        }
        return new DataSourceConfigView(List.copyOf(sources), aggregationView());
    }

    @Override
    public SourceCardView update(SourceCode sourceCode, DataSourceConfigUpdate update) {
        requireKnownSource(sourceCode);
        String key = ConfigCenter.KEY_DATASOURCE_PREFIX + sourceCode.name();
        ObjectNode merged = mutableDoc(key, sourceCode);
        if (update.enabled() != null) {
            merged.put("enabled", update.enabled());
        }
        if (update.mode() != null) {
            merged.put("mode", update.mode());
        }
        if (update.timeoutMillis() != null) {
            merged.put("timeoutMillis", update.timeoutMillis());
        }
        if (update.retries() != null) {
            merged.put("retries", update.retries());
        }
        if (update.cacheTtlSeconds() != null) {
            merged.put("cacheTtlSeconds", update.cacheTtlSeconds());
        }
        if (update.params() != null) {
            merged.set("params", objectMapper.valueToTree(update.params()));
        }
        RuntimeConfigEntry saved =
                configService.write(
                        key, merged.toString(), parseExpected(update.expectedUpdatedAt()));
        log.info("数据源配置已更新 sourceCode={}（键级审计，值不入日志）", sourceCode);
        return cardOf(sourceCode, saved);
    }

    @Override
    public AggregationView updateAggregation(AggregationGlobalUpdate update) {
        ObjectNode merged = mutableDoc(KEY_AGGREGATION_GLOBAL, null);
        if (update.detailTimeoutMillis() != null) {
            merged.put("detailTimeoutMillis", update.detailTimeoutMillis());
        }
        RuntimeConfigEntry saved =
                configService.write(
                        KEY_AGGREGATION_GLOBAL,
                        merged.toString(),
                        parseExpected(update.expectedUpdatedAt()));
        return new AggregationView(
                saved.document().path("detailTimeoutMillis").asLong(),
                saved.updatedAt().toString(),
                AGGREGATION_EFFECTIVE_MODES);
    }

    @Override
    public ConnectivityResult connectivityTest(SourceCode sourceCode) {
        requireKnownSource(sourceCode);
        RoutingSourceAdapter routing = routingOf(sourceCode);
        RuntimeDataSource config = configCenter.dataSource(sourceCode);
        AbstractSourceAdapter target =
                (AbstractSourceAdapter)
                        (config.mode() == RuntimeDataSource.Mode.MOCK
                                        && sourceCode != SourceCode.EVENT
                                ? routing.mockAdapter()
                                : routing.realAdapter());
        Subject probe = subjectRepository.findFirstActive().orElse(null);
        if (probe == null) {
            return new ConnectivityResult(
                    false, null, null, config.mode().name(), "无启用标的可供测试（请先录入标的）", null);
        }
        long startNanos = System.nanoTime();
        try {
            SourceResult result = target.fetchFresh(probe);
            long latency = elapsedMillis(startNanos);
            boolean ok = result.getStatus() == SourceStatus.OK;
            Long itemCount = ok ? itemCountOf(result) : null;
            String note = noteOf(sourceCode, config);
            return new ConnectivityResult(
                    ok,
                    latency,
                    itemCount,
                    config.mode().name(),
                    ok ? null : "抓取未返回有效数据（状态：" + result.getStatus().name() + "）",
                    note);
        } catch (Exception e) {
            long latency = elapsedMillis(startNanos);
            log.warn("连通性测试异常 sourceCode={}: {}", sourceCode, String.valueOf(e));
            return new ConnectivityResult(
                    false, latency, null, config.mode().name(), summarize(e), null);
        }
    }

    // —— 读视图 ——

    private SourceCardView cardOf(SourceCode code) {
        RuntimeConfigEntry entry =
                configService.read(ConfigCenter.KEY_DATASOURCE_PREFIX + code.name()).orElse(null);
        return cardOf(code, entry);
    }

    private SourceCardView cardOf(SourceCode code, RuntimeConfigEntry entry) {
        // 配置值经快照读取（键缺失时内部回落代码缺省）；entry 仅供 updatedAt 回显
        RuntimeDataSource config = configCenter.dataSource(code);
        Map<String, Object> params = new LinkedHashMap<>(config.params());
        return new SourceCardView(
                code.name(),
                SOURCE_LABELS.getOrDefault(code, code.name()),
                config.enabled(),
                config.mode().name(),
                config.timeoutMillis(),
                config.retries(),
                config.cacheTtlSeconds(),
                params,
                healthOf(code),
                entry == null ? null : entry.updatedAt().toString(),
                SOURCE_EFFECTIVE_MODES);
    }

    /** 健康徽章数据：最近一条事件（含 OK 心跳）+ 24h 异常计数；从未有事件两字段 null（空态）。 */
    private HealthView healthOf(SourceCode code) {
        Optional<DataSourceEvent> latest = eventRepository.findLatestBySourceCode(code);
        long errors24h =
                eventRepository.countErrorsSince(code, clock.instant().minus(Duration.ofHours(24)));
        return new HealthView(
                latest.map(e -> e.getEventType().name()).orElse(null),
                latest.map(e -> e.getCreatedAt() == null ? null : e.getCreatedAt().toString())
                        .orElse(null),
                errors24h);
    }

    private AggregationView aggregationView() {
        RuntimeConfigEntry entry = configService.read(KEY_AGGREGATION_GLOBAL).orElse(null);
        long timeout =
                entry == null
                        ? defaultAggregationTimeout()
                        : entry.document()
                                .path("detailTimeoutMillis")
                                .asLong(defaultAggregationTimeout());
        return new AggregationView(
                timeout,
                entry == null ? null : entry.updatedAt().toString(),
                AGGREGATION_EFFECTIVE_MODES);
    }

    private long defaultAggregationTimeout() {
        return configCenter
                .document(KEY_AGGREGATION_GLOBAL)
                .map(doc -> doc.path("detailTimeoutMillis").asLong(0))
                .filter(v -> v > 0)
                .orElse(2000L);
    }

    // —— 写路径（合并 → 校验落库） ——

    /** 当前文档为基（无键时以代码缺省视图序列化为基），返回可变副本供字段合并。 */
    private ObjectNode mutableDoc(String configKey, SourceCode sourceCode) {
        JsonNode current =
                configService
                        .read(configKey)
                        .map(RuntimeConfigEntry::document)
                        .orElseGet(() -> defaultDoc(sourceCode));
        return (ObjectNode) current.deepCopy();
    }

    /** 键缺失时的基线文档（与种子同源：代码缺省 + 全局 mock 开关裁定 mode）。 */
    private ObjectNode defaultDoc(SourceCode code) {
        RuntimeDataSource fallback = code == null ? null : configCenter.dataSource(code);
        if (code == null) {
            return objectMapper.createObjectNode().put("detailTimeoutMillis", 2000L);
        }
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("enabled", fallback.enabled());
        doc.put("mode", fallback.mode().name());
        doc.put("timeoutMillis", fallback.timeoutMillis());
        doc.put("retries", fallback.retries());
        doc.put("cacheTtlSeconds", fallback.cacheTtlSeconds());
        doc.set("params", objectMapper.valueToTree(fallback.params()));
        return doc;
    }

    private void requireKnownSource(SourceCode sourceCode) {
        if (sourceCode == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_CONFIG_NOT_FOUND, "数据源不存在");
        }
    }

    private RoutingSourceAdapter routingOf(SourceCode sourceCode) {
        return routingAdapters.stream()
                .filter(a -> a.sourceCode() == sourceCode)
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.DATASOURCE_CONFIG_NOT_FOUND,
                                        "数据源未装配路由: " + sourceCode));
    }

    private Instant parseExpected(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "expectedUpdatedAt: 须为 ISO-8601 时刻（如 2026-09-22T01:00:00Z）");
        }
    }

    // —— 连通性测试辅助 ——

    private static Long itemCountOf(SourceResult result) {
        Object items = result.getData().get("items");
        if (items instanceof List<?> list) {
            return (long) list.size();
        }
        return (long) result.getData().size();
    }

    /** mock 模式提示（PRD/UI：mock ≠ 真实连通，明示防误判）；EVENT 源提示本地表口径。 */
    private static String noteOf(SourceCode code, RuntimeDataSource config) {
        if (code == SourceCode.EVENT) {
            return "事件源读取本地异动表，无外部端点";
        }
        if (config.mode() == RuntimeDataSource.Mode.MOCK) {
            return "当前为 mock 模式：本地返回模拟数据，未发起真实外呼";
        }
        return null;
    }

    private static String summarize(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
