package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FeedFetcher;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 按 {@code adapter_type} 路由的取数门面（M13 T103，方案 §4.3）：rss → {@link RssFeedFetcher}、json_api → {@link
 * JsonApiFeedFetcher}、preset → 按 {@code adapter_ref} 从容器取 {@link PresetFeedAdapter} bean（T106
 * SinaZhiboAdapter）；html_template 预留值直拒（页面新增向导不开放）。
 *
 * <p>{@code @Primary}：容器内有三个 FeedFetcher 实现（rss/json/路由），摄取服务按端口注入时以路由门面为唯一权威。
 */
@Component
@Primary
public class RoutingFeedFetcher implements FeedFetcher {

    private final RssFeedFetcher rssFetcher;
    private final JsonApiFeedFetcher jsonFetcher;
    private final ApplicationContext applicationContext;

    public RoutingFeedFetcher(
            RssFeedFetcher rssFetcher,
            JsonApiFeedFetcher jsonFetcher,
            ApplicationContext applicationContext) {
        this.rssFetcher = rssFetcher;
        this.jsonFetcher = jsonFetcher;
        this.applicationContext = applicationContext;
    }

    @Override
    public FetchResult fetch(InfoSource source, FetchContext context) {
        return resolve(source).fetch(source, context);
    }

    /** 通道解析（包内可见供路由单测）。 */
    FeedFetcher resolve(InfoSource source) {
        return switch (source.getAdapterType()) {
            case RSS -> rssFetcher;
            case JSON_API -> jsonFetcher;
            case PRESET -> presetAdapter(source.getAdapterRef());
            case HTML_TEMPLATE -> throw new FeedFetchException(
                    "HTML_TEMPLATE 通道预留未开放: " + source.getSourceCode());
        };
    }

    private PresetFeedAdapter presetAdapter(String adapterRef) {
        try {
            return applicationContext.getBean(adapterRef, PresetFeedAdapter.class);
        } catch (RuntimeException e) {
            throw new FeedFetchException(
                    "预置适配器 bean 不存在或类型不符: " + adapterRef + " (" + e.getMessage() + ")", e);
        }
    }
}
