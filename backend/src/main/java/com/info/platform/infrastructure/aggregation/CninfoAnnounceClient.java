package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 巨潮资讯 {@code cninfo} 公告 HTTP 客户端（{@code www.cninfo.com.cn}，ADR-0034 T57 公告备选源）：由 {@link
 * AnnounceSourceAdapter} 在东财失败/空响应降级时调用。
 *
 * <p><b>输出即东财公告 flat 中间结构</b>（ADR-0031 模式）：本客户端把巨潮条目映射为 {@link AnnounceSourceAdapter#doFetch}
 * 拍平后的同款键（art_code / title / notice_date / stock_code / short_name / adjunct_url）， {@code
 * field-mapping/eastmoney-announce.json} 零改动即可逐条映射——备选源对聚合契约完全透明。
 *
 * <h2>响应契约（2026-09-24 实测，ADR-0034 附录 B）</h2>
 *
 * <ul>
 *   <li><b>orgId 映射表</b>：GET {@code /new/data/szse_stock.json}（UTF-8 JSON 数组，~6258 条）——文件名虽为
 *       szse，实测<b>沪深全量皆在内</b> （600519→gssh0600519、000001→gssz0000001；sse_stock.json 为 404 不需要）；条目按
 *       {@code code}（6 位）→ {@code orgId} 入缓存
 *   <li><b>公告查询</b>：POST {@code
 *       /new/hisAnnouncement/query}（form：pageNum/pageSize/column=szse/tabName=fulltext/
 *       stock={code},{orgId}/seDate=/isHLtitle=false）——<b>orgId 必填</b>（缺省查询返回空），双市场样本均 200 按公告时间倒序；
 *       无 Referer 要求（普通 UA 即可，防御性携带浏览器 UA）；{@code column=szse} 为双市场共用列值（实测沪样本同可用）
 *   <li><b>字段</b>：{@code announcementId}→art_code、{@code announcementTitle}→title（isHLtitle=false
 *       已免 {@code <em>} 高亮标签，防御性再剔）、{@code announcementTime}（epoch 毫秒，实测为公告日
 *       00:00）→notice_date（Asia/Shanghai {@code yyyy-MM-dd HH:mm:ss}，与东财 {@code notice_date}
 *       完全一致，{@code to_iso_date} 映射不变）、 {@code secCode/secName}→stock_code/short_name、{@code
 *       adjunctUrl}→adjunct_url（仅供 adapter 拼详情直链，不进映射白名单）
 *   <li><b>分类字段缺口</b>：cninfo 无东财 {@code column_name} 同款友好分类名（仅 columnId 数字串）——category
 *       按白名单语义缺失不产出（公告分区核心形态标题/时间/URL 与主源一致，ADR-0034 §3.2）
 * </ul>
 *
 * <h2>orgId 映射缓存（TTL 24h）</h2>
 *
 * <p>{@code volatile} 表 + 加载时刻 + synchronized 双检单飞刷新：过期/缺失时同步刷新；<b>刷新失败沿用旧表</b>（stale 容忍，WARN
 * 留痕）；无旧表可沿则抛异常（该级失败，交链执行器）。条目 ~6258 无内存压力；24h 一次消除稳态第二请求。 标的未命中映射表（如港股，vFD 同口径对齐主源现状）→ empty（→
 * 链下一级 / MISSING，不发查询请求）。
 *
 * <p>空响应：{@code announcements} null/[] → empty；导航 {@code root.announcements[]} 与东财 {@code
 * data.list[]} 的结构差异在本客户端内吸收。分页从简：按方案参数取第一页 {@code announcePageSize} 条（最新公告按时间倒序在前）。
 *
 * <p>超时不在此设——弹性预算两轮合用（ANNOUNCE noRetry 2s 覆盖东财一轮 + 巨潮稳态单请求；冷启动首轮 orgId 拉取 +0.3s，query 偶发慢样本同属
 * ADR-0031 已知限制）；三参数运行时可调（种子入 {@link DataSourceDefaults}，页面可改，ADR-0032）。
 */
@Component
public class CninfoAnnounceClient {

    private static final Logger log = LoggerFactory.getLogger(CninfoAnnounceClient.class);

    /** 查询 POST 端点（缺省取 {@link DataSourceDefaults}，构造期回落）。 */
    static final String DEFAULT_QUERY_URL =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "cninfoQueryUrl");

    /** orgId 映射表端点（同上）。 */
    static final String DEFAULT_STOCK_LIST_URL =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "cninfoStockListUrl");

    /** 详情 PDF 直链前缀（拼 {@code adjunctUrl} 相对路径）。 */
    static final String DEFAULT_DETAIL_URL_PREFIX =
            DataSourceDefaults.paramString(SourceCode.ANNOUNCE, "cninfoDetailUrlPrefix");

    /** 查询 POST form 契约常量（ADR-0034 实测）：column=szse 双市场共用列值、全文标签页、关闭标题高亮。 */
    private static final String COLUMN_SZSE = "szse";

    private static final String TAB_NAME_FULLTEXT = "fulltext";

    private static final String HIGHLIGHT_TITLE_OFF = "false";

    private static final int PAGE_NUMBER_FIRST = 1;

    private static final String SE_DATE_UNBOUNDED = "";

    /** 默认条数回落（运行时复用既有 announcePageSize）。 */
    private static final int DEFAULT_PAGE_SIZE =
            DataSourceDefaults.paramInt(SourceCode.ANNOUNCE, "announcePageSize", 10);

    /** 浏览器 UA（防御性携带；实测无 UA 要求）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** orgId 映射缓存 TTL（方案 §4.2：24h；全参构造可注入短 TTL 供单测）。 */
    static final Duration ORG_ID_TTL = Duration.ofHours(24);

    /** 公告时间格式（对齐东财 notice_date，Asia/Shanghai 墙钟口径）。 */
    private static final DateTimeFormatter NOTICE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 标题防御性去高亮标签（isHLtitle=false 已免，改版防御）。 */
    private static final String EM_TAG_PATTERN = "</?em>";

    private final RestClient restClient;
    private final String queryUrl;
    private final String stockListUrl;
    private final String detailUrlPrefix;
    private final Duration orgIdTtl;

    /** orgId 映射缓存（volatile + synchronized 双检刷新；刷新失败沿旧表 stale 容忍）。 */
    private volatile Map<String, String> codeToOrgId = Map.of();

    private volatile Instant orgIdLoadedAt;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落构造期缺省。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    /** Spring 装配构造（ADR-0032）：回落值取 {@link DataSourceDefaults} 代码内置缺省。 */
    @Autowired
    public CninfoAnnounceClient(RestClient.Builder restClientBuilder) {
        this(
                restClientBuilder,
                DEFAULT_QUERY_URL,
                DEFAULT_STOCK_LIST_URL,
                DEFAULT_DETAIL_URL_PREFIX,
                ORG_ID_TTL);
    }

    /** 全参构造（纯构造单测指定回落值与 orgId 缓存 TTL）。 */
    public CninfoAnnounceClient(
            RestClient.Builder restClientBuilder,
            String queryUrl,
            String stockListUrl,
            String detailUrlPrefix,
            Duration orgIdTtl) {
        this.restClient = restClientBuilder.build();
        this.queryUrl = queryUrl;
        this.stockListUrl = stockListUrl;
        this.detailUrlPrefix = detailUrlPrefix;
        this.orgIdTtl = orgIdTtl;
    }

    /**
     * 取某 6 位证券代码最新若干条公告（东财公告 flat 中间结构，键见类 Javadoc）。
     *
     * @param stockCode 6 位证券代码，如 {@code 600519}（沪）/ {@code 000001}（深）
     * @return flat 条目列表；{@code announcements} 空/null 或标的未命中 orgId 映射表时 {@link Optional#empty()}（→
     *     链下一级 / MISSING）
     * @throws org.springframework.web.client.RestClientException 查询 POST 失败，或 orgId
     *     表过期刷新失败且无旧表可沿（该级失败交链执行器）
     */
    public Optional<List<Map<String, Object>>> fetchAnnouncements(String stockCode) {
        String queryUrl =
                RuntimeParams.of(
                        configCenter, SourceCode.ANNOUNCE, "cninfoQueryUrl", this.queryUrl);
        int pageSize =
                RuntimeParams.intOf(
                        configCenter, SourceCode.ANNOUNCE, "announcePageSize", DEFAULT_PAGE_SIZE);
        String orgId = orgIdOf(stockCode);
        if (orgId == null) {
            log.info("巨潮 orgId 映射未命中标的，按无公告处理 stockCode={}", stockCode);
            return Optional.empty();
        }
        Map<String, Object> root =
                restClient
                        .post()
                        .uri(URI.create(queryUrl))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("User-Agent", USER_AGENT)
                        .body(queryForm(stockCode, orgId, pageSize))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return extractItems(root, stockCode);
    }

    /**
     * 由公告 {@code adjunctUrl} 相对路径拼装详情 PDF 直链（{@code static.cninfo.com.cn} 前缀，实测 200
     * application/pdf）。
     *
     * @param adjunctUrl 相对路径，如 {@code finalpage/2026-08-15/1225475868.PDF}
     * @return 详情 URL
     */
    public String detailUrlOf(String adjunctUrl) {
        String prefix =
                RuntimeParams.of(
                        configCenter,
                        SourceCode.ANNOUNCE,
                        "cninfoDetailUrlPrefix",
                        this.detailUrlPrefix);
        return prefix + adjunctUrl;
    }

    /** POST form（契约常量见类 Javadoc；stock 为 {@code code,orgId} 复合格式）。 */
    private static MultiValueMap<String, String> queryForm(
            String stockCode, String orgId, int pageSize) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("pageNum", String.valueOf(PAGE_NUMBER_FIRST));
        form.add("pageSize", String.valueOf(pageSize));
        form.add("column", COLUMN_SZSE);
        form.add("tabName", TAB_NAME_FULLTEXT);
        form.add("stock", stockCode + "," + orgId);
        form.add("seDate", SE_DATE_UNBOUNDED);
        form.add("isHLtitle", HIGHLIGHT_TITLE_OFF);
        return form;
    }

    /** 导航 {@code root.announcements[]} 并逐条转东财 flat 键；任一层缺失/空数组返回 empty。 */
    private static Optional<List<Map<String, Object>>> extractItems(
            Map<String, Object> root, String stockCode) {
        if (root == null) {
            return Optional.empty();
        }
        Object announcements = root.get("announcements");
        if (!(announcements instanceof List<?> items) || items.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add(toFlatItem(map));
            }
        }
        if (result.isEmpty()) {
            log.info("巨潮公告条目均非对象结构，按无公告处理 stockCode={}", stockCode);
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /** 巨潮字段 → 东财 flat 键（映射核对表见类 Javadoc）；缺失/null 字段不入 flat（白名单语义）。 */
    private static Map<String, Object> toFlatItem(Map<?, ?> raw) {
        Map<String, Object> flat = new LinkedHashMap<>();
        putIfPresent(flat, "art_code", raw.get("announcementId"));
        putIfPresent(flat, "title", cleanTitle(raw.get("announcementTitle")));
        if (raw.get("announcementTime") instanceof Number time) {
            putIfPresent(flat, "notice_date", formatNoticeDate(time.longValue()));
        }
        putIfPresent(flat, "stock_code", raw.get("secCode"));
        putIfPresent(flat, "short_name", raw.get("secName"));
        putIfPresent(flat, "adjunct_url", raw.get("adjunctUrl"));
        return flat;
    }

    /** epoch 毫秒 → Asia/Shanghai {@code yyyy-MM-dd HH:mm:ss}（与东财 notice_date 同格式，to_iso_date 不变）。 */
    private static String formatNoticeDate(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE_SHANGHAI)
                .format(NOTICE_DATE_FORMAT);
    }

    /** 标题去 {@code <em>} 高亮标签（isHLtitle=false 已免，防御性双保险）。 */
    private static String cleanTitle(Object rawTitle) {
        return rawTitle == null ? null : String.valueOf(rawTitle).replaceAll(EM_TAG_PATTERN, "");
    }

    private static void putIfPresent(Map<String, Object> flat, String key, Object value) {
        if (value != null) {
            flat.put(key, value);
        }
    }

    /** 查 orgId：缓存过期/缺失先刷新（双检单飞）；未命中返回 null（→ 调用方按无公告处理）。 */
    private String orgIdOf(String stockCode) {
        if (isOrgIdCacheStale()) {
            refreshOrgIdTable();
        }
        return codeToOrgId.get(stockCode);
    }

    private boolean isOrgIdCacheStale() {
        Instant loadedAt = this.orgIdLoadedAt;
        return loadedAt == null || loadedAt.isBefore(Instant.now().minus(orgIdTtl));
    }

    /** 刷新 orgId 映射表（synchronized 双检单飞）：成功整体替换；失败时旧表非空则沿旧表（stale 容忍，WARN）， 无旧表可沿抛出（该级失败交链执行器）。 */
    private void refreshOrgIdTable() {
        synchronized (this) {
            if (!isOrgIdCacheStale()) {
                return;
            }
            String stockListUrl =
                    RuntimeParams.of(
                            configCenter,
                            SourceCode.ANNOUNCE,
                            "cninfoStockListUrl",
                            this.stockListUrl);
            try {
                Map<String, String> parsed = parseOrgIdTable(fetchOrgIdTable(stockListUrl));
                if (parsed.isEmpty()) {
                    throw new IllegalStateException("巨潮 orgId 映射表为空 url=" + stockListUrl);
                }
                this.codeToOrgId = parsed;
                this.orgIdLoadedAt = Instant.now();
                log.info("巨潮 orgId 映射表已加载 entries={}", parsed.size());
            } catch (RuntimeException e) {
                if (codeToOrgId.isEmpty()) {
                    throw e;
                }
                log.warn(
                        "巨潮 orgId 映射表刷新失败，沿用旧表 stale 容忍 entries={} error={}",
                        codeToOrgId.size(),
                        e.toString());
            }
        }
    }

    /** GET 映射表端点（UTF-8 JSON 数组）。 */
    private List<Map<String, Object>> fetchOrgIdTable(String stockListUrl) {
        return restClient
                .get()
                .uri(URI.create(stockListUrl))
                .accept(MediaType.APPLICATION_JSON)
                .header("User-Agent", USER_AGENT)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /** 条目数组 → code→orgId 映射（code/orgId 任一缺失的条目跳过）。 */
    private static Map<String, String> parseOrgIdTable(List<Map<String, Object>> table) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (table == null) {
            return parsed;
        }
        for (Map<String, Object> entry : table) {
            Object code = entry.get("code");
            Object orgId = entry.get("orgId");
            if (code instanceof String codeText
                    && !codeText.isBlank()
                    && orgId instanceof String orgIdText
                    && !orgIdText.isBlank()) {
                parsed.putIfAbsent(codeText, orgIdText);
            }
        }
        return parsed;
    }
}
