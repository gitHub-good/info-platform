package com.info.platform.domain.subscription;

import java.util.List;
import java.util.Optional;

/**
 * 订阅配置仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>行级权限</h2>
 *
 * 面向用户的数据查询均带 {@code ownerUserId} 参数，基础设施层在 SQL {@code WHERE user_id=?} 过滤， 端口层即约束行级权限——应用层取 {@code
 * UserContext.get().userId()} 传入。 唯一不带 {@code ownerUserId} 的是 {@link #existsById}，
 * 仅返回布尔存在性（不泄露其他用户订阅数据）， 供应用层区分「订阅不存在→30050(404)」与「订阅存在但非本人→30051(403)」。 {@link #findAllActive}
 * 为系统级查询（跨全部用户， 供 T27 信息流命中引擎按全部 active 订阅匹配内容），不带 owner 过滤——仅返回订阅中（status=1）的订阅，
 * 不暴露越权风险（信息流匹配是系统行为）。
 *
 * <h2>幂等</h2>
 *
 * 按 §4.4「幂等业务语义键」= userId + subType + subKey：subscribe 经 {@link #findByOwnerIdAndTypeAndKey}
 * 查找同自然键的已存在行（含已退订）——已订阅直返（幂等，无副作用）、已退订重新激活（reactivate 翻 status，复用同一行）、 全新则 INSERT。 DB {@code
 * UNIQUE(user_id, sub_type, sub_key)} 为最后防线（极小概率竞态兜底，正常路径经预检不触达）。
 */
public interface SubscriptionRepository {

    /** 落库：id 为空走 INSERT 并回填主键，非空走 UPDATE（乐观锁由基础设施层 @Version 处理）。 */
    Subscription save(Subscription subscription);

    /**
     * 按归属用户游标分页查订阅（行级 {@code WHERE user_id=?}），可选按 subType 过滤。
     *
     * @param ownerUserId 归属用户
     * @param subType 订阅类型 code，null 表示不过滤（全部类型）
     * @param cursor 上一页末条 id，null 表示首页
     * @param limit 单页条数
     */
    List<Subscription> findByOwnerIdCursor(
            long ownerUserId, Integer subType, Long cursor, int limit);

    /** 按归属用户 + 订阅 id 加载订阅（行级校验用，退订时定位本人订阅）。 */
    Optional<Subscription> findByOwnerIdAndId(long ownerUserId, Long id);

    /** 订阅 id 是否存在（404/403 区分用，不按归属过滤，仅返回布尔）。 */
    boolean existsById(Long id);

    /**
     * 按归属用户 + 订阅类型 + 订阅键查找已存在订阅（含已退订，subscribe 幂等键 userId+subType+subKey）。
     *
     * <p>返回的实体 status 驱动幂等决策：订阅中→直返（无副作用）、已退订→重新激活、空→全新 INSERT。
     */
    Optional<Subscription> findByOwnerIdAndTypeAndKey(long ownerUserId, int subType, String subKey);

    /**
     * 查询全部订阅中的订阅（跨全部用户，status=1）。
     *
     * <p><b>系统任务专用，不走行级权限</b>：供 T27 信息流命中引擎按全部 active 订阅匹配内容（主题/事件类型/政策主题命中）。 仅返回 status=1
     * 订阅中的订阅；已退订（status=0）不参与匹配（对齐 PRD 故事 5 场景 3「退订降噪」）。
     *
     * @return 全部订阅中订阅列表（按 id 升序）
     */
    List<Subscription> findAllActive();
}
