package com.info.platform.infrastructure.aggregation;

import java.util.ArrayList;
import java.util.List;
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
 * 东方财富 {@code np-anotice-stock} 公告 HTTP 客户端（T05 公告源）。
 *
 * <p>封装 GET {@code np-anotice-stock.eastmoney.com/api/security/ann}：按 6 位证券代码取最新 N 条公告列表。 与 {@link
 * EastMoneyClient}（push2 行情/估值）、{@link EastMoneyFinanceClient}（datacenter 财务）属<b>不同端点/域名</b>，
 * 响应结构亦不同 （公告返回 {@code data.list[]} 数组，行情返回 {@code data} 单对象、财务返回 {@code result.data[]}
 * 数组），故单列一类客户端。
 *
 * <p>响应结构（2026-09-21 curl 实测，茅台 600519）：
 *
 * <pre>{@code
 * {"data":{"list":[{
 *   "art_code":"AN202608141827994407",
 *   "title":"贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告",
 *   "title_ch":"...","title_en":"",
 *   "notice_date":"2026-08-15 00:00:00","display_time":"2026-08-14 20:41:29:276",
 *   "sort_date":"2026-08-15 12:00:00","source_type":"31",
 *   "codes":[{"ann_type":"A,SHA","stock_code":"600519","short_name":"贵州茅台", ...}],
 *   "columns":[{"column_code":"001002008","column_name":"其他"}]
 * }, ...],"page_index":1,"page_size":3,"total_hits":1074},
 *  "error":"","success":1}
 * }</pre>
 *
 * <p>导航 {@code root.data.list} 为原始公告数组（与 Spike-1 §6.4 载 {@code data.list} 一致，无偏差）。 <b>字段嵌套偏差</b>：
 * Spike-1 §4.4 将 {@code stock_code}/{@code short_name}/{@code column_name} 列为列表项顶层字段，实测却分别嵌在 {@code
 * codes[0]} 与 {@code columns[0]} 数组内——故本客户端仅返回原始 {@code data.list}（保留嵌套），由 {@link
 * AnnounceSourceAdapter#doFetch} 拍平后逐条映射。
 *
 * <p>空结果（无公告或代码不存在）实测：{@code data.list=[]}、{@code total_hits=0}，返回 {@link Optional#empty()}（→
 * MISSING）。 不验 {@code success} 标志（与 {@link EastMoneyFinanceClient} 一致，靠结构导航）。
 *
 * <p>软限频：东财 {@code np-anotice-stock} 无 token、按 IP 软限，<b>无需 Referer 头</b>（2026-09-21 curl 实测无
 * Referer 返回 200，与 datacenter 财务端需 Referer 不同）。超时不在本客户端设——由 {@link
 * com.info.platform.infrastructure.common.ResilienceRunner}（2s 重试 0，见 ADR-0011）兜底。 HTTP
 * 异常直接抛出，由模板层降级。
 *
 * <p>详情 URL：eastmoney 公告 web 详情页为 JS 渲染，无稳定的 art_code 直链（{@code /notices/detail/<art_code>.html} 实测
 * 302 回退到列表页）。 故由 {@link #detailUrlOf} 按 art_code 拼装公告 PDF 直链 {@code
 * https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf}（2026-09-21 对多样本实测稳定，200 application/pdf）；{@code
 * H2_} 前缀已 A 股（source_type=31）实测确认，港股/其他市场前缀可能不同，故走配置项 {@code
 * adapter.eastmoney.announce-detail-url-template} 可调。
 */
@Component
public class EastMoneyAnnounceClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyAnnounceClient.class);

    private static final String DEFAULT_ANNOUNCE_URL =
            "https://np-anotice-stock.eastmoney.com/api/security/ann";

    /** 详情 PDF 直链模板（art_code 占位由 {@link #detailUrlOf} 替换）。H2_ 前缀已 A 股实测确认。 */
    private static final String DEFAULT_DETAIL_URL_TEMPLATE =
            "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf";

    private static final String ART_CODE_PLACEHOLDER = "{art_code}";

    /** 东财公告列表 API 契约常量：sr=-1 按时间倒序、ann_type=A 全部公告、client_source=web。 */
    private static final int SORT_REVERSE = -1;

    private static final String ANN_TYPE_ALL = "A";

    private static final String CLIENT_SOURCE_WEB = "web";

    private static final int PAGE_INDEX_FIRST = 1;

    private static final int DEFAULT_PAGE_SIZE = 3;

    private final RestClient restClient;
    private final String announceUrl;
    private final int pageSize;
    private final String detailUrlTemplate;

    public EastMoneyAnnounceClient(
            RestClient.Builder restClientBuilder,
            @Value("${adapter.eastmoney.announce-url:" + DEFAULT_ANNOUNCE_URL + "}")
                    String announceUrl,
            @Value("${adapter.eastmoney.announce-page-size:" + DEFAULT_PAGE_SIZE + "}")
                    int pageSize,
            @Value(
                            "${adapter.eastmoney.announce-detail-url-template:"
                                    + DEFAULT_DETAIL_URL_TEMPLATE
                                    + "}")
                    String detailUrlTemplate) {
        this.restClient = restClientBuilder.build();
        this.announceUrl = announceUrl;
        this.pageSize = pageSize;
        this.detailUrlTemplate = detailUrlTemplate;
    }

    /**
     * 取某 6 位证券代码最新若干条公告（原始 {@code data.list}，含嵌套 codes[]/columns[]）。
     *
     * @param stockCode 6 位证券代码，如 {@code 600519}（沪）/ {@code 000001}（深）。非 secid。
     * @return 原始公告列表；{@code data.list} 为空/null 时返回 {@link Optional#empty()}（→ MISSING）
     */
    public Optional<List<Map<String, Object>>> fetchAnnouncements(String stockCode) {
        String url = buildUrl(stockCode);
        log.debug("东财公告请求 stockCode={} pageSize={}", stockCode, pageSize);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return extractList(root);
    }

    /**
     * 由公告 {@code art_code} 拼装详情 PDF 直链。
     *
     * @param artCode 公告唯一 ID，如 {@code AN202608141827994407}
     * @return 详情 URL；模板未含占位符时原样返回
     */
    public String detailUrlOf(String artCode) {
        return detailUrlTemplate.replace(ART_CODE_PLACEHOLDER, artCode);
    }

    /** 导航 {@code root.data.list}；任一层缺失/空数组返回 {@link Optional#empty()}（→ MISSING）。 */
    @SuppressWarnings("unchecked")
    private static Optional<List<Map<String, Object>>> extractList(Map<String, Object> root) {
        if (root == null) {
            return Optional.empty();
        }
        Object data = root.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return Optional.empty();
        }
        Object list = dataMap.get("list");
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private String buildUrl(String stockCode) {
        return UriComponentsBuilder.fromUriString(announceUrl)
                .queryParam("sr", SORT_REVERSE)
                .queryParam("page_size", pageSize)
                .queryParam("page_index", PAGE_INDEX_FIRST)
                .queryParam("ann_type", ANN_TYPE_ALL)
                .queryParam("client_source", CLIENT_SOURCE_WEB)
                .queryParam("stock_list", stockCode)
                .build()
                .toUriString();
    }
}
