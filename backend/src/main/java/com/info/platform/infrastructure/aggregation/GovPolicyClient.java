package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 国务院 {@code gov.cn/zhengce} 政策库 HTTP 客户端（T07 政策源）。
 *
 * <p>6 类源中<b>唯一无统一 API</b> 的源：gov.cn 政策 RSS 已停用（Spike-1 §8 🔴 确认），官方仅提供 HTML 列表页。 故本客户端 GET {@code
 * https://www.gov.cn/zhengce/} 列表页 HTML，用 {@link Jsoup} 解析为政策条目（标题/发布日期/详情 URL），交 {@link
 * PolicySourceAdapter} 做发文单位切分 + 行业关联过滤 + 字段映射。 与 {@link SinaNewsClient}（新闻 JSON）同属列表型客户端，但响应是
 * HTML（非 JSON），解析路径不同。
 *
 * <p><b>HTML 结构</b>（2026-09-21 curl 实测，HTTP 200 / 40KB）：
 *
 * <pre>{@code
 * <div class="item item03">            <!-- 最新政策节 -->
 *   <div class="list fl">
 *     <ul>
 *       <li>
 *         <a href="./202609/content_7081587.htm" target="_blank">
 *           中共中央办公厅 国务院办公厅印发《关于分类推进高校改革的意见》
 *         </a>
 *         <span>2026-09-20</span>
 *       </li>
 *       <li>
 *         <a href="https://www.gov.cn/zhengce/content/202609/content_7081441.htm" ...>
 *           国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知
 *         </a>
 *         <span>2026-09-18</span>
 *       </li>
 *       ...约 9 条...
 *     </ul>
 *     <div class="more"><a href="./zuixin/">更多&gt;&gt;</a></div>
 *   </div>
 * </div>
 * }</pre>
 *
 * <p>选择器 {@link #LIST_SELECTOR}（{@code div.list.fl > ul > li}）实测<b>唯一</b>命中「最新政策」节列表（全页仅一处 {@code
 * div class="list fl"}）。每条 {@code <li>} 取：{@code <a>} 文本作标题、{@code a.absUrl("href")} 作详情链接、 {@code
 * <span>} 文本作发布日期（{@code yyyy-MM-dd}）。详情链接有相对（{@code ./202609/content_XXX.htm}）与绝对 （完整
 * URL）两种写法，Jsoup {@code absUrl} 以 baseUri 解析为绝对 URL。
 *
 * <p><b>无行业分类标签</b>（实测确认）：政策列表项<b>不含</b>行业/板块分类标签，仅标题 + 日期。 政策为宏观流，关联 {@code subject.industry}
 * 靠标题关键词字典命中（Spike-1 §6.6 + §4.6），由 {@link PolicySourceAdapter#isIndustryRelevant} 完成。无关联 →
 * MISSING（不阻断详情页政策分区，技术方案 §4.3 流程 1 政策源 missing）。
 *
 * <p><b>charset 偏差</b>（关键）：gov.cn 响应头 {@code Content-Type: text/html} <b>不含 charset</b>（2026-09-21
 * curl -I 实测确认）， 仅页面 {@code <meta charset=utf-8>} 声明。若用 RestClient {@code .body(String.class)}，
 * StringHttpMessageConverter 默认 ISO-8859-1 解码 → 中文全乱码。 故本客户端读 {@code byte[]}，交 Jsoup {@link
 * Jsoup#parse(java.io.InputStream, String, String) parse(InputStream, null, baseUri)}：charsetName 传
 * {@code null} 让 Jsoup 按 meta 自动检测为 utf-8（meta 缺失时默认 utf-8）。<b>此为 gov.cn 实测必要处理，文档/JSON 源无此问题</b>。
 *
 * <p><b>最近 N 条</b>：列表页固定展示约 9 条最近政策（最新一版国务院/国办文件），「更多」走 {@code /zhengce/zuixin/}。 当前取列表页约 9 条已覆盖 V2
 * 种子（白酒/银行/互联网）行业关联；需更多条数时改配 {@code adapter.gov.policy-url} 指向 {@code zuixin/} 页。
 *
 * <p>软限频/防封：gov.cn 无 token、按 IP 软限，实测裸 curl 带浏览器 UA 即 200，仍带 {@code User-Agent} + {@code
 * Referer}（{@code https://www.gov.cn/}）保险。超时不在本客户端设——由 {@link
 * com.info.platform.infrastructure.common.ResilienceRunner}（2s 重试 0，对齐技术方案 §4.3 流程 1 政策 2s） 兜底。
 * HTTP/Jsoup 异常直接抛出，由模板层降级。
 */
@Component
public class GovPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(GovPolicyClient.class);

    private static final String DEFAULT_POLICY_URL = "https://www.gov.cn/zhengce/";

    /** 软限频来源页（无 token，靠 Referer 标识来源，保险防 403）。 */
    private static final String DEFAULT_REFERER = "https://www.gov.cn/";

    /** 浏览器 UA（gov.cn 实测裸 curl 带此 UA 返回 200，仍保留保险）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /**
     * 「最新政策」列表项选择器（2026-09-21 实测唯一命中）。全页仅一处 {@code div class="list fl"}， 其下 {@code > ul > li}
     * 为政策条目。gov.cn 改版可能导致此选择器失效——失效时 parsePolicies 返回空（→ MISSING， 不阻断），建议同步更新 Spike-1 §6.6。
     */
    static final String LIST_SELECTOR = "div.list.fl > ul > li";

    private final RestClient restClient;
    private final String policyUrl;
    private final String referer;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落 @Value yml 值。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    public GovPolicyClient(
            RestClient.Builder restClientBuilder,
            @Value("${adapter.gov.policy-url:" + DEFAULT_POLICY_URL + "}") String policyUrl,
            @Value("${adapter.gov.policy-referer:" + DEFAULT_REFERER + "}") String referer) {
        this.restClient = restClientBuilder.build();
        this.policyUrl = policyUrl;
        this.referer = referer;
    }

    /**
     * 取 gov.cn 政策列表页最近若干条政策（原始 title/url/pubDate，未做发文单位切分与行业关联）。
     *
     * @return 原始政策列表；HTML 为空/无列表项返回 {@link Optional#empty()}（→ MISSING）
     * @throws java.io.IOException Jsoup 读流解析失败（网络/编码异常，由调用方 doFetch 经弹性降级）
     */
    public Optional<List<Map<String, Object>>> fetchPolicies() throws java.io.IOException {
        String policyUrl =
                RuntimeParams.of(configCenter, SourceCode.POLICY, "policyUrl", this.policyUrl);
        String referer =
                RuntimeParams.of(configCenter, SourceCode.POLICY, "policyReferer", this.referer);
        log.debug("gov.cn 政策请求 url={}", policyUrl);
        byte[] body =
                restClient
                        .get()
                        .uri(policyUrl)
                        .accept(MediaType.TEXT_HTML)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", referer)
                        .retrieve()
                        // 读 byte[] 交 Jsoup 按 meta 自动检测 charset（gov.cn 响应头无 charset，见类 Javadoc）
                        .body(byte[].class);
        return parsePolicies(body, policyUrl);
    }

    /**
     * Jsoup 解析 HTML 字节为政策条目列表。charset 由 meta 自动检测（gov.cn meta 声明 utf-8）。
     *
     * <p>package-private + static：便于 {@link PolicySourceAdapterTest} 直接喂数据测解析逻辑（不依赖真实网络）， 同 {@link
     * NewsSourceAdapter#isRelevant} 的可测性策略。
     *
     * @param htmlBytes HTML 字节；null/空数组返回 {@link Optional#empty()}
     * @param baseUrl 详情链接解析的基准 URL（解析相对 href 为绝对 URL）
     * @throws java.io.IOException Jsoup 读流失败（ByteArrayInputStream 实际不产生真实 IO，签名要求声明）
     */
    static Optional<List<Map<String, Object>>> parsePolicies(byte[] htmlBytes, String baseUrl)
            throws java.io.IOException {
        if (htmlBytes == null || htmlBytes.length == 0) {
            return Optional.empty();
        }
        // charsetName=null → Jsoup 按 meta 自动检测，meta 缺失默认 utf-8（gov.cn meta 声明 utf-8）
        Document doc = Jsoup.parse(new ByteArrayInputStream(htmlBytes), null, baseUrl);
        Elements items = doc.select(LIST_SELECTOR);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> policies = new ArrayList<>(items.size());
        for (Element li : items) {
            Element a = li.selectFirst("a");
            if (a == null) {
                continue;
            }
            String title = a.text().trim();
            // absUrl 解析相对/绝对 href 为绝对 URL（相对 ./202609/... → base + 202609/...）
            String url = a.absUrl("href");
            if (title.isEmpty() || url == null || url.isEmpty()) {
                continue;
            }
            String pubDate = "";
            Element span = li.selectFirst("span");
            if (span != null) {
                pubDate = span.text().trim();
            }
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("title", title);
            policy.put("url", url);
            policy.put("pubDate", pubDate);
            policies.add(policy);
        }
        return policies.isEmpty() ? Optional.empty() : Optional.of(policies);
    }

    /**
     * 测试辅助：由已解码 HTML 字符串解析（用于不涉及 charset 检测路径的纯解析单测）。 生产路径走 {@link #parsePolicies(byte[],
     * String)}（byte[] + Jsoup 自动 charset）。 ByteArrayInputStream 不产生真实 IO 异常， 故此处把签名要求的 IOException
     * 包装为 {@link java.io.UncheckedIOException}（保留 cause，非吞异常），让直接单测无需声明 throws。
     */
    static Optional<List<Map<String, Object>>> parsePolicies(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        try {
            return parsePolicies(html.getBytes(StandardCharsets.UTF_8), baseUrl);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
