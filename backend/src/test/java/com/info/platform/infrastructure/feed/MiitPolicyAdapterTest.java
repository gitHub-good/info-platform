package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 工信部政策文件适配器单测（M17 T140，REQ-20260926-14 拍板一 #1）：真实截样本（2026-09-22 预检，search-front-server 检索 API
 * 现行结构）的条目映射（标题/相对链接绝对化/art uuid 作 externalId/deploytime 毫秒墙钟）+ 解读跨栏条目保留 + 结构漂移防御。零外呼。
 */
class MiitPolicyAdapterTest {

    private final MiitPolicyAdapter adapter = new MiitPolicyAdapter(RestClient.builder().build());

    private static com.info.platform.domain.feed.InfoSource miitSource() {
        return PresetSources.fromCode("miit_policy");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/miit-policy-sample.json");
    }

    @Test
    void parseList_mapsRealApiItems_titleUrlExternalIdAndEpochMillis() {
        List<RawFeedItem> items = adapter.parse(fixture(), miitSource());

        // fixture 首轮 5 条实测样本（通知 2 + 解读 2 + 通知 1，政策文件与解读混排保留）
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("c3b698fbaf0d42259de3e48d36a0d107");
        assertThat(newest.title()).contains("创新型产业集群建设管理办法");
        // 相对链接绝对化（详情直链与 externalId 分离：url 含 art 前缀，externalId 为 uuid 本体）
        assertThat(newest.url())
                .isEqualTo(
                        "https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2026/"
                                + "art_c3b698fbaf0d42259de3e48d36a0d107.html");
        assertThat(newest.author()).isEqualTo("工业和信息化部");
        // deploytime 毫秒墙钟 1790132090256 → 2026-09-23T02:54:50.256Z
        assertThat(newest.publishedAt()).isEqualTo(Instant.ofEpochMilli(1790132090256L));
        // 目录声明 cursorType=NONE（检索序非严格时间序，裁量见 ADR-0053）：不产出游标
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_keepsInterpretationCrossColumnItems() {
        List<RawFeedItem> items = adapter.parse(fixture(), miitSource());

        // 解读条目（/zwgk/zcjd/ 跨栏）与文件同列呈现，按栏内内容保留（沿发改委解读保留先例）
        RawFeedItem interpretation = items.get(2);
        assertThat(interpretation.url()).contains("/zwgk/zcjd/");
        assertThat(interpretation.title()).contains("解读");
        assertThat(interpretation.externalId()).isEqualTo("ced716be0a094e82937064b815ecba28");
    }

    @Test
    void parseList_skipsMalformedItems_andFallsBackWhenDeploytimeUnparsable() {
        // 边界：url 无 art uuid / title 缺失的条目跳过；deploytime 不可解析回落 null（摄取层补抓取时刻）
        String body =
                """
                {"code":"200","success":true,"data":{"searchResult":{"dataResults":[
                  {"groupData":[{"data":{"title":"无 uuid 链接条目","url":"/zwgk/no-uuid.html","deploytime":"1790132090256"}}]},
                  {"groupData":[{"data":{"url":"/zwgk/zcwj/wjfb/tz/art/11111111111111111111111111111111.html","deploytime":"1790132090256"}}]},
                  {"groupData":[{"data":{"title":"墙钟不可解析条目","url":"/zwgk/zcwj/wjfb/tz/art_22222222222222222222222222222222.html","deploytime":"not-a-number"}}]}
                ]}}}
                """;

        var items = adapter.parse(body, miitSource());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).externalId()).isEqualTo("22222222222222222222222222222222");
        assertThat(items.get(0).publishedAt()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parse("{\"code\":\"200\",\"data\":{}}", miitSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("miit_policy");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(miitSource().getEndpoint()))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        MiitPolicyAdapter httpAdapter = new MiitPolicyAdapter(builder.build());

        var result = httpAdapter.fetch(miitSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
