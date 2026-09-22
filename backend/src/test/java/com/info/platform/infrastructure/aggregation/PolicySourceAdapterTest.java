package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceAdapterInfrastructureConfig;
import com.info.platform.infrastructure.common.SourceCache;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PolicySourceAdapter 单测（T07）：gov.cn 政策库 HTML 抓取 + 行业关联关键词过滤。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟 gov.cn 响应 HTML（结构 {@code div.list.fl>ul>li}，2026-09-21
 * curl 实测确认）， 覆盖：正常取数→行业关键词过滤→发文单位切分→逐条映射→OK（含 title/publishedAt/url/department/relatedIndustries，
 * <b>必带 UA+Referer</b>，<b>读 byte[] 防 charset 乱码</b>）/ 不同行业按各自热词命中 / 无关联→MISSING / 空 HTML→MISSING /
 * 无列表选择器→MISSING（模拟改版） / null 响应体→MISSING / HTTP 500→异常降级 MISSING。
 *
 * <p><b>行业关联匹配逻辑</b>（核心难点）单独单测 {@link PolicySourceAdapter#isIndustryRelevant}： 标题含行业热词命中 / 不含不命中 /
 * 行业 null（无字典）永不命中 / 未知行业无字典永不命中 / 标题 blank 不命中。 <b>发文单位切分</b> 单独单测 {@link
 * PolicySourceAdapter#extractDepartment}：关于/印发/转发各切分 / 无动词不切 / 动词在首位不切 / null/blank。
 *
 * <p><b>实测偏差</b>（vs Spike-1 §6.6/§4.6）：gov.cn 响应头 {@code Content-Type: text/html} 无 charset（仅 meta
 * 声明 utf-8）， 故 adapter 读 byte[] 交 Jsoup 按 meta 自动检测；政策列表<b>无行业分类标签</b>（Spike-1 §6.6
 * 已预判，实测确认），关联行业靠标题热词命中。 mock 响应按实测 HTML 结构构造（含 meta charset + 相对/绝对混合 href）。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换。不启 Flyway/DB。
 *
 * <p><b>charset 测试设计</b>：mock 响应以 UTF-8 字节数组提供（{@code html.getBytes(UTF_8)}）， 经 client 读 byte[] →
 * Jsoup meta 检测为 utf-8 正确解析中文（与生产路径一致；String 直读会因 ISO-8859-1 默认乱码）。
 */
class PolicySourceAdapterTest {

    private static final String POLICY_URL = "https://www.gov.cn/zhengce/";
    private static final String REFERER = "https://www.gov.cn/";

    private ExecutorService exec;
    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;
    private NoopCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(new ObjectMapper());
        runner = new ResilienceRunner(exec);
        breaker = new NoopCircuitBreaker();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void fetch_normalResponse_filtersByIndustryAndMapsItems() {
        // 白酒行业 subject（贵州茅台）：policy1 含「白酒」命中、policy2「房车消费」不命中、policy3「退役军人」不命中
        String html =
                """
                <!doctype html><html><head>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                <title>政策_中国政府网</title></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_7081587.htm" target="_blank">国务院办公厅关于促进白酒产业高质量发展的若干意见</a><span>2026-09-20</span></li>
                  <li><a href="https://www.gov.cn/zhengce/content/202609/content_7081441.htm" target="_blank">国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知</a><span>2026-09-18</span></li>
                  <li><a href="./202609/content_7081017.htm" target="_blank">中共中央办公厅 国务院办公厅印发《退役军人服务和保障规划》</a><span>2026-09-14</span></li>
                </ul><div class="more"><a href="./zuixin/" target="_blank">更多&gt;&gt;</a></div></div></div>
                </body></html>
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andExpect(method(HttpMethod.GET))
                                        // gov.cn 需带 UA + Referer（防封保险）
                                        .andExpect(
                                                req ->
                                                        assertThat(
                                                                        req.getHeaders()
                                                                                .getFirst(
                                                                                        "User-Agent"))
                                                                .contains("Mozilla"))
                                        .andExpect(
                                                req ->
                                                        assertThat(
                                                                        req.getHeaders()
                                                                                .getFirst(
                                                                                        "Referer"))
                                                                .isEqualTo(REFERER))
                                        .andExpect(
                                                req ->
                                                        assertThat(req.getHeaders().getAccept())
                                                                .anyMatch(
                                                                        mt ->
                                                                                mt.isCompatibleWith(
                                                                                        MediaType
                                                                                                .TEXT_HTML)))
                                        .andRespond(
                                                withSuccess(
                                                        html.getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.POLICY);
        assertThat(result.getSource()).isEqualTo("国务院政策");

        Object itemsObj = result.getData().get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        // policy2/policy3 不含白酒热词被过滤；仅 policy1 命中
        assertThat(items).hasSize(1);

        Map<String, Object> first = items.get(0);
        assertThat(first.get("title")).isEqualTo("国务院办公厅关于促进白酒产业高质量发展的若干意见");
        // span 日期 yyyy-MM-dd → to_iso_date → LocalDate.toString
        assertThat(first.get("publishedAt")).isEqualTo("2026-09-20");
        // 相对 href ./202609/... 经 base https://www.gov.cn/zhengce/ 解析为绝对 URL
        assertThat(first.get("url"))
                .isEqualTo("https://www.gov.cn/zhengce/202609/content_7081587.htm");
        // 发文单位：标题「关于」之前的前缀
        assertThat(first.get("department")).isEqualTo("国务院办公厅");
        // 命中行业回填
        assertThat(first.get("relatedIndustries")).isEqualTo("白酒");
    }

    @Test
    void fetch_differentIndustry_matchesBankKeywords() {
        // 银行业 subject（平安银行）：policy1「中国人民银行...金融监管」含「银行/金融/监管」命中；policy2「白酒」对银行行业不命中
        String html =
                """
                <!doctype html><html><head>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                <title>政策_中国政府网</title></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_7090001.htm" target="_blank">中国人民银行关于完善金融监管有关工作的通知</a><span>2026-09-19</span></li>
                  <li><a href="./202609/content_7081587.htm" target="_blank">国务院办公厅关于促进白酒产业高质量发展的若干意见</a><span>2026-09-20</span></li>
                </ul></div></div>
                </body></html>
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SZ000001", "平安银行", "银行"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(
                                                withSuccess(
                                                        html.getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        // 仅「中国人民银行...金融监管」命中银行业热词；「白酒」政策对银行行业不命中
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("relatedIndustries")).isEqualTo("银行");
        // 发文单位：中国人民银行（「关于」之前）
        assertThat(items.get(0).get("department")).isEqualTo("中国人民银行");
    }

    @Test
    void fetch_noHit_returnsMissing() {
        // 最近政策均不含白酒热词 → 无关联 → MISSING（不阻断，政策为宏观流命中率稀疏）
        String html =
                """
                <!doctype html><html><head><meta charset="utf-8"><title>政策</title></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_x.htm" target="_blank">国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知</a><span>2026-09-18</span></li>
                  <li><a href="./202609/content_y.htm" target="_blank">电力安全事故应急处置和调查处理条例</a><span>2026-09-04</span></li>
                </ul></div></div>
                </body></html>
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(
                                                withSuccess(
                                                        html.getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_emptyHtml_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(
                                                withSuccess(
                                                        "  ".getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_htmlWithoutListSelector_returnsMissing() {
        // gov.cn 改版导致选择器失效（无 div.list.fl 列表）→ 无条目 → MISSING（不阻断，建议同步更新 Spike-1 §6.6）
        String html =
                """
                <!doctype html><html><head><meta charset="utf-8"><title>政策</title></head><body>
                <div class="some-other-layout"><ul><li><a href="/x.htm">某政策</a></li></ul></div>
                </body></html>
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(
                                                withSuccess(
                                                        html.getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_http500_throwsAndDegradesToMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL)).andRespond(withServerError()));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_emptyBody_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", "白酒"),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(withSuccess(new byte[0], MediaType.TEXT_HTML)));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_nullIndustry_neverMatches_returnsMissing() {
        // 行业为 null（无字典）→ 关键词集空 → 永不命中 → MISSING（不阻断）
        String html =
                """
                <!doctype html><html><head><meta charset="utf-8"><title>政策</title></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_x.htm" target="_blank">国务院办公厅关于促进白酒产业发展的意见</a><span>2026-09-20</span></li>
                </ul></div></div>
                </body></html>
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithIndustry("SH600519", "贵州茅台", null),
                        server ->
                                server.expect(requestTo(POLICY_URL))
                                        .andRespond(
                                                withSuccess(
                                                        html.getBytes(StandardCharsets.UTF_8),
                                                        MediaType.TEXT_HTML)));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    // ---- parsePolicies 直接单测（不依赖网络，验 Jsoup 选择器/abs:href 解析）----

    @Test
    void parsePolicies_realisticHtml_resolvesAbsoluteAndRelativeUrls() {
        String html =
                """
                <html><head><meta charset="utf-8"></head><body>
                <div class="item item03"><div class="list fl"><ul>
                  <li><a href="./202609/content_7081587.htm" target="_blank">国务院办公厅关于促进白酒产业高质量发展的若干意见</a><span>2026-09-20</span></li>
                  <li><a href="https://www.gov.cn/zhengce/content/202609/content_7081441.htm" target="_blank">国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知</a><span>2026-09-18</span></li>
                  <li><a href="https://www.gov.cn/zhengce/content/202609/content_7081017.htm" target="_blank">无日期条目</a></li>
                </ul></div></div>
                </body></html>
                """;
        var parsed = GovPolicyClient.parsePolicies(html, POLICY_URL);
        assertThat(parsed).isPresent();
        List<Map<String, Object>> policies = parsed.get();
        assertThat(policies).hasSize(3);

        // 相对 href 经 base 解析为绝对
        assertThat(policies.get(0).get("url"))
                .isEqualTo("https://www.gov.cn/zhengce/202609/content_7081587.htm");
        assertThat(policies.get(0).get("title")).isEqualTo("国务院办公厅关于促进白酒产业高质量发展的若干意见");
        assertThat(policies.get(0).get("pubDate")).isEqualTo("2026-09-20");
        // 绝对 href 原样返回
        assertThat(policies.get(1).get("url"))
                .isEqualTo("https://www.gov.cn/zhengce/content/202609/content_7081441.htm");
        // 无 span 的条目 pubDate 为空串（仍保留条目，title/url 完整）
        assertThat(policies.get(2).get("pubDate")).isEqualTo("");
    }

    @Test
    void parsePolicies_emptyHtml_returnsEmpty() {
        assertThat(GovPolicyClient.parsePolicies("", POLICY_URL)).isEmpty();
        assertThat(GovPolicyClient.parsePolicies((String) null, POLICY_URL)).isEmpty();
    }

    // ---- 行业关联关键词匹配逻辑（核心，独立单测 isIndustryRelevant）----

    @Test
    void isIndustryRelevant_keywordHit_variants() {
        Set<String> baijiu = Set.of("白酒", "酒类", "烟酒");
        // 标题含行业热词
        assertThat(PolicySourceAdapter.isIndustryRelevant("国务院关于促进白酒产业发展的意见", baijiu)).isTrue();
        assertThat(PolicySourceAdapter.isIndustryRelevant("商务部关于规范酒类市场的通知", baijiu)).isTrue();
        // 互联网行业热词
        Set<String> internet = Set.of("互联网", "平台经济", "数据安全");
        assertThat(PolicySourceAdapter.isIndustryRelevant("国务院关于促进平台经济规范发展的意见", internet)).isTrue();
    }

    @Test
    void isIndustryRelevant_noHit_variants() {
        Set<String> baijiu = Set.of("白酒", "酒类", "烟酒");
        // 标题不含任一热词
        assertThat(PolicySourceAdapter.isIndustryRelevant("国务院办公厅关于促进房车消费的若干措施", baijiu)).isFalse();
        // 标题 null/blank
        assertThat(PolicySourceAdapter.isIndustryRelevant(null, baijiu)).isFalse();
        assertThat(PolicySourceAdapter.isIndustryRelevant("", baijiu)).isFalse();
        assertThat(PolicySourceAdapter.isIndustryRelevant("  ", baijiu)).isFalse();
        // 关键词集空（行业不在字典）
        assertThat(PolicySourceAdapter.isIndustryRelevant("国务院关于白酒产业的意见", Set.of())).isFalse();
        assertThat(PolicySourceAdapter.isIndustryRelevant("国务院关于白酒产业的意见", null)).isFalse();
    }

    // ---- 发文单位切分逻辑（独立单测 extractDepartment）----

    @Test
    void extractDepartment_verbPrefixes() {
        // 「关于」前缀
        assertThat(PolicySourceAdapter.extractDepartment("国务院办公厅关于促进白酒产业发展的若干意见"))
                .isEqualTo("国务院办公厅");
        // 「印发」前缀（多个发文单位）
        assertThat(PolicySourceAdapter.extractDepartment("中共中央办公厅 国务院办公厅印发《退役军人服务和保障规划》"))
                .isEqualTo("中共中央办公厅 国务院办公厅");
        // 「转发」前缀（早于后续「关于」切分）
        assertThat(PolicySourceAdapter.extractDepartment("国务院办公厅转发文化和旅游部等部门《关于促进房车消费的若干措施》的通知"))
                .isEqualTo("国务院办公厅");
        // 「发布」前缀
        assertThat(PolicySourceAdapter.extractDepartment("中国人民银行发布金融统计数据")).isEqualTo("中国人民银行");
    }

    @Test
    void extractDepartment_noVerbOrAtStart_returnsEmpty() {
        // 无承接动词（条例/办法是政策名后缀，不切分）→ 不产出 department
        assertThat(PolicySourceAdapter.extractDepartment("电力安全事故应急处置和调查处理条例")).isEqualTo("");
        assertThat(PolicySourceAdapter.extractDepartment("市场监督管理所条例")).isEqualTo("");
        // 动词在首位（无单位前缀）→ 不产出
        assertThat(PolicySourceAdapter.extractDepartment("关于做好某工作的通知")).isEqualTo("");
        // null/blank
        assertThat(PolicySourceAdapter.extractDepartment(null)).isEqualTo("");
        assertThat(PolicySourceAdapter.extractDepartment("")).isEqualTo("");
    }


    @Configuration
    static class RestClientBuilderConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GovPolicyClient client = new GovPolicyClient(builder, POLICY_URL, REFERER);
        PolicySourceAdapter adapter =
                new PolicySourceAdapter(cache, fieldMapper, runner, breaker, client);
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    private static Subject subjectWithIndustry(String subjectCode, String name, String industry) {
        // 测试仅用 A 股标的（SH600519 白酒 / SZ000001 银行），eastmoney secid 用于关联派生
        String secid = subjectCode.startsWith("SZ") ? "0.000001" : "1.600519";
        return Subject.reconstruct(
                1L,
                SubjectCode.of(subjectCode),
                Market.A_SHARE,
                SubjectType.STOCK,
                name,
                Map.of("eastmoney", secid),
                industry,
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
