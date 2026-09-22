package com.info.platform.infrastructure.subscription;

import com.info.platform.domain.push.SubscriptionResolver;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@link SubscriptionResolver} 的精细订阅实现（T26，基础设施层，归属 subscription 域）。
 *
 * <p>替代 M1 {@link WatchlistSubscriptionResolver} 的 watchlist 隐含订阅——对齐技术方案 §4.3 流程 3「查订阅」+ §4.1.6
 * 精细订阅 + PRD 故事 5：某标的异动该推给哪些用户，由 {@code subscription_config} 精细订阅配置决定（用户显式订阅了该标的才推）， 而非 M1「加入
 * watchlist 即隐含订阅异动」。
 *
 * <p>解析：查 {@code subscription_config} WHERE {@code sub_type=2(标的)} AND {@code sub_key=subjectId}
 * AND {@code status=1(订阅中)} → 返回订阅该标的的用户集合。已退订（status=0）的标的订阅不命中（退订后不推送，对齐 PRD 故事 5 场景 3「退订降噪」）。
 *
 * <p><b>切换策略</b>：本类标 {@link Primary}，Spring 注入 {@link SubscriptionResolver} 端口时优先选本实现， 覆盖 M1 {@link
 * WatchlistSubscriptionResolver}（后者保留为可回退实现，非默认）。应用层 {@code PushService} 注入的是 {@link
 * SubscriptionResolver} 端口，切换后零改动——端口契约不变，仅实现替换。
 *
 * <p>系统任务查询，不带 ownerUserId 过滤（推送目标解析需跨全部用户），无越权风险——本方法只返回 user_id 集合，不暴露订阅归属细节。
 */
@Component
@Primary
public class SubscriptionConfigSubscriptionResolver implements SubscriptionResolver {

    private static final Logger log =
            LoggerFactory.getLogger(SubscriptionConfigSubscriptionResolver.class);

    private final SubscriptionMapper subscriptionMapper;

    public SubscriptionConfigSubscriptionResolver(SubscriptionMapper subscriptionMapper) {
        this.subscriptionMapper = subscriptionMapper;
    }

    @Override
    public Set<Long> resolveAnomalyTargets(Long subjectId) {
        if (subjectId == null) {
            return Collections.emptySet();
        }
        // sub_key 存 subjectId 的字符串形式（subscribe 时 String.valueOf 落库），按文本匹配避免 SQLite 类型亲和歧义
        String subKey = String.valueOf(subjectId);
        List<Long> userIds =
                subscriptionMapper.selectActiveSubscriberUserIdsBySubject(
                        subKey,
                        SubscriptionType.SUBJECT.code(),
                        SubscriptionStatus.SUBSCRIBED.code());
        // UNIQUE(user_id, sub_type, sub_key) 保证每用户至多一行，DISTINCT 在 SQL 已去重；LinkedHashSet
        // 保序并二次去重（防御驱动器/映射异常）
        Set<Long> targets = new LinkedHashSet<>(userIds);
        log.debug("异动推送目标解析（精细订阅）: subjectId={}, 目标用户 {} 个", subjectId, targets.size());
        return targets;
    }
}
