package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * GovPolicyClient JSON 分支单测（M12 T93，REQ-20260925-09 / ADR-0037 决策 5）：{@code policyUrl} 指向 {@code
 * ZUIXINZHENGCE.json} 时的解析与连通口径 + HTML 分支兼容（页面手改回旧 URL 热回退）。
 *
 * <p>JSON 样本结构取 2026-09-22 curl 实测（方案 §1.2 实测 4 / 附录 C）：JSON 数组，字段 {@code TITLE / SUB_TITLE /
 * URL（绝对链）/ DOCRELPUBTIME（yyyy-MM-dd）}，按时间倒序，全量 1100 条（样本截取）。 输出须与 HTML 分支<b>同款 raw
 * 契约</b>（title/url/pubDate）——{@code PolicySourceAdapter} 行业过滤/单位切分零改动。
 */
class GovPolicyClientTest {

    private static final String JSON_URL = "https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json";

    private static final String HTML_URL = "https://www.gov.cn/zhengce/";

    private static final String REFERER = "https://www.gov.cn/";

    /** 实测样本结构（截取 3 条，字段全大写、URL 绝对链、DOCRELPUBTIME yyyy-MM-dd）。 */
    private static final String JSON_BODY =
            """
            [
              {"TITLE":"中共中央办公厅 国务院办公厅印发《关于分类推进高校改革的意见》",
               "SUB_TITLE":"","URL":"https://www.gov.cn/zhengce/content/202609/content_7081587.htm",
               "DOCRELPUBTIME":"2026-09-20"},
              {"TITLE":"国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知",
               "SUB_TITLE":"房车消费","URL":"https://www.gov.cn/zhengce/content/202609/content_7081441.htm",
               "DOCRELPUBTIME":"2026-09-18"},
              {"TITLE":"国务院关于促进白酒产业高质量发展的指导意见",
               "SUB_TITLE":"","URL":"https://www.gov.cn/zhengce/content/202609/content_7081400.htm",
               "DOCRELPUBTIME":"2026-09-17"}
            ]
            """;

    // ---- parseJsonPolicies · 解析单测（喂实测样本结构） ----

    @Test
    void parseJsonPolicies_mapsSampleStructureToRawContract() throws Exception {
        List<Map<String, Object>> entries =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(
                                JSON_BODY,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        List<Map<String, Object>>>() {});
        Optional<List<Map<String, Object>>> result = GovPolicyClient.parseJsonPolicies(entries, 30);

        assertThat(result).isPresent();
        List<Map<String, Object>> policies = result.get();
        assertThat(policies).hasSize(3);
        // 同款 raw 契约：TITLE→title、URL→url、DOCRELPUBTIME→pubDate（与 HTML 分支输出一致，adapter 零改动）
        assertThat(policies.get(0))
                .containsEntry("title", "中共中央办公厅 国务院办公厅印发《关于分类推进高校改革的意见》")
                .containsEntry(
                        "url", "https://www.gov.cn/zhengce/content/202609/content_7081587.htm")
                .containsEntry("pubDate", "2026-09-20");
        assertThat(policies.get(2)).containsEntry("pubDate", "2026-09-17");
    }

    @Test
    void parseJsonPolicies_truncatesToMaxItems() {
        Optional<List<Map<String, Object>>> result =
                GovPolicyClient.parseJsonPolicies(sampleEntries(5), 2);

        // 截取前 2 条（JSON 按时间倒序 → 前 N 条即最新 N 条）
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
    }

    @Test
    void parseJsonPolicies_skipsEntriesMissingTitleOrUrl_emptyWhenAllInvalid() {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> noTitle = new LinkedHashMap<>();
        noTitle.put("URL", "https://www.gov.cn/x");
        noTitle.put("DOCRELPUBTIME", "2026-09-20");
        entries.add(noTitle);
        Map<String, Object> noUrl = new LinkedHashMap<>();
        noUrl.put("TITLE", "无链接政策");
        entries.add(noUrl);

        assertThat(GovPolicyClient.parseJsonPolicies(entries, 30)).isEmpty();
        assertThat(GovPolicyClient.parseJsonPolicies(null, 30)).isEmpty();
        assertThat(GovPolicyClient.parseJsonPolicies(List.of(), 30)).isEmpty();
        // 改版防御：大写字段整体变更 → 全部跳过 → empty（→ MISSING 不阻断；HTML 分支保留页面级回退）
        assertThat(GovPolicyClient.parseJsonPolicies(List.of(Map.of("title", "小写字段")), 30))
                .isEmpty();
    }

    // ---- fetchPolicies · 连通口径两形态（MockRestServiceServer） ----

    @Test
    void fetchPolicies_jsonUrl_requestsJsonAndParsesWithTruncation() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GovPolicyClient client = new GovPolicyClient(builder, JSON_URL, 2, REFERER);

        server.expect(requestTo(JSON_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", containsString("Mozilla")))
                .andExpect(header("Referer", REFERER))
                .andRespond(withSuccess(JSON_BODY, MediaType.APPLICATION_JSON));

        Optional<List<Map<String, Object>>> result = client.fetchPolicies();
        server.verify();

        // policyMaxItems=2 截取前 2 条（30 条池扩容命中面的载体）
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0)).containsEntry("pubDate", "2026-09-20");
    }

    @Test
    void fetchPolicies_htmlUrl_keepsJsoupBranchForCompatibility() throws Exception {
        // 页面手改回 HTML URL（V20 回滚/热回退路径）→ 既有 Jsoup 分支照常解析
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GovPolicyClient client = new GovPolicyClient(builder, HTML_URL, 30, REFERER);
        String html =
                """
                <html><head><meta charset="utf-8"></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_7081587.htm">中共中央办公厅 国务院办公厅印发《意见》</a><span>2026-09-20</span></li>
                </ul></div></div>
                </body></html>
                """;

        server.expect(requestTo(HTML_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                MediaType.TEXT_HTML));

        Optional<List<Map<String, Object>>> result = client.fetchPolicies();
        server.verify();

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).containsEntry("pubDate", "2026-09-20");
        // HTML 分支相对链接解析为绝对 URL（baseUri）
        assertThat(result.get().get(0).get("url").toString())
                .isEqualTo("https://www.gov.cn/zhengce/202609/content_7081587.htm");
    }

    @Test
    void fetchPolicies_jsonUrl_structuralChange_yieldsEmptyNotThrow() throws Exception {
        // 改版风险（方案 §8）：JSON 结构变更（空数组/非预期字段）→ empty → MISSING 不阻断
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GovPolicyClient client = new GovPolicyClient(builder, JSON_URL, 30, REFERER);

        server.expect(requestTo(JSON_URL))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.fetchPolicies()).isEmpty();
        server.verify();
    }

    // ---- helpers ----

    /** 实测样本结构的内存构造（count 条，时间倒序）。 */
    private static List<Map<String, Object>> sampleEntries(int count) {
        List<Map<String, Object>> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("TITLE", "政策条目 " + (count - i));
            entry.put("SUB_TITLE", "");
            entry.put(
                    "URL",
                    "https://www.gov.cn/zhengce/content/202609/content_" + (7081587 - i) + ".htm");
            entry.put("DOCRELPUBTIME", "2026-09-" + String.format("%02d", 20 - i));
            entries.add(entry);
        }
        return entries;
    }
}
