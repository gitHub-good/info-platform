package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

/**
 * RoutingFeedFetcher 单测（T103，方案 §4.3 按 adapterType 路由）：rss/json_api 直路由、preset 按 adapter_ref 取
 * bean、 bean 缺失与 html_template 的防御性失败。
 */
class RoutingFeedFetcherTest {

    private final RssFeedFetcher rssFetcher = mock(RssFeedFetcher.class);
    private final JsonApiFeedFetcher jsonFetcher = mock(JsonApiFeedFetcher.class);
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);

    private RoutingFeedFetcher router() {
        return new RoutingFeedFetcher(rssFetcher, jsonFetcher, applicationContext);
    }

    private static InfoSource source(AdapterType type, String adapterRef) {
        return InfoSource.create(
                "t103_route",
                "路由源",
                "快讯",
                type,
                adapterRef,
                "https://example.com/x",
                SourceConfig.empty(),
                5,
                true,
                false);
    }

    @Test
    void route_rss_delegatesToRssEngine() {
        InfoSource source = source(AdapterType.RSS, null);
        when(rssFetcher.fetch(eq(source), any())).thenReturn(FetchResult.of(List.of()));

        FetchResult result = router().fetch(source, FetchContext.firstPage(null));

        assertThat(result.items()).isEmpty();
        verify(rssFetcher).fetch(eq(source), any());
        verify(jsonFetcher, never()).fetch(any(), any());
    }

    @Test
    void route_jsonApi_delegatesToJsonEngine() {
        InfoSource source = source(AdapterType.JSON_API, null);
        when(jsonFetcher.fetch(eq(source), any())).thenReturn(FetchResult.of(List.of()));

        router().fetch(source, FetchContext.firstPage(null));

        verify(jsonFetcher).fetch(eq(source), any());
    }

    @Test
    void route_preset_resolvesBeanByAdapterRef() {
        InfoSource source = source(AdapterType.PRESET, "sinaZhiboAdapter");
        PresetFeedAdapter adapter = mock(PresetFeedAdapter.class);
        when(applicationContext.getBean("sinaZhiboAdapter", PresetFeedAdapter.class))
                .thenReturn(adapter);
        when(adapter.fetch(eq(source), any())).thenReturn(FetchResult.of(List.of()));

        router().fetch(source, FetchContext.firstPage(null));

        verify(adapter).fetch(eq(source), any());
    }

    @Test
    void route_presetMissingBean_throwsFeedFetchExceptionWithContext() {
        InfoSource source = source(AdapterType.PRESET, "ghostAdapter");
        when(applicationContext.getBean("ghostAdapter", PresetFeedAdapter.class))
                .thenThrow(new NoSuchBeanDefinitionException("ghostAdapter"));

        assertThatThrownBy(() -> router().fetch(source, FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("ghostAdapter");
    }

    @Test
    void route_htmlTemplate_throwsFeedFetchException() {
        InfoSource source = source(AdapterType.HTML_TEMPLATE, null);

        assertThatThrownBy(() -> router().fetch(source, FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("HTML_TEMPLATE");
    }
}
