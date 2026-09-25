package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>软限频：东财 {@code np-anotice-stock} 无 token、按 IP 软限。<b>ISSUE-A 实测收紧（2026-09-22）</b>：WAF 升级后需带浏览器
 * User-Agent，并同 datacenter 财务端补东财站内 {@code Referer}（此前「无需 Referer」结论失效）。 响应实测以 {@code
 * text/plain;charset=UTF-8} 声明返回 JSON 体，容错读见 {@link EastMoneyHttpSupport}（ISSUE-B）。 超时不在本客户端设——由
 * {@link com.info.platform.infrastructure.common.ResilienceRunner}（2s 重试 0，见 ADR-0011）兜底。 HTTP
 * 异常直接抛出，由模板层降级。
 *
 * <p>详情 URL：eastmoney 公告 web 详情页为 JS 渲染，无稳定的 art_code 直链（{@code /notices/detail/<art_code>.html} 实测
 * 302 回退到列表页）。 故由 {@link #detailUrlOf} 按 art_code 拼装公告 PDF 直链 {@code
 * https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf}（2026-09-21 对多样本实测稳定，200 application/pdf）；{@code
 * H2_} 前缀已 A 股（source_type=31）实测确认，港股/其他市场前缀可能不同，故走运行时参数 {@code
 * datasource.ANNOUNCE.params.announceDetailUrlTemplate} 可调（页面可改，ADR-0032）。
 */
@Component
public class EastMoneyAnnounceClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyAnnounceClient.class);

    private static final String DEFAULT_ANNOUNCE_URL =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "announceUrl");

    /** 东财软限频来源页（ISSUE-A：公告端点 WAF 收紧后与 datacenter 财务端同带站内 Referer）。 */
    private static final String DEFAULT_ANNOUNCE_REFERER =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "announceReferer");

    /** 详情 PDF 直链模板（art_code 占位由 {@link #detailUrlOf} 替换）。H2_ 前缀已 A 股实测确认。 */
    private static final String DEFAULT_DETAIL_URL_TEMPLATE =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "announceDetailUrlTemplate");

    private static final String ART_CODE_PLACEHOLDER = "{art_code}";

    /** 东财公告列表 API 契约常量：sr=-1 按时间倒序、ann_type=A 全部公告、client_source=web。 */
    private static final int SORT_REVERSE = -1;

    private static final String ANN_TYPE_ALL = "A";

    private static final String CLIENT_SOURCE_WEB = "web";

    private static final int PAGE_INDEX_FIRST = 1;

    private static final int DEFAULT_PAGE_SIZE =
            DataSourceDefaults.paramInt(SourceCode.ANNOUNCE, "announcePageSize", 10);

    private final RestClient restClient;
    private final String announceUrl;
    private final int pageSize;
    private final String detailUrlTemplate;
    private final String referer;

    /**
     * 一页公告取数结果（M12 T90：{@code data.list} 条目 + {@code total_hits} 总数透出）。
     *
     * <p>totalHits 为该股公告总数（实测各页恒定，如 1074）；条目列表可能为空（越界页——page 合法但超出源总页数，
     * 与「无公告/代码不存在」（totalHits=0）区分，前者如实回显空页 + 总数）。
     */
    public record AnnouncePage(List<Map<String, Object>> items, long totalHits) {}

    /** 配置中心（T36 热化）：null（纯构造单测）时回落构造期缺省。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    /** Spring 装配构造（ADR-0032）：回落值取 {@link DataSourceDefaults} 代码内置缺省（原 yml adapter 段迁移）。 */
    @Autowired
    public EastMoneyAnnounceClient(RestClient.Builder restClientBuilder) {
        this(
                restClientBuilder,
                DEFAULT_ANNOUNCE_URL,
                DEFAULT_PAGE_SIZE,
                DEFAULT_DETAIL_URL_TEMPLATE,
                DEFAULT_ANNOUNCE_REFERER);
    }

    /** 全参构造（纯构造单测指定回落值）。 */
    public EastMoneyAnnounceClient(
            RestClient.Builder restClientBuilder,
            String announceUrl,
            int pageSize,
            String detailUrlTemplate,
            String referer) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.announceUrl = announceUrl;
        this.pageSize = pageSize;
        this.detailUrlTemplate = detailUrlTemplate;
        this.referer = referer;
    }

    /**
     * 取某 6 位证券代码最新一页公告（页大小走运行时 {@code announcePageSize}）。
     *
     * @param stockCode 6 位证券代码，如 {@code 600519}（沪）/ {@code 000001}（深）。非 secid。
     * @return 原始公告列表；{@code data.list} 为空/null 时返回 {@link Optional#empty()}（→ MISSING）
     */
    public Optional<List<Map<String, Object>>> fetchAnnouncements(String stockCode) {
        return fetchAnnouncementPage(stockCode, PAGE_INDEX_FIRST).map(AnnouncePage::items);
    }

    /**
     * 取某 6 位证券代码指定页公告（页大小走运行时 {@code announcePageSize}），透出 {@code total_hits} 总数（M12 T90）。
     *
     * @param stockCode 6 位证券代码；非 secid
     * @param pageIndex 页码（≥1；首屏聚合传 1，公告分区翻页透传源原生页码，实测深翻可用）
     * @return 一页结果（条目 + 总数）；{@code data} 节点缺失/null 返回 {@link Optional#empty()}（→ 降级/MISSING）
     */
    public Optional<AnnouncePage> fetchAnnouncementPage(String stockCode, int pageIndex) {
        int pageSize =
                RuntimeParams.intOf(
                        configCenter, SourceCode.ANNOUNCE, "announcePageSize", this.pageSize);
        return fetchAnnouncementPage(stockCode, pageIndex, pageSize);
    }

    /**
     * 取某 6 位证券代码指定页公告（显式页大小，分区子端点请求方页大小口径），透出 {@code total_hits} 总数（M12 T90）。
     *
     * <p>条目为空但 {@code total_hits > 0} 时仍返回 present（越界页语义：如实回显空页 + 总数，交上层区分处理—— 与
     * {@link #fetchAnnouncements} 的「空即 empty」口径不同，后者供首屏聚合走降级链）。
     *
     * @param stockCode 6 位证券代码；非 secid
     * @param pageIndex 页码（≥1）
     * @param pageSize 页大小（1~50，接口层已校验）
     * @return 一页结果（条目 + 总数）；{@code data} 节点缺失/null 返回 {@link Optional#empty()}
     */
    public Optional<AnnouncePage> fetchAnnouncementPage(
            String stockCode, int pageIndex, int pageSize) {
        String announceUrl =
                RuntimeParams.of(
                        configCenter, SourceCode.ANNOUNCE, "announceUrl", this.announceUrl);
        String referer =
                RuntimeParams.of(
                        configCenter, SourceCode.ANNOUNCE, "announceReferer", this.referer);
        String url = buildUrl(announceUrl, stockCode, pageIndex, pageSize);
        log.debug("东财公告请求 stockCode={} pageIndex={} pageSize={}", stockCode, pageIndex, pageSize);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                        .header("Referer", referer)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return extractPage(root);
    }

    /**
     * 由公告 {@code art_code} 拼装详情 PDF 直链。
     *
     * @param artCode 公告唯一 ID，如 {@code AN202608141827994407}
     * @return 详情 URL；模板未含占位符时原样返回
     */
    public String detailUrlOf(String artCode) {
        return RuntimeParams.of(
                        configCenter,
                        SourceCode.ANNOUNCE,
                        "announceDetailUrlTemplate",
                        this.detailUrlTemplate)
                .replace(ART_CODE_PLACEHOLDER, artCode);
    }

    /** 导航 {@code root.data.list}；任一层缺失/空数组返回 {@link Optional#empty()}（→ MISSING）。 */
    @SuppressWarnings("unchecked")
    private static Optional<List<Map<String, Object>>> extractList(Map<String, Object> root) {
        return extractPage(root).flatMap(page -> page.items().isEmpty() ? Optional.empty() : Optional.of(page.items()));
    }

    /**
     * 导航 {@code root.data.list + root.data.total_hits}（M12 T90）：data 节点缺失/null → empty（结构异常，交降级链）；
     * 条目为空但 total_hits&gt;0 → present（越界页语义）；两者皆空 → present（totalHits=0，由调用方判「无公告」）。
     */
    @SuppressWarnings("unchecked")
    private static Optional<AnnouncePage> extractPage(Map<String, Object> root) {
        if (root == null) {
            return Optional.empty();
        }
        Object data = root.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return Optional.empty();
        }
        Object list = dataMap.get("list");
        List<Map<String, Object>> items = List.of();
        if (list instanceof List<?> rawItems) {
            List<Map<String, Object>> parsed = new ArrayList<>(rawItems.size());
            for (Object item : rawItems) {
                if (item instanceof Map<?, ?> map) {
                    parsed.add((Map<String, Object>) map);
                }
            }
            items = List.copyOf(parsed);
        }
        long totalHits =
                dataMap.get("total_hits") instanceof Number number ? number.longValue() : 0L;
        return Optional.of(new AnnouncePage(items, totalHits));
    }

    private String buildUrl(String announceUrl, String stockCode, int pageIndex, int pageSize) {
        return UriComponentsBuilder.fromUriString(announceUrl)
                .queryParam("sr", SORT_REVERSE)
                .queryParam("page_size", pageSize)
                .queryParam("page_index", pageIndex)
                .queryParam("ann_type", ANN_TYPE_ALL)
                .queryParam("client_source", CLIENT_SOURCE_WEB)
                .queryParam("stock_list", stockCode)
                .build()
                .toUriString();
    }
}
