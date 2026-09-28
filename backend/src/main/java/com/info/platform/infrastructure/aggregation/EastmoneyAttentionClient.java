package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.mainline.AttentionSource;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 东财 datacenter 主力关注度代理客户端（M27 T244，方案 §4.4.4 + ADR-0063 裁决 5；Spike-D D-4/D-5 实测参数形态）： 龙虎榜 30 日计数
 * （{@code RPT_DAILYBILLBOARD_DETAILSNEW}，{@code result.count} 服务端直出）+ 增减持净方向（{@code
 * RPT_SHARE_HOLDER_INCREASE}，按 DIRECTION 分组计数）。
 *
 * <p>复用 {@link EastMoneyHttpSupport}（浏览器 UA + {@code text/plain} JSON 容错）+ {@code Referer:
 * https://data.eastmoney.com/}（datacenter 软限频，EastmoneyDatacenterClient 同款）；单请求弹性沿惯例（超时 5s / 重试 1 /
 * 退避 500ms——GET 幂等）。调用节奏由 {@code AttentionProxyService} 编排（≤15 只 × 2 报表 = ≤30 请求/日集中在盘后，页间 500ms
 * 礼貌间隔）。
 */
@Component
public class EastmoneyAttentionClient implements AttentionSource {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyAttentionClient.class);

    /** datacenter 报表端点（EastmoneyDatacenterClient 同端点）。 */
    static final String DEFAULT_REPORT_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";

    /** 龙虎榜报表名（个股维 + 日期窗复合过滤，Spike-D D-4 实证）。 */
    static final String LHB_REPORT = "RPT_DAILYBILLBOARD_DETAILSNEW";

    /** 增减持报表名（个股维 + NOTICE_DATE 窗，Spike-D D-5 实证）。 */
    static final String HOLDER_CHANGE_REPORT = "RPT_SHARE_HOLDER_INCREASE";

    /** 龙虎榜单页取首行即可（count 即 30 日计数——1 请求即答案）。 */
    private static final int LHB_PAGE_SIZE = 1;

    /** 增减持窗内行数上限（分组计数防御上限——30 日窗实测个位数）。 */
    private static final int HOLDER_PAGE_SIZE = 100;

    /** 单请求弹性（超时 5s / 重试 1 / 退避 500ms——GET 幂等）。 */
    private static final ResilienceSpec REQUEST_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(5), 1, Duration.ofMillis(500));

    private final RestClient restClient;

    private final ResilienceRunner resilienceRunner;

    private final String reportUrl;

    /** Spring 装配构造（缺省端点）。 */
    @Autowired
    public EastmoneyAttentionClient(
            RestClient.Builder restClientBuilder, ResilienceRunner resilienceRunner) {
        this(restClientBuilder, resilienceRunner, DEFAULT_REPORT_URL);
    }

    /** 全参构造（纯构造单测指定端点——零外呼）。 */
    public EastmoneyAttentionClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            String reportUrl) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.reportUrl = reportUrl;
    }

    /**
     * 龙虎榜近窗计数 + 最近一次上榜（count 服务端直出——D-4「1 请求即答案」）。
     *
     * @param code 6 位证券代码
     * @param sinceDate TRADE_DATE 下界（yyyy-MM-dd，含）
     */
    @Override
    public LhbSummary fetchLhbSummary(String code, String sinceDate) {
        Map<String, Object> body =
                fetchReport(
                        LHB_REPORT,
                        "(SECURITY_CODE=\"" + code + "\")(TRADE_DATE>='" + sinceDate + "')",
                        "TRADE_DATE",
                        LHB_PAGE_SIZE);
        Map<?, ?> result = resultOf(body);
        int count = ((Number) result.get("count")).intValue();
        String latestDate = null;
        String reason = null;
        if (result.get("data") instanceof java.util.List<?> data
                && !data.isEmpty()
                && data.get(0) instanceof Map<?, ?> first) {
            latestDate = textOf(first.get("TRADE_DATE"));
            reason = textOf(first.get("EXPLANATION"));
        }
        return new LhbSummary(count, latestDate, reason);
    }

    /**
     * 增减持近窗净方向（按 DIRECTION 分组计数 → 净增持/净减持/均衡）。
     *
     * @param sinceDate NOTICE_DATE 下界（yyyy-MM-dd，含）
     */
    @Override
    public HolderChangeSummary fetchHolderChangeSummary(String code, String sinceDate) {
        Map<String, Object> body =
                fetchReport(
                        HOLDER_CHANGE_REPORT,
                        "(SECURITY_CODE=\"" + code + "\")(NOTICE_DATE>='" + sinceDate + "')",
                        "NOTICE_DATE",
                        HOLDER_PAGE_SIZE);
        Map<?, ?> result = resultOf(body);
        Map<String, Integer> byDirection = new LinkedHashMap<>();
        if (result.get("data") instanceof java.util.List<?> data) {
            for (Object item : data) {
                if (item instanceof Map<?, ?> row) {
                    String direction = textOf(row.get("DIRECTION"));
                    if (direction != null) {
                        byDirection.merge(direction, 1, Integer::sum);
                    }
                }
            }
        }
        int increase = byDirection.getOrDefault("增持", 0);
        int decrease = byDirection.getOrDefault("减持", 0);
        String net = increase > decrease ? "净增持" : increase < decrease ? "净减持" : "均衡";
        return new HolderChangeSummary(increase, decrease, net);
    }

    private Map<String, Object> fetchReport(
            String reportName, String filter, String sortColumn, int pageSize) {
        String url =
                reportUrl
                        + "?reportName="
                        + reportName
                        + "&columns=ALL&filter="
                        + filter
                        + "&pageNumber=1&pageSize="
                        + pageSize
                        + "&sortColumns="
                        + sortColumn
                        + "&sortTypes=-1&source=WEB&client=WEB";
        try {
            return resilienceRunner.run(
                    () ->
                            restClient
                                    .get()
                                    .uri(url)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                                    .header("Referer", "https://data.eastmoney.com/")
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                    REQUEST_RESILIENCE,
                    "eastmoney-attention");
        } catch (ResilienceException e) {
            throw new IllegalStateException("datacenter 报表拉取失败（重试耗尽）report=" + reportName, e);
        }
    }

    private static Map<?, ?> resultOf(Map<String, Object> body) {
        if (!(body.get("result") instanceof Map<?, ?> result)
                || !(result.get("count") instanceof Number)) {
            throw new IllegalStateException("datacenter 响应缺 result/count 节点（该只徽章降级）");
        }
        return result;
    }

    private static String textOf(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw);
        return text.isBlank() ? null : text;
    }
}
