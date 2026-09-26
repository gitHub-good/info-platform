package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 工信部政策文件预置适配器（M17 T140，REQ-20260926-14 拍板一 #1，bean 名 {@code miitPolicyAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：REQ 记路径 {@code /zwgk/zcwj/index.html} 实测为 2KB JS 跳转壳（→ {@code
 * /zwgk/zcwj/wjfb/index.html} → {@code /search/wjfb.html?...&category=51}），列表正文客户端渲染——
 * 现行唯一服务端数据端点为站点公开检索 API {@code /search-front-server/api/search/info}（search.js 页面自身调用， 参数
 * websiteid=110000000000000&amp;searchid=51 = 政策文件·文件发布）。响应 {@code
 * data.searchResult.dataResults[].groupData[].data}（实测 groupData 均 1 条）， 字段 {@code title}/{@code
 * url}（站内相对链接）/{@code deploytime}（Unix 毫秒字符串）；列表混排政策解读跨栏条目（{@code /zwgk/zcjd/}）——按栏内内容保留（沿发改委解读先例）。
 *
 * <p>增量口径：externalId = 详情 URL 的 {@code art_{uuid}} 段（uuid 本体）；cursorType=NONE——实测检索序非严格时间序
 * （deploytime 1790038536069 先于 1790038626691），TIME 游标「遇已见止」会漏新条目，重复轮由 (source_id, external_id)
 * 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：miit.gov.cn robots 404 → 按 RFC 9309「无 robots 文件 = 无限制」；历史 WAF
 * 风险（普查预告）实测未触发（预检 200）， 单源退避降级兜底。频控 60min（REQ 官方频段上限，日级源礼貌抓取）。
 */
@Component(MiitPolicyAdapter.BEAN_NAME)
public class MiitPolicyAdapter implements PresetFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog MIIT_POLICY）。 */
    public static final String BEAN_NAME = "miitPolicyAdapter";

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 详情 URL：/…/art/{uuid}.html（uuid 32 位十六进制作 externalId）。 */
    private static final Pattern ART_UUID = Pattern.compile("art_([0-9a-fA-F]{32})\\.html$");

    /** 详情相对链接 → 绝对直链前缀（实测 url 均为站内根相对路径）。 */
    private static final String SITE_BASE = "https://www.miit.gov.cn";

    /** 礼貌抓取浏览器 UA（与既有预置源一致口径）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    private final RestClient restClient;

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public MiitPolicyAdapter(RestClient.Builder builder) {
        this.restClient = builder.requestFactory(requestFactory()).build();
    }

    /** 全参构造（单测注入受控 RestClient）。 */
    MiitPolicyAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    @Override
    public FetchResult fetch(InfoSource source, FetchContext context) {
        return new FetchResult(parse(httpGet(source), source), false);
    }

    private String httpGet(InfoSource source) {
        String url = source.getEndpoint();
        try {
            String body =
                    restClient
                            .get()
                            .uri(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://www.miit.gov.cn/")
                            .retrieve()
                            .body(String.class);
            if (body == null || body.isBlank()) {
                throw new FeedFetchException("工信部政策文件空响应 " + source.getSourceCode() + " " + url);
            }
            return body;
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    "工信部政策文件取数失败 " + source.getSourceCode() + " " + url + ": " + e.getMessage(), e);
        }
    }

    /** 解析检索 API 响应（包内可见，fixture 单测直调零外呼）：dataResults×groupData 摊平为条目。 */
    List<RawFeedItem> parse(String body, InfoSource source) {
        JsonNode dataResults;
        try {
            dataResults =
                    MAPPER.readTree(body).path("data").path("searchResult").path("dataResults");
        } catch (Exception e) {
            throw new FeedFetchException(
                    "工信部政策文件 JSON 解析失败 " + source.getSourceCode() + ": " + e.getMessage(), e);
        }
        if (!dataResults.isArray()) {
            throw new FeedFetchException(
                    "工信部政策文件结构漂移（data.searchResult.dataResults 缺失）: " + source.getSourceCode());
        }
        List<RawFeedItem> items = new ArrayList<>();
        for (JsonNode dataResult : dataResults) {
            for (JsonNode groupData : dataResult.path("groupData")) {
                RawFeedItem item = toItem(groupData.path("data"), source);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return AbstractHtmlListFeedAdapter.dedupeByExternalId(items);
    }

    /** 单条转换：title + 相对链接绝对化 + art uuid 作 externalId + deploytime 毫秒墙钟。 */
    private static RawFeedItem toItem(JsonNode data, InfoSource source) {
        String title = AbstractHtmlListFeedAdapter.cleanTitle(data.path("title").asText(null));
        String relativeUrl = data.path("url").asText(null);
        if (title == null || relativeUrl == null) {
            return null;
        }
        Matcher uuid = ART_UUID.matcher(relativeUrl);
        if (!uuid.find()) {
            return null;
        }
        String deployTime = data.path("deploytime").asText(null);
        Instant publishedAt = null;
        if (deployTime != null && !deployTime.isBlank()) {
            try {
                publishedAt = Instant.ofEpochMilli(Long.parseLong(deployTime.trim()));
            } catch (NumberFormatException ignore) {
                // 墙钟不可解析回落抓取时刻（归摄取层），不致命
            }
        }
        return new RawFeedItem(
                uuid.group(1),
                title,
                null,
                SITE_BASE + relativeUrl,
                "工业和信息化部",
                publishedAt,
                source.getConfig().effectiveCursorType() == CursorType.TIME && publishedAt != null
                        ? publishedAt.toString()
                        : null);
    }
}
