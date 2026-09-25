package com.info.platform.application.feed;

import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 统一资讯流查询服务（M13 T104，方案 §4.5 GET /api/v1/news-items）。
 *
 * <p>默认排除软删源条目（join 语义在仓储层）；条目视图补源展示名（sourceCode/sourceName，页面渲染用）； 游标模式 nextBeforeId =
 * 末条 id（满页才有续页信号）。
 */
@Service
public class NewsItemsQueryService {

    private final FeedItemRepository itemRepository;
    private final InfoSourceRepository infoSourceRepository;

    public NewsItemsQueryService(
            FeedItemRepository itemRepository, InfoSourceRepository infoSourceRepository) {
        this.itemRepository = itemRepository;
        this.infoSourceRepository = infoSourceRepository;
    }

    /** 游标模式（id DESC，beforeId 续取；nextBeforeId=null 表示末页）。 */
    public NewsItemsCursorView listCursor(Long sourceId, Long beforeId, int limit) {
        List<FeedItem> items = itemRepository.findLatest(sourceId, beforeId, limit);
        Map<Long, InfoSource> sources = sourceDisplayMap();
        Long nextBeforeId =
                items.size() == limit ? items.get(items.size() - 1).id() : null;
        return new NewsItemsCursorView(
                items.stream().map(item -> toView(item, sources)).toList(), nextBeforeId);
    }

    /** 页码模式（M9 PageQuery 模式：total + page/size 回显）。 */
    public NewsItemsPagedView listPaged(Long sourceId, int page, int size) {
        List<FeedItem> items = itemRepository.findPage(sourceId, page, size);
        long total = itemRepository.countByFilter(sourceId);
        Map<Long, InfoSource> sources = sourceDisplayMap();
        return new NewsItemsPagedView(
                items.stream().map(item -> toView(item, sources)).toList(), total, page, size);
    }

    private Map<Long, InfoSource> sourceDisplayMap() {
        return infoSourceRepository.findAll().stream()
                .collect(Collectors.toMap(InfoSource::getId, Function.identity()));
    }

    private static NewsItemView toView(FeedItem item, Map<Long, InfoSource> sources) {
        InfoSource source = sources.get(item.sourceId());
        return new NewsItemView(
                item.id(),
                item.sourceId(),
                source == null ? null : source.getSourceCode(),
                source == null ? null : source.getName(),
                item.title(),
                item.summary(),
                item.url(),
                item.author(),
                item.publishedAt().toString(),
                item.fetchedAt().toString());
    }
}
