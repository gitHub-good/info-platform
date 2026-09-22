package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.ai.ReadingEventType;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link ReadingEventRepository} 端口的 SQLite/MyBatis-Plus 实现（T29）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（字典序即时间序），窗口查询按 {@code created_at >= start}
 * 文本比较即可命中索引。追加型流水仅 INSERT，无 UPDATE 路径。
 */
@Repository
public class ReadingEventRepositoryImpl implements ReadingEventRepository {

    private final ReadingEventMapper mapper;

    public ReadingEventRepositoryImpl(ReadingEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReadingEvent save(ReadingEvent event) {
        ReadingEventPO po = toPO(event);
        String now = Instant.now().toString();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return toEntity(po);
    }

    @Override
    public boolean existsSince(
            long userId, ReadingEventType contentType, String contentRef, Instant since) {
        LambdaQueryWrapper<ReadingEventPO> wrapper =
                new LambdaQueryWrapper<ReadingEventPO>()
                        .eq(ReadingEventPO::getUserId, (int) userId)
                        .eq(ReadingEventPO::getContentType, contentType.persistentName())
                        .eq(ReadingEventPO::getContentRef, contentRef)
                        .ge(
                                since != null,
                                ReadingEventPO::getCreatedAt,
                                since == null ? null : since.toString())
                        .last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<ReadingEvent> findByUserSince(long userId, Instant since, int limit) {
        LambdaQueryWrapper<ReadingEventPO> wrapper =
                new LambdaQueryWrapper<ReadingEventPO>()
                        .eq(ReadingEventPO::getUserId, (int) userId)
                        .ge(
                                since != null,
                                ReadingEventPO::getCreatedAt,
                                since == null ? null : since.toString())
                        .orderByDesc(ReadingEventPO::getId)
                        .last("LIMIT " + Math.max(0, limit));
        return toEntities(mapper.selectList(wrapper));
    }

    private static List<ReadingEvent> toEntities(List<ReadingEventPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(ReadingEventRepositoryImpl::toEntity).toList();
    }

    private static ReadingEvent toEntity(ReadingEventPO po) {
        return ReadingEvent.reconstruct(
                po.getId(),
                po.getUserId() == null ? 0L : po.getUserId(),
                po.getSubjectId(),
                po.getContentType() == null ? null : ReadingEventType.fromName(po.getContentType()),
                po.getContentRef(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()));
    }

    private static ReadingEventPO toPO(ReadingEvent event) {
        ReadingEventPO po = new ReadingEventPO();
        po.setId(event.getId());
        po.setUserId((int) event.getUserId());
        po.setSubjectId(event.getSubjectId());
        po.setContentType(event.getContentType().persistentName());
        po.setContentRef(event.getContentRef());
        return po;
    }
}
