package com.info.platform.application.subscription;

import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.math.BigDecimal;

/**
 * 自选清单项视图（应用层返回值，供接口层包装为 {@code Result}）。
 *
 * <p>对齐技术方案 §4.1.2：清单项只暴露前端需要的 id/subjectId/anomalyThreshold/status， 不含内部 version/时间戳。
 */
public record WatchlistItemView(Long id, Long subjectId, BigDecimal anomalyThreshold, int status) {

    /** 领域值对象 → 视图（状态映射为 code）。 */
    static WatchlistItemView from(WatchlistItem item) {
        WatchlistStatus status =
                item.getStatus() == null ? WatchlistStatus.ENABLED : item.getStatus();
        return new WatchlistItemView(
                item.getId(), item.getSubjectId(), item.getAnomalyThreshold(), status.code());
    }
}
