package com.info.platform.infrastructure.aggregation;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 东方财富 push2 行情 HTTP 客户端（T03）。
 *
 * <p>封装 GET {@code push2.eastmoney.com/api/qt/stock/get}：按 secid + f 字段列表取行情原始数据节点。 端点 URL 与字段列表走
 * {@code application.yml}（{@code adapter.eastmoney.*}）可配，不硬编码全 URL。
 *
 * <p>超时不在本客户端重复设置——弹性超时由上层 {@link com.info.platform.infrastructure.common.ResilienceRunner}（{@code
 * ResilienceSpec} 1.5s）统一兜底， 对齐 ADR-0010（弹性收敛在 ResilienceRunner）。HTTP 异常（4xx/5xx/连接失败）直接抛出，由模板层降级。
 *
 * <p>{@code fltt=2} / {@code invt=2} 为东财返回格式契约（价格返回带小数、单位元），属 API 契约常量非配置项。
 */
@Component
public class EastMoneyClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyClient.class);

    /** 东财返回格式：fltt=2 价格返回带小数（非 ×100 整数）。 */
    private static final int PRICE_FMT_DECIMAL = 2;

    /** 东财价格单位：invt=2 单位为元。 */
    private static final int PRICE_UNIT_YUAN = 2;

    private final RestClient restClient;
    private final String quoteUrl;
    private final String fields;

    public EastMoneyClient(
            RestClient.Builder restClientBuilder,
            @Value("${adapter.eastmoney.quote-url:https://push2.eastmoney.com/api/qt/stock/get}")
                    String quoteUrl,
            @Value("${adapter.eastmoney.fields:f43,f44,f45,f46,f47,f48,f57,f58,f60,f169,f170,f171}")
                    String fields) {
        this.restClient = restClientBuilder.build();
        this.quoteUrl = quoteUrl;
        this.fields = fields;
    }

    /**
     * 取某 secid 的行情数据节点（{@code data} 节点的 f 字段→值）。
     *
     * @param secid 东财 secid，如 {@code 1.600519}（沪）/ {@code 0.000001}（深）/ {@code 116.00700}（港）
     * @return data 节点；盘外/停牌 data 为空或 null 时返回 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> fetchQuote(String secid) {
        String url = buildUrl(secid);
        log.debug("东财行情请求 secid={}", secid);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (root == null) {
            return Optional.empty();
        }
        Object data = root.get("data");
        if (data instanceof Map<?, ?> map && !map.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dataNode = (Map<String, Object>) data;
            return Optional.of(dataNode);
        }
        return Optional.empty();
    }

    private String buildUrl(String secid) {
        return UriComponentsBuilder.fromUriString(quoteUrl)
                .queryParam("secid", secid)
                .queryParam("fields", fields)
                .queryParam("fltt", PRICE_FMT_DECIMAL)
                .queryParam("invt", PRICE_UNIT_YUAN)
                .build()
                .toUriString();
    }
}
