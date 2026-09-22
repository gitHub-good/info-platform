package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link LlmCallLogRepository} 端口的 SQLite/MyBatis-Plus 实现（T30）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（字典序即时间序），窗口查询按 {@code created_at >= start} 文本比较即可命中 {@code
 * idx_llm_call_log_created} 索引。追加型流水仅 INSERT，无 UPDATE 路径。
 */
@Repository
public class LlmCallLogRepositoryImpl implements LlmCallLogRepository {

    private final LlmCallLogMapper mapper;

    public LlmCallLogRepositoryImpl(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmCallLog save(LlmCallLog log) {
        LlmCallLogPO po = toPO(log);
        String now = Instant.now().toString();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return toEntity(po);
    }

    @Override
    public List<LlmCallLog> findCreatedSince(Instant start, int limit) {
        LambdaQueryWrapper<LlmCallLogPO> wrapper =
                new LambdaQueryWrapper<LlmCallLogPO>()
                        .ge(
                                start != null,
                                LlmCallLogPO::getCreatedAt,
                                start == null ? null : start.toString())
                        .orderByDesc(LlmCallLogPO::getId)
                        .last("LIMIT " + Math.max(0, limit));
        return toEntities(mapper.selectList(wrapper));
    }

    private List<LlmCallLog> toEntities(List<LlmCallLogPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(LlmCallLogRepositoryImpl::toEntity).toList();
    }

    private static LlmCallLog toEntity(LlmCallLogPO po) {
        return LlmCallLog.reconstruct(
                po.getId(),
                po.getUserId() == null ? 0L : po.getUserId(),
                po.getSceneKey(),
                po.getProvider(),
                po.getModel(),
                po.getStatus() == null ? null : LlmCallStatus.fromName(po.getStatus()),
                po.getCacheHit() != null && po.getCacheHit() == 1,
                po.getPromptTokens() == null ? 0 : po.getPromptTokens(),
                po.getCompletionTokens() == null ? 0 : po.getCompletionTokens(),
                po.getCostMicros() == null ? 0L : po.getCostMicros(),
                po.getDurationMillis() == null ? 0L : po.getDurationMillis(),
                po.getErrorMessage(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()));
    }

    private static LlmCallLogPO toPO(LlmCallLog log) {
        LlmCallLogPO po = new LlmCallLogPO();
        po.setId(log.getId());
        po.setUserId((int) log.getUserId());
        po.setProvider(log.getProviderKey());
        po.setModel(log.getModel());
        po.setSceneKey(log.getSceneKey());
        po.setStatus(log.getStatus() == null ? null : log.getStatus().persistentName());
        po.setCacheHit(log.isCacheHit() ? 1 : 0);
        po.setPromptTokens(log.getPromptTokens());
        po.setCompletionTokens(log.getCompletionTokens());
        po.setCostMicros((int) log.getCostMicros());
        po.setDurationMillis((int) log.getDurationMillis());
        po.setErrorMessage(log.getErrorMessage());
        return po;
    }
}
