package com.info.platform.domain.push;

import java.util.Set;

/**
 * 推送目标解析端口（依赖倒置：push 域定义、基础设施层实现）。
 *
 * <p>把「某标的异动该推给哪些用户」的解析规则抽象成可替换端口，对齐技术方案 §4.3 流程 3「查订阅」步骤：
 *
 * <ul>
 *   <li><b>M1（当前实现）</b>：watchlist 隐含异动订阅——把某标的加入自选清单即视为订阅其异动推送。 {@code
 *       WatchlistSubscriptionResolver}（infrastructure/subscription）JOIN watchlist + watchlist_item
 *       解析所有含该标的的活跃清单的 归属用户。
 *   <li><b>T26 切换</b>：subscription_config 落地后换实现为按 sub_type=2(标的)/3(事件类型) 精细订阅查询； 端口契约不变，应用层 {@code
 *       PushService} 零改动。
 * </ul>
 *
 * <p>领域层纯净接口，不依赖框架类型。跨域：push 域定义端口、subscription 域基础设施实现，不跨域 import 内部类。
 */
public interface SubscriptionResolver {

    /**
     * 解析某标的异动推送的目标用户集合。
     *
     * @param subjectId 标的内部主键
     * @return 去重后的目标用户 id 集合（无订阅返回空集，绝不返回 null）
     */
    Set<Long> resolveAnomalyTargets(Long subjectId);
}
