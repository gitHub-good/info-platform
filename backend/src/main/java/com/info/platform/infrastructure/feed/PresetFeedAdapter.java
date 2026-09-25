package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.FeedFetcher;

/**
 * 预置适配通道（M13 T103，方案 §4.3）：Spring bean 实现本接口，{@code info_source.adapter_ref} = bean 名；
 * 解析/翻页/richtext 清洗全在代码内（M13 仅 SinaZhiboAdapter，T106 落地）。 路由经 {@code RoutingFeedFetcher} 按 bean
 * 名分发。
 */
public interface PresetFeedAdapter extends FeedFetcher {}
