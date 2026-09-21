package com.info.platform.infrastructure.subscription;

import com.info.platform.domain.push.SubscriptionResolver;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link SubscriptionResolver} 的 M1 实现：watchlist 隐含异动订阅（基础设施层，归属 subscription 域）。
 *
 * <p>对齐技术方案 §4.3 流程 3「查订阅」+ 任务单 T14：M1 阶段把某标的加入自选清单即视为订阅其异动推送。 本类 JOIN watchlist +
 * watchlist_item，解析所有含该标的的活跃清单（item.status=1 且 watchlist.status=1）的归属用户， 去重返回。
 *
 * <p>可替换设计（对齐 §4.4 / T26）：本端口契约稳定，T26 subscription_config 落地后换实现为按 sub_type=2(标的)/3(事件类型)
 * 精细订阅查询；应用层 {@code PushService} 注入的是 {@link SubscriptionResolver} 端口，切换实现零改动。
 *
 * <p>系统任务查询，不带 ownerUserId 过滤（推送目标解析需跨全部用户），无越权风险——本方法只返回 user_id 集合，不暴露清单归属细节。
 */
@Component
public class WatchlistSubscriptionResolver implements SubscriptionResolver {

    private static final Logger log = LoggerFactory.getLogger(WatchlistSubscriptionResolver.class);

    private final WatchlistItemMapper itemMapper;

    public WatchlistSubscriptionResolver(WatchlistItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Override
    public Set<Long> resolveAnomalyTargets(Long subjectId) {
        if (subjectId == null) {
            return Collections.emptySet();
        }
        List<Long> userIds =
                itemMapper.selectActiveUserIdsBySubject(
                        subjectId, WatchlistStatus.ENABLED.code(), WatchlistStatus.ENABLED.code());
        // DISTINCT 已在 SQL 去重； LinkedHashSet 保序并二次去重（防御驱动器/映射异常）
        Set<Long> targets = new LinkedHashSet<>(userIds);
        log.debug("异动推送目标解析: subjectId={}, 目标用户 {} 个", subjectId, targets.size());
        return targets;
    }
}
