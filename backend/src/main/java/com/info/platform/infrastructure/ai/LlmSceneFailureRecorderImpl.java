package com.info.platform.infrastructure.ai;

import com.info.platform.application.ai.LlmSceneFailureRecorder;
import com.info.platform.domain.ai.LlmCallLog;
import org.springframework.stereotype.Component;

/**
 * {@link LlmSceneFailureRecorder} 端口实现（M22 T190 随批）：定型一行 FAILED {@code llm_call_log}（userId=0 系统调用
 * / cost=0 / duration=0——非真实调用耗时）经 {@link LlmCallLogger} 落库；留痕失败不阻断（Logger 内部容错契约）。
 */
@Component
public class LlmSceneFailureRecorderImpl implements LlmSceneFailureRecorder {

    private final LlmCallLogger callLogger;

    public LlmSceneFailureRecorderImpl(LlmCallLogger callLogger) {
        this.callLogger = callLogger;
    }

    @Override
    public void record(String sceneKey, String errorMessage) {
        LlmCallLog entry = LlmCallLog.begin(0L, sceneKey == null ? "" : sceneKey);
        entry.markFailed(errorMessage, 0L);
        callLogger.record(entry);
    }
}
