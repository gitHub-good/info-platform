package com.info.platform.application.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 统一资讯流查询服务（M13 T104，方案 §4.5 GET /api/v1/news-items；T160 增资讯库读模型）。
 *
 * <p>默认排除软删源条目（join 语义在仓储层）；条目视图补源展示名（sourceCode/sourceName，页面渲染用）； 游标模式 nextBeforeId = 末条
 * id（满页才有续页信号）。T160（M19 V2.1）：读模型升级为 news_item LEFT JOIN news_analysis 的 资讯库行（l0/l1 归类产物 + 近重复主条
 * url），页码模式承接 q/l0/l1 组合过滤（{@link LibraryFilter}，全 AND 语义）。 V3.1：行视图增回联标的（matched_subjects
 * 库内直读解析——损坏容错空表，回联列非权威面不阻断读路径，PolicyService 同惯例）。
 */
@Service
public class NewsItemsQueryService {

    private static final Logger log = LoggerFactory.getLogger(NewsItemsQueryService.class);

    private final FeedItemRepository itemRepository;
    private final InfoSourceRepository infoSourceRepository;
    private final ObjectMapper objectMapper;

    public NewsItemsQueryService(
            FeedItemRepository itemRepository,
            InfoSourceRepository infoSourceRepository,
            ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.infoSourceRepository = infoSourceRepository;
        this.objectMapper = objectMapper;
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

    private NewsItemView toView(LibraryRow row, Map<Long, InfoSource> sources) {
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
                row.nearDupMasterUrl(),
                matchedSubjects(row));
    }

    /** matched_subjects JSON → 视图（库内值直读；损坏容错空表——回联列非权威面，不阻断读路径）。 */
    private List<MatchedSubjectView> matchedSubjects(LibraryRow row) {
        String json = row.matchedSubjectsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SubjectRef> refs =
                    objectMapper.readValue(
                            json,
                            objectMapper
                                    .getTypeFactory()
                                    .constructCollectionType(List.class, SubjectRef.class));
            return refs.stream()
                    .filter(ref -> ref.code() != null && !ref.code().isBlank())
                    .map(ref -> new MatchedSubjectView(ref.code(), ref.name(), ref.industry()))
                    .toList();
        } catch (Exception e) {
            log.warn("matched_subjects 解析失败 newsId={}: {}", row.item().id(), e.getMessage());
            return List.of();
        }
    }

    /** matched_subjects JSON 元素（[{code,name,industry}]——与 SubjectMatcher.MatchedSubject 同形）。 */
    private record SubjectRef(String code, String name, String industry) {}
}
