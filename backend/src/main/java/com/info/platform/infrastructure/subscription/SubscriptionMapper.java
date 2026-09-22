package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * subscription_config 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}； 推送目标解析用 {@code @Select}
 * 原生 SQL。
 */
@Mapper
public interface SubscriptionMapper extends BaseMapper<SubscriptionPO> {

    /**
     * 查询订阅了某标的的全部活跃用户（去重 user_id），供 T26 后 PushService 异动推送目标解析（精细订阅，替代 M1 watchlist 隐含订阅）。
     *
     * <p>按 sub_type=2(标的) + sub_key=subjectId + status=1(订阅中) 过滤； {@code UNIQUE(user_id, sub_type,
     * sub_key)} 保证同一用户对同一标的仅一行， DISTINCT 为防御性二次去重。 已退订（status=0）的标的订阅不命中（退订后不推送，对齐 PRD 故事 5 场景 3）。
     *
     * @param subKey 标的订阅键（subjectId 的字符串形式，与 subscribe 时存入的 sub_key 文本对齐）
     * @param subType 订阅类型码（2=标的）
     * @param status 订阅状态码（1=订阅中）
     */
    @Select(
            "SELECT DISTINCT user_id FROM subscription_config "
                    + "WHERE sub_type = #{subType} AND sub_key = #{subKey} AND status = #{status}")
    List<Long> selectActiveSubscriberUserIdsBySubject(
            @Param("subKey") String subKey,
            @Param("subType") int subType,
            @Param("status") int status);
}
