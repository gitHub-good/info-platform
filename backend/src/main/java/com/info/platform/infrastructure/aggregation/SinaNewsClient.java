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
 * 新浪滚动新闻 HTTP 客户端（T06 新闻源）。
 *
 * <p>封装 GET {@code feed.mix.sina.com.cn/api/roll/get}：取财经滚动新闻<b>全市场流</b>（按 lid 分类翻页）， 不传个股参数。 与
 * {@link EastMoneyAnnounceClient}（公告）同属列表型客户端，但响应结构不同（新闻返回 {@code result.data[]} 数组，公告返回 {@code
 * data.list[]}）。
 *
 * <p>响应结构（2026-09-21 curl 实测，财经分类 lid=2510）：
 *
 * <pre>{@code
 * {"result":{
 *   "status":{"code":0,"msg":"succ"},
 *   "timestamp":"Mon Sep 21 12:25:23 +0800 2026",
 *   "total":51634,"start":...,"end":...,"lid":2510,
 *   "data":[{"docid":"...","title":"...","stitle":"","ctime":"1748275048",
 *           "intro":"...","summary":"...","wapsummary":"...",
 *           "url":"https://news.sina.com.cn/...","media_name":"...","keywords":"...",
 *           "author":"", ...}]}}
 * }</pre>
 *
 * <p>导航 {@code root.result.data[]} 为原始新闻数组。<b>响应路径偏差</b>：Spike-1 §6.5 载「{@code data} 数组为新闻」，实测为
 * {@code result.data[]}（多一层 {@code result}，与公告源 {@code data.list[]}、财务源 {@code result.data[]}
 * 均不同）——故建议 Spike-1 §6.5 更新响应路径为 {@code result.data}。
 *
 * <p><b>字段偏差</b>：Spike-1 §4.5 列 {@code ctime} 为发布时间（to_iso_date 映射），实测 {@code ctime} 为 <b>Unix
 * 秒级时间戳字符串</b>（如 {@code "1748275048"}），非 {@code yyyy-MM-dd HH:mm:ss}， {@link FieldMapper} 的 {@code
 * to_iso_date} 无法直接解析——由 {@link NewsSourceAdapter#doFetch} 内 epoch→Asia/Shanghai datetime
 * 预处理回填后映射。其余字段 docid/title/intro/url/media_name/keywords 实测与 Spike-1 §4.5 一致。
 *
 * <p><b>不支持个股过滤</b>：2026-09-21 curl 实测，{@code stock=600519} 与 {@code k=贵州茅台} 参数均被 忽略，返回的全是 lid
 * 分类下的全市场新闻（与标的无关）——与 Spike-1 §6.5「全市场流、个股关联靠标题关键词 匹配」预判一致。故本客户端仅取全市场流，个股关联由 {@link
 * NewsSourceAdapter} 在 {@code doFetch} 内 关键词过滤。
 *
 * <p>软限频/防封：新浪无 token、按 IP 软限，请求需带 {@code User-Agent}（Mozilla 浏览器）+ {@code Referer} （{@code
 * https://finance.sina.com.cn}），否则可能 403（Spike-1 §8 新浪需 Referer 头）。 超时不在此设—— 由 {@link
 * com.info.platform.infrastructure.common.ResilienceRunner}（2s 重试 0，对齐技术方案 §4.3 流程 1 新闻）兜底。 HTTP
 * 异常直接抛出，由模板层降级。
 *
 * <p><b>lid 偏差</b>：Spike-1 §6.5 未给具体 lid 值（仅示例「港股 2509」等）；2026-09-21 curl 实测 lid=1685~1689 均返回
 * {@code code:11「列表和页面没有经过注册」}已失效，lid=2510~2518 可用（财经滚动分类）。 默认 lid=2510，可经运行时参数 {@code
 * datasource.NEWS.params.newsLid} 配置（页面可改，ADR-0032）。建议 Spike-1 §6.5 补充 lid=2510 实测可用、 标注 1685~1689
 * 失效。
 */
@Component
public class SinaNewsClient {

    private static final Logger log = LoggerFactory.getLogger(SinaNewsClient.class);

    private static final String DEFAULT_NEWS_URL =
            DataSourceDefaults.paramString(SourceCode.NEWS, "newsUrl");

    /** 新浪滚动新闻 API 契约常量：pageid=153 为滚动新闻页面 ID（实测固定）。 */
    private static final int DEFAULT_PAGE_ID =
            DataSourceDefaults.paramInt(SourceCode.NEWS, "newsPageId", 153);

    /** 财经滚动分类 lid（2026-09-21 curl 实测可用；1685~1689 已失效）。 */
    private static final int DEFAULT_LID =
            DataSourceDefaults.paramInt(SourceCode.NEWS, "newsLid", 2510);

    private static final int DEFAULT_PAGE_SIZE =
            DataSourceDefaults.paramInt(SourceCode.NEWS, "newsPageSize", 20);

    private static final int PAGE_INDEX_FIRST = 1;

    /** 新浪软限频要求的来源页（无 token，靠 Referer 标识来源，防 403）。 */
    private static final String DEFAULT_REFERER =
            DataSourceDefaults.paramString(SourceCode.NEWS, "newsReferer");

    /** 浏览器 UA（新浪对裸 curl UA 易封，实测带此 UA 返回 200）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    private final RestClient restClient;
    private final String newsUrl;
    private final int pageId;
    private final int lid;
    private final int pageSize;
    private final String referer;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落构造期缺省。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    /** Spring 装配构造（ADR-0032）：回落值取 {@link DataSourceDefaults} 代码内置缺省（原 yml adapter 段迁移）。 */
    @Autowired
    public SinaNewsClient(RestClient.Builder restClientBuilder) {
        this(
                restClientBuilder,
                DEFAULT_NEWS_URL,
                DEFAULT_PAGE_ID,
                DEFAULT_LID,
                DEFAULT_PAGE_SIZE,
                DEFAULT_REFERER);
    }

    /** 全参构造（纯构造单测指定回落值）。 */
    public SinaNewsClient(
            RestClient.Builder restClientBuilder,
            String newsUrl,
            int pageId,
            int lid,
            int pageSize,
            String referer) {
        this.restClient = restClientBuilder.build();
        this.newsUrl = newsUrl;
        this.pageId = pageId;
        this.lid = lid;
        this.pageSize = pageSize;
        this.referer = referer;
    }

    /**
     * 取财经滚动新闻全市场流（原始 {@code result.data[]}，未过滤个股）。
     *
     * @return 原始新闻列表；{@code result.data} 为空/null 时返回 {@link Optional#empty()}（→ MISSING）
     */
    public Optional<List<Map<String, Object>>> fetchRollNews() {
        String newsUrl = RuntimeParams.of(configCenter, SourceCode.NEWS, "newsUrl", this.newsUrl);
        int pageId = RuntimeParams.intOf(configCenter, SourceCode.NEWS, "newsPageId", this.pageId);
        int lid = RuntimeParams.intOf(configCenter, SourceCode.NEWS, "newsLid", this.lid);
        int pageSize =
                RuntimeParams.intOf(configCenter, SourceCode.NEWS, "newsPageSize", this.pageSize);
        String referer =
                RuntimeParams.of(configCenter, SourceCode.NEWS, "newsReferer", this.referer);
        String url = buildUrl(newsUrl, pageId, lid, pageSize);
        log.debug("新浪新闻请求 lid={} pageSize={}", lid, pageSize);
        Map<String, Object> root =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", referer)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return extractList(root);
    }

    /** 导航 {@code root.result.data[]}；任一层缺失/空数组返回 {@link Optional#empty()}（→ MISSING）。 */
    @SuppressWarnings("unchecked")
    private static Optional<List<Map<String, Object>>> extractList(Map<String, Object> root) {
        if (root == null) {
            return Optional.empty();
        }
        Object result = root.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return Optional.empty();
        }
        Object data = resultMap.get("data");
        if (!(data instanceof List<?> items) || items.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> news = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                news.add((Map<String, Object>) map);
            }
        }
        return news.isEmpty() ? Optional.empty() : Optional.of(news);
    }

    private String buildUrl(String newsUrl, int pageId, int lid, int pageSize) {
        return UriComponentsBuilder.fromUriString(newsUrl)
                .queryParam("pageid", pageId)
                .queryParam("lid", lid)
                .queryParam("num", pageSize)
                .queryParam("page", PAGE_INDEX_FIRST)
                .build()
                .toUriString();
    }
}
