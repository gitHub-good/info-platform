package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.mainline.IndustryQuoteSource;
import com.info.platform.domain.mainline.BoardAggregator;
import com.info.platform.domain.mainline.BoardQuote;
import com.info.platform.domain.mainline.IndustryQuote;
import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.domain.recommendation.IndustryDirectory;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 东财板块行情 HTTP 客户端（M27 T242 通道 A，方案 §4.2.1 + ADR-0063 裁决 1）：实现应用层端口 {@link
 * IndustryQuoteSource}——{@code push2 clist fs=m:90+t:2} 单页 pz=100 一次拉完约 86 板块行。
 *
 * <p>字段映射（fields 最小集）：f12 板块码（不落库）/ f14 板块名 → dim_name / f3 当日涨跌幅 / f62 主力净流入(元) / f104 涨家数 / f105
 * 跌家数 / f20 总市值。每行先过 {@link IndustryDirectory#swPrimaryOf}，null → 跳过 + WARN 计数（安全侧， 沿 M21
 * 先例——首跑复核条款 §4.2.5 留档后纯常量热修补映射）。聚合（板块→申万 31）在 {@code BoardAggregator}（本客户端内完成， 输出已聚合的行业行 + 板块明细行）。
 *
 * <p><b>push2 封禁期注记</b>（Spike-C C-1）：本会话内 push2 族 IP 级封禁不可实测——字段契约按方案 §4.2.1 写定 + 单测 mock 锁契约 +
 * 首跑复核留档（T242 验收项）。弹性沿 clist 惯例（超时 5s / 重试 1 / 退避 500ms）；共用约定复用 {@link EastMoneyHttpSupport} （浏览器
 * UA + {@code text/plain} JSON 容错读）。日志标签 {@code eastmoney-board-quote}。
 */
@Component
public class EastMoneyBoardQuoteClient implements IndustryQuoteSource {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyBoardQuoteClient.class);

    /** clist 板块行情端点（fs=m:90+t:2 = 行业板块桶）。 */
    static final String DEFAULT_BOARD_URL = "https://push2.eastmoney.com/api/qt/clist/get";

    /** 通道来源留痕（industry_market_snapshot.source）。 */
    static final String SOURCE = "eastmoney-push2";

    /** 只取 7 列（代码/名称/涨跌幅/主力净流入/涨跌家数/总市值），响应最小化。 */
    static final String BOARD_FIELDS = "f12,f14,f3,f62,f104,f105,f20";

    /** 板块桶（行业板块 fs=m:90+t:2）。 */
    private static final String BOARD_FILTER = "m:90+t:2";

    /** 单页 100（服务端强制上限；86±行一页足够）。 */
    private static final int PAGE_SIZE = 100;

    /** 按代码稳定排序（fid=f12 同因，翻页漂移最小化——单页形态下为稳定性惯例）。 */
    private static final String SORT_FIELD_CODE = "f12";

    private static final int SORT_DESCENDING = 1;

    /** 东财返回格式契约常量（clist 同款）：fltt=2 带小数、invt=2 单位元、np=1 网页原生参数。 */
    private static final int PRICE_FMT_DECIMAL = 2;

    private static final int PRICE_UNIT_YUAN = 2;

    /** 快照口径时区（quote_time 落库口径，Asia/Shanghai）。 */
    private static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 单请求弹性（clist 惯例：超时 5s / 重试 1 / 退避 500ms——GET 幂等）。 */
    private static final ResilienceSpec REQUEST_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(5), 1, Duration.ofMillis(500));

    /** 弹性日志标签（非既有 SourceCode 枚举的行情源）。 */
    private static final String SOURCE_LABEL = "eastmoney-board-quote";

    private final RestClient restClient;

    private final ResilienceRunner resilienceRunner;

    private final Clock clock;

    private final String boardUrl;

    /** Spring 装配构造（缺省端点 + 系统时钟）。 */
    @Autowired
    public EastMoneyBoardQuoteClient(
            RestClient.Builder restClientBuilder, ResilienceRunner resilienceRunner, Clock clock) {
        this(restClientBuilder, resilienceRunner, clock, DEFAULT_BOARD_URL);
    }

    /** 全参构造（纯构造单测指定端点与时钟——零外呼）。 */
    public EastMoneyBoardQuoteClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            Clock clock,
            String boardUrl) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.clock = clock;
        this.boardUrl = boardUrl;
    }

    @Override
    public IndustryQuoteBatch fetch() {
        Map<String, Object> body = fetchBody();
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("push2 板块行情响应缺 data 节点（本轮通道放弃）");
        }
        Object rawTotal = data.get("total");
        if (!(rawTotal instanceof Number total) || total.intValue() < 0) {
            throw new IllegalStateException("push2 板块行情响应 total 非法 total=" + rawTotal);
        }
        List<BoardQuote> boards = new ArrayList<>();
        int unmapped = 0;
        if (data.get("diff") instanceof List<?> diff) {
            for (Object item : diff) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                Object name = row.get("f14");
                String boardName = name == null ? null : String.valueOf(name).trim();
                String industry =
                        boardName == null || boardName.isEmpty()
                                ? null
                                : IndustryDirectory.swPrimaryOf(boardName);
                if (industry == null) {
                    unmapped++;
                    log.warn("未收录东财板块（安全侧跳过）: {}", boardName);
                    continue;
                }
                boards.add(
                        new BoardQuote(
                                boardName,
                                industry,
                                doubleOf(row.get("f3")),
                                intOf(row.get("f104")),
                                intOf(row.get("f105")),
                                doubleOf(row.get("f62")),
                                doubleOf(row.get("f20"))));
            }
        }
        if (boards.size() + unmapped != total.intValue()) {
            throw new IllegalStateException(
                    "push2 板块行情 total 完整性校验失败 collected="
                            + (boards.size() + unmapped)
                            + " total="
                            + total.intValue()
                            + "（丢行/异常行，本轮回填通道放弃）");
        }
        List<IndustryQuote> industries = BoardAggregator.aggregate(boards);
        String quoteTime =
                clock.instant().atZone(SNAPSHOT_ZONE).truncatedTo(ChronoUnit.SECONDS).toString();
        log.info(
                "push2 板块行情拉取完成 boards={} industries={} unmapped={}",
                boards.size(),
                industries.size(),
                unmapped);
        return new IndustryQuoteBatch(SOURCE, quoteTime, boards, industries, unmapped);
    }

    /** 单请求拉取（ResilienceSpec 包裹），失败上抛由调用方按通道降级处理。 */
    private Map<String, Object> fetchBody() {
        String url = buildUrl();
        try {
            return resilienceRunner.run(
                    () ->
                            restClient
                                    .get()
                                    .uri(url)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                    REQUEST_RESILIENCE,
                    SOURCE_LABEL);
        } catch (ResilienceException e) {
            throw new IllegalStateException("push2 板块行情拉取失败（重试耗尽）", e);
        }
    }

    /** 手工拼接 query 串（EastMoneyListClient 同因：UriComponentsBuilder 对已编码参数行为不稳——fs 含 '+' 不再编码）。 */
    private String buildUrl() {
        return UriComponentsBuilder.fromUriString(boardUrl)
                .queryParam("pn", 1)
                .queryParam("pz", PAGE_SIZE)
                .queryParam("po", SORT_DESCENDING)
                .queryParam("np", 1)
                .queryParam("fltt", PRICE_FMT_DECIMAL)
                .queryParam("invt", PRICE_UNIT_YUAN)
                .queryParam("fid", SORT_FIELD_CODE)
                .queryParam("fs", BOARD_FILTER)
                .queryParam("fields", BOARD_FIELDS)
                .build()
                .toUriString();
    }

    /** 数值容错映射：null / "-" 占位 / 非数值 → null（缺数语义）。 */
    private static Double doubleOf(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private static Integer intOf(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
