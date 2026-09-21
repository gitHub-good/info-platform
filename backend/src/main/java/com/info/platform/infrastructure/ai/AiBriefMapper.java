package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * ai_brief 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}；CAS 领取走显式 {@link #claimById}（@Update
 * SQL， 不经 {@code OptimisticLockerInnerInterceptor}，避免与 updateById 的乐观锁逻辑互相干扰——CAS 的 version
 * 条件本就是显式乐观锁）。
 */
@Mapper
public interface AiBriefMapper extends BaseMapper<AiBriefPO> {

    /**
     * CAS 领取：{@code UPDATE SET version=version+1, updated_at=? WHERE id=? AND status=0 AND
     * version=?}。
     *
     * <p>影响 1 行=领取成功（version 已 bump）；影响 0 行=被并发领走或已终态。调用方据返回值决定是否重选回读。
     *
     * @param id 任务 id
     * @param expectedVersion 期望的当前 version（findById 读得）
     * @param now 更新时间（ISO-8601 文本）
     * @return 影响行数（1=成功 / 0=未领到）
     */
    @Update(
            "UPDATE ai_brief SET version = version + 1, updated_at = #{now} "
                    + "WHERE id = #{id} AND status = 0 AND version = #{expectedVersion}")
    int claimById(
            @Param("id") Long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") String now);
}
