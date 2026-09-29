package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.mainline.IndustryQuoteSource;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.mainline.IndustryQuote;
import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.domain.mainline.LeaderStock;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 腾讯板块排行 HTTP 客户端（M27 T242 通道 B，方案 §4.2.1 + ADR-0063 裁决 1；Spike-C C-2b 实证 + 2026-09-28 预检复核）：
 * 实现应用层端口 {@link IndustryQuoteSource}——{@code proxy.finance.qq.com getRank board_type=hy} 单请求 16KB
 * 一次拉完恰 31 板块 = 申万一级 31 全集逐名相等（stock_type 全 {@code BK-HY-1}），直接产出 31 行业行（TENCENT_DIRECT，无需映射）。
 *
 * <p>字段映射（预检实测 2026-09-28）：{@code name}→industry（<b>启动期校验 31 名 ⊆ {@link
 * IndustryCategory#SW_INDUSTRIES}， 多/少即 fail-fast 本轮降级</b>——源侧分类改版防线）/ {@code zdf}→pctDay / {@code
 * zdf_d5}→pctD5 / {@code zljlr}×10⁴→mainNetFlow (万→元) / {@code zgb}("57/122")→up/down / {@code
 * zsz}×10⁴→totalMv (亿→元) / {@code lzg}→leaderStock（code 去市场前缀存 6 位）。JSON UTF-8 无 GBK 问题。
 *
 * <p>参数形态：{@code sort_type=price}（<b>2026-09-28 预检发现</b>：方案 §4.2.1 笔误的 {@code sort_type=code} 源侧报
 * 「参数错误:sort_type error」；附录 A.3 实证形态为 {@code price}，以 A.3 为准）+ {@code direct=up} + 单页 count=100 上限。
 * 浏览器 UA + {@code Referer: https://gu.qq.com/}（实测可带）。弹性同 clist 惯例。日志标签 {@code tencent-board-rank}。
 */
@Component
public class TencentBoardRankClient implements IndustryQuoteSource {

    private static final Logger log = LoggerFactory.getLogger(TencentBoardRankClient.class);

    /** 腾讯板块排行端点（Spike-C C-2b 实证形态）。 */
    static final String DEFAULT_RANK_URL =
            "https://proxy.finance.qq.com/cgi/cgi-bin/rank/pt/getRank";

    /** 通道来源留痕（industry_market_snapshot.source）。 */
    static final String SOURCE = "tencent-rank";

    /** 聚合方式留痕（通道 B 直出，无聚合）。 */
    static final String AGG_METHOD = "TENCENT_DIRECT";

    /** 万 → 元 的换算系数（zljlr/zsz 源单位万/亿制：万×10⁴=元，亿=万×10⁴=×10⁸——预检 zljlr 单位万、zsz 单位亿）。 */
    static final double WAN_TO_YUAN = 10_000d;

    /** 亿 → 元 的换算系数（zsz 源单位亿）。 */
    static final double YI_TO_YUAN = 100_000_000d;

    /** 单请求弹性（超时 5s / 重试 1 / 退避 500ms——GET 幂等）。 */
    private static final ResilienceSpec REQUEST_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(5), 1, Duration.ofMillis(500));

    /** 弹性日志标签。 */
    private static final String SOURCE_LABEL = "tencent-board-rank";

    /** 快照口径时区（quote_time 落库口径，Asia/Shanghai）。 */
    private static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 领涨股代码的市场前缀（sh/sz——落库去前缀存 6 位，与 subject_code 尾段对齐）。 */
    private static final String LEADER_CODE_PREFIX_REGEX = "^(sh|sz|bj)";

    private final RestClient restClient;

    private final ResilienceRunner resilienceRunner;

    private final Clock clock;

    private final String rankUrl;

    /** Spring 装配构造（缺省端点 + 系统时钟）。 */
    @Autowired
    public TencentBoardRankClient(
            RestClient.Builder restClientBuilder, ResilienceRunner resilienceRunner, Clock clock) {
        this(restClientBuilder, resilienceRunner, clock, DEFAULT_RANK_URL);
    }

    /** 全参构造（纯构造单测指定端点与时钟——零外呼）。 */
    public TencentBoardRankClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            Clock clock,
            String rankUrl) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.clock = clock;
        this.rankUrl = rankUrl;
    }

    @Override
    public IndustryQuoteBatch fetch() {
        Map<String, Object> body = fetchBody();
        if (!(body.get("data") instanceof Map<?, ?> data)
                || !(data.get("rank_list") instanceof List<?> rankList)) {
            throw new IllegalStateException("腾讯板块排行响应缺 data.rank_list 节点（本轮通道放弃）");
        }
        // 启动期 31 名 ⊆ 申万枚举校验（源侧分类改版防线——多名/少名即 fail-fast 本轮降级，方案 §4.2.1）
        List<String> names = new ArrayList<>(rankList.size());
        for (Object item : rankList) {
            if (item instanceof Map<?, ?> row) {
                names.add(String.valueOf(row.get("name")));
            }
        }
        Set<String> swIndustries = IndustryCategory.SW_INDUSTRIES;
        if (names.size() != swIndustries.size() || !swIndustries.containsAll(names)) {
            List<String> unknown =
                    names.stream().filter(name -> !swIndustries.contains(name)).toList();
            throw new IllegalStateException(
                    "腾讯板块排行 31 名漂移（本轮降级）rows=" + names.size() + " 未收录名=" + unknown);
        }
        List<IndustryQuote> industries = new ArrayList<>(rankList.size());
        for (Object item : rankList) {
            mapRow((Map<?, ?>) item).ifPresent(industries::add);
        }
        String quoteTime =
                clock.instant()
                        .atZone(SNAPSHOT_ZONE)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .toOffsetDateTime()
                        .toString();
        log.info("腾讯板块排行拉取完成 industries={}", industries.size());
        return new IndustryQuoteBatch(SOURCE, quoteTime, List.of(), List.copyOf(industries), 0);
    }

    /** 单请求拉取（ResilienceSpec 包裹），失败上抛由调用方按双通道全败降级处理。 */
    private Map<String, Object> fetchBody() {
        String url =
                UriComponentsBuilder.fromUriString(rankUrl)
                        .queryParam("board_type", "hy")
                        .queryParam("sort_type", "price")
                        .queryParam("direct", "up")
                        .queryParam("offset", 0)
                        .queryParam("count", 100)
                        .build()
                        .toUriString();
        try {
            return resilienceRunner.run(
                    () ->
                            restClient
                                    .get()
                                    .uri(url)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                                    .header("Referer", "https://gu.qq.com/")
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                    REQUEST_RESILIENCE,
                    SOURCE_LABEL);
        } catch (ResilienceException e) {
            throw new IllegalStateException("腾讯板块排行拉取失败（重试耗尽）", e);
        }
    }

    /** 单行映射：zdf/zdf_d5/zljlr×10⁴/zgb 两数/zsz×10⁸/lzg 去前缀。 */
    private java.util.Optional<IndustryQuote> mapRow(Map<?, ?> row) {
        String industry = String.valueOf(row.get("name"));
        int[] upDown = parseZgb(row.get("zgb"));
        LeaderStock leader = mapLeader(row.get("lzg"));
        return java.util.Optional.of(
                new IndustryQuote(
                        industry,
                        doubleOf(row.get("zdf")),
                        doubleOf(row.get("zdf_d5")),
                        upDown[0],
                        upDown[1],
                        doubleOf(row.get("zljlr")) == null
                                ? null
                                : doubleOf(row.get("zljlr")) * WAN_TO_YUAN,
                        doubleOf(row.get("zsz")) == null
                                ? null
                                : doubleOf(row.get("zsz")) * YI_TO_YUAN,
                        leader,
                        AGG_METHOD));
    }

    /** {@code zgb} "57/122" 两数解析（涨/跌家数；异常形态记 0/0 不失败整轮）。 */
    private static int[] parseZgb(Object raw) {
        if (raw == null) {
            return new int[] {0, 0};
        }
        String[] parts = String.valueOf(raw).split("/");
        if (parts.length != 2) {
            return new int[] {0, 0};
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }

    /** 领涨股映射（code 去 sh/sz/bj 前缀；缺行返回 null——通道 A 语义）。 */
    private static LeaderStock mapLeader(Object raw) {
        if (!(raw instanceof Map<?, ?> lzg)) {
            return null;
        }
        String code = lzg.get("code") == null ? null : String.valueOf(lzg.get("code"));
        return new LeaderStock(
                code == null ? null : code.replaceFirst(LEADER_CODE_PREFIX_REGEX, ""),
                lzg.get("name") == null ? null : String.valueOf(lzg.get("name")),
                doubleOf(lzg.get("zdf")));
    }

    private static Double doubleOf(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
