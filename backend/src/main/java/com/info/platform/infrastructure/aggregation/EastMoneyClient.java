package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 东方财富 push2 {@code stock/get} HTTP 客户端（T03 行情 + T04 估值）。
 *
 * <p>封装 GET {@code push2.eastmoney.com/api/qt/stock/get}：按 secid + f 字段列表取 {@code data} 节点原始字段。
 * 行情与估值同走该端点（Spike-1 §3.1 选型「端点收敛」），仅 f 字段列表不同—— 行情传行情列（f43/f44…）， 估值传估值列（f162/f167…）。端点 URL
 * 与行情字段列表走 {@code application.yml}（{@code adapter.eastmoney.*}）可配，不硬编码全 URL。
 *
 * <p>两类调用入口： {@link #fetchQuote}（行情 adapter 用）与 {@link #fetchValuation}（估值 adapter 用）——同端点不同 f 字段列。
 * <b>T36 热化</b>：URL 与字段列表改<b>每次调用</b>从运行时配置 {@code datasource.QUOTE / VALUATION.params} 读取（LIVE
 * 级，页面保存即生效）； 配置中心缺失（纯构造单测）回落构造期 {@code @Value} yml 值。
 *
 * <p>超时不在本客户端重复设置——弹性超时由上层 {@link com.info.platform.infrastructure.common.ResilienceRunner}（行情 1.5s
 * / 估值 2s）统一兜底， 对齐 ADR-0010（弹性收敛在 ResilienceRunner）。HTTP 异常（4xx/5xx/连接失败）直接抛出，由模板层降级。
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
    private final String valuationFields;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落 @Value yml 值。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    public EastMoneyClient(
            RestClient.Builder restClientBuilder,
            @Value("${adapter.eastmoney.quote-url:https://push2.eastmoney.com/api/qt/stock/get}")
                    String quoteUrl,
            @Value("${adapter.eastmoney.fields:f43,f44,f45,f46,f47,f48,f57,f58,f60,f169,f170,f171}")
                    String fields,
            @Value("${adapter.eastmoney.valuation-fields:f57,f162,f167}") String valuationFields) {
        this.restClient = restClientBuilder.build();
        this.quoteUrl = quoteUrl;
        this.fields = fields;
        this.valuationFields = valuationFields;
    }

    /**
     * 取某 secid 的行情数据节点（{@code data} 节点的 f 字段→值）。
     *
     * <p>URL/字段每次调用读 {@code datasource.QUOTE.params}（quoteUrl/fields，LIVE 级热生效）。
     *
     * @param secid 东财 secid，如 {@code 1.600519}（沪）/ {@code 0.000001}（深）/ {@code 116.00700}（港）
     * @return data 节点；盘外/停牌 data 为空或 null 时返回 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> fetchQuote(String secid) {
        return fetch(
                RuntimeParams.of(configCenter, SourceCode.QUOTE, "quoteUrl", quoteUrl),
                secid,
                RuntimeParams.of(configCenter, SourceCode.QUOTE, "fields", fields));
    }

    /**
     * 取某 secid 的估值数据节点（与行情同端点，fields 取估值列）。
     *
     * <p>估值 adapter（T04）复用本客户端：URL/字段每次调用读 {@code datasource.VALUATION.params}
     * （quoteUrl/valuationFields，LIVE 级热生效）。
     *
     * @param secid 东财 secid，如 {@code 1.600519}
     * @return data 节点；data 为空或 null 时返回 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> fetchValuation(String secid) {
        return fetch(
                RuntimeParams.of(configCenter, SourceCode.VALUATION, "quoteUrl", quoteUrl),
                secid,
                RuntimeParams.of(
                        configCenter, SourceCode.VALUATION, "valuationFields", valuationFields));
    }

    /** 取数（显式 URL + fields）。 */
    Optional<Map<String, Object>> fetch(String url, String secid, String fields) {
        String requestUrl = buildUrl(url, secid, fields);
        log.debug("东财 push2 请求 secid={} fields={}", secid, fields);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(requestUrl)
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

    private String buildUrl(String quoteUrl, String secid, String fields) {
        return UriComponentsBuilder.fromUriString(quoteUrl)
                .queryParam("secid", secid)
                .queryParam("fields", fields)
                .queryParam("fltt", PRICE_FMT_DECIMAL)
                .queryParam("invt", PRICE_UNIT_YUAN)
                .build()
                .toUriString();
    }
}
