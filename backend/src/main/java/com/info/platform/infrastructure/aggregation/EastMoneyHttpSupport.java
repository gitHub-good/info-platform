package com.info.platform.infrastructure.aggregation;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * 东财系 HTTP 客户端共用约定（{@link EastMoneyClient} / {@link EastMoneyFinanceClient} / {@link
 * EastMoneyAnnounceClient}）：浏览器 UA 防断连 + {@code text/plain} JSON 容错读。
 *
 * <p><b>UA（ISSUE-A）</b>：东财 WAF 对无 User-Agent 的 Java/curl 请求间歇断连（{@code Unexpected end of file from
 * server}），带浏览器 UA 可通——与新浪/政源同策略（{@code SinaNewsClient} / {@code GovPolicyClient}）。
 *
 * <p><b>text/plain JSON（ISSUE-B）</b>：datacenter 与 np-anotice 实测（2026-09-22 curl）返回 200 + {@code
 * text/plain;charset=UTF-8} 的 JSON 体，而 RestClient 默认 Jackson converter 只读 {@code
 * application/json}，会抛 {@code UnknownContentTypeException} 致整源降级。{@link #withTextPlainJson} 在默认
 * converter 列表<b>末尾</b>追加一个 兼容 {@code text/plain} 的 Jackson converter——默认 converter 仍在前、{@code
 * application/json} 响应路径不受影响。
 */
final class EastMoneyHttpSupport {

    /** 浏览器 UA（与 SinaNewsClient/GovPolicyClient 同款，防东财 WAF 对裸 UA 断连）。 */
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** 东财端点 content-type 漂移容错：JSON 体也可能以 text/plain 声明返回。 */
    private static final List<MediaType> JSON_OR_TEXT_PLAIN =
            List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

    private EastMoneyHttpSupport() {}

    /** 追加兼容 {@code text/plain} 的 Jackson converter（追加在默认列表末尾，不改动既有读路径）。 */
    static RestClient.Builder withTextPlainJson(RestClient.Builder builder) {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(JSON_OR_TEXT_PLAIN);
        return builder.messageConverters(converters -> converters.add(converter));
    }
}
