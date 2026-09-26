package com.info.platform.application.feed;

import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FeedItemRepository.LibraryFilter;
import com.info.platform.domain.feed.FeedItemRepository.LibraryRow;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 统一资讯流查询服务（M13 T104，方案 §4.5 GET /api/v1/news-items；T160 增资讯库读模型）。
 *
 * <p>默认排除软删源条目（join 语义在仓储层）；条目视图补源展示名（sourceCode/sourceName，页面渲染用）； 游标模式 nextBeforeId = 末条
 * id（满页才有续页信号）。T160（M19 V2.1）：读模型升级为 news_item LEFT JOIN news_analysis 的 资讯库行（l0/l1 归类产物 + 近重复主条
 * url），页码模式承接 q/l0/l1 组合过滤（{@link LibraryFilter}，全 AND 语义）。
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

    /** 游标模式（id DESC，beforeId 续取；nextBeforeId=null 表示末页）。条目含 analysis join 字段（增量追加）。 */
    public NewsItemsCursorView listCursor(Long sourceId, Long beforeId, int limit) {
        List<LibraryRow> rows = itemRepository.findLatest(sourceId, beforeId, limit);
        Map<Long, InfoSource> sources = sourceDisplayMap();
        Long nextBeforeId = rows.size() == limit ? rows.get(rows.size() - 1).item().id() : null;
        return new NewsItemsCursorView(
                rows.stream().map(row -> toView(row, sources)).toList(), nextBeforeId);
    }

    /** 页码模式（M9 PageQuery 模式：total + page/size 回显；filter 组合过滤全 AND）。 */
    public NewsItemsPagedView listPaged(LibraryFilter filter, int page, int size) {
        List<LibraryRow> rows = itemRepository.findPage(filter, page, size);
        long total = itemRepository.countByFilter(filter);
        Map<Long, InfoSource> sources = sourceDisplayMap();
        return new NewsItemsPagedView(
                rows.stream().map(row -> toView(row, sources)).toList(), total, page, size);
    }

    private Map<Long, InfoSource> sourceDisplayMap() {
        return infoSourceRepository.findAll().stream()
                .collect(Collectors.toMap(InfoSource::getId, Function.identity()));
    }

    private static NewsItemView toView(LibraryRow row, Map<Long, InfoSource> sources) {
        FeedItem item = row.item();
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
                item.fetchedAt().toString(),
                row.l0Result().name(),
                row.l0Detail(),
                row.mainCategory(),
                row.confidence(),
                row.lowConfidence(),
                row.nearDupMasterId(),
                row.nearDupMasterUrl());
    }
}
