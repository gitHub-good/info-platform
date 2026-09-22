package com.info.platform.infrastructure.ai;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 调用留痕写入器（T30）：封装 {@link LlmCallLogRepository#save}，留痕失败不阻断调用主链路。
 *
 * <p>{@code LlmGatewayImpl} 每次.chat 落一行留痕（缓存命中/成功/失败/预算拒绝四态）。 留痕是可观测性旁路——DB 抖动不应把 LLM
 * 调用本身打挂（调用成功而留痕失败时，成本口径略欠记、宁可业务可用）， 故本类捕获全部落库异常记 ERROR（带 userId/scene/status
 * 上下文），不向上抛（对齐编码规范「不吞异常=至少记日志+上下文」的旁路场景豁免）。
 */
@Component
public class LlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogger.class);

    private final LlmCallLogRepository repository;

    public LlmCallLogger(LlmCallLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 落一行留痕（已定型实体）；失败记 ERROR 不抛。
     *
     * @param entry 经 markCacheHit/markSuccess/markFailed/markRejected 定型的留痕
     */
    public void record(LlmCallLog entry) {
        try {
            repository.save(entry);
        } catch (Exception e) {
            log.error(
                    "LLM 调用留痕落库失败 userId={} scene={} status={}",
                    entry.getUserId(),
                    entry.getSceneKey(),
                    entry.getStatus(),
                    e);
        }
    }
}
