package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubscriptionRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>行级权限：所有返回订阅数据的查询均 {@code WHERE user_id=?}（ownerUserId），端口层即约束——用户只能读到自己的订阅。 唯一不带 owner 过滤的是
 * {@link #existsById}，仅返回布尔存在性，供应用层区分 404/403； {@link #findAllActive} 为系统级查询（T27 信息流命中）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本； 乐观锁由 {@code @Version} + {@code
 * OptimisticLockerInnerInterceptor} 守护（退订/重新激活 UPDATE 生效）。
 */
@Repository
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRepositoryImpl.class);

    private final SubscriptionMapper subscriptionMapper;

    public SubscriptionRepositoryImpl(SubscriptionMapper subscriptionMapper) {
        this.subscriptionMapper = subscriptionMapper;
    }

    @Override
    @Transactional
    public Subscription save(Subscription subscription) {
        SubscriptionPO po = toPO(subscription);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getVersion() == null) {
                po.setVersion(0);
            }
            subscriptionMapper.insert(po);
            log.info(
                    "新增订阅: id={}, userId={}, subType={}, subKey={}",
                    po.getId(),
                    po.getUserId(),
                    po.getSubType(),
                    po.getSubKey());
            return toEntity(po);
        }
        po.setUpdatedAt(now);
        subscriptionMapper.updateById(po); // @Version 乐观锁生效（退订/重新激活）
        log.info("更新订阅: id={}, status={}, version={}", po.getId(), po.getStatus(), po.getVersion());
        return toEntity(po);
    }

    @Override
    public List<Subscription> findByOwnerIdCursor(
            long ownerUserId, Integer subType, Long cursor, int limit) {
        LambdaQueryWrapper<SubscriptionPO> wrapper =
                new LambdaQueryWrapper<SubscriptionPO>()
                        .eq(SubscriptionPO::getUserId, ownerUserId)
                        .eq(subType != null, SubscriptionPO::getSubType, subType)
                        .gt(cursor != null, SubscriptionPO::getId, cursor)
                        .orderByAsc(SubscriptionPO::getId)
                        .last("LIMIT " + limit);
        return subscriptionMapper.selectList(wrapper).stream()
                .map(SubscriptionRepositoryImpl::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Subscription> findByOwnerIdAndId(long ownerUserId, Long id) {
        SubscriptionPO po =
                subscriptionMapper.selectOne(
                        new LambdaQueryWrapper<SubscriptionPO>()
                                .eq(SubscriptionPO::getUserId, ownerUserId)
                                .eq(SubscriptionPO::getId, id));
        return Optional.ofNullable(po).map(SubscriptionRepositoryImpl::toEntity);
    }

    @Override
    public boolean existsById(Long id) {
        return subscriptionMapper.exists(
                new LambdaQueryWrapper<SubscriptionPO>().eq(SubscriptionPO::getId, id));
    }

    @Override
    public Optional<Subscription> findByOwnerIdAndTypeAndKey(
            long ownerUserId, int subType, String subKey) {
        SubscriptionPO po =
                subscriptionMapper.selectOne(
                        new LambdaQueryWrapper<SubscriptionPO>()
                                .eq(SubscriptionPO::getUserId, ownerUserId)
                                .eq(SubscriptionPO::getSubType, subType)
                                .eq(SubscriptionPO::getSubKey, subKey));
        return Optional.ofNullable(po).map(SubscriptionRepositoryImpl::toEntity);
    }

    @Override
    public List<Subscription> findAllActive() {
        // 系统任务专用：跨全部用户，仅返回 status=1 订阅中的订阅（T27 信息流命中用）
        List<SubscriptionPO> pos =
                subscriptionMapper.selectList(
                        new LambdaQueryWrapper<SubscriptionPO>()
                                .eq(SubscriptionPO::getStatus, SubscriptionStatus.SUBSCRIBED.code())
                                .orderByAsc(SubscriptionPO::getId));
        return pos.stream().map(SubscriptionRepositoryImpl::toEntity).collect(Collectors.toList());
    }

    private static Subscription toEntity(SubscriptionPO po) {
        return Subscription.reconstruct(
                po.getId(),
                po.getUserId(),
                SubscriptionType.fromCode(po.getSubType()),
                po.getSubKey(),
                SubscriptionChannel.fromCode(po.getChannel()),
                SubscriptionStatus.fromCode(po.getStatus()),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static SubscriptionPO toPO(Subscription subscription) {
        SubscriptionPO po = new SubscriptionPO();
        po.setId(subscription.getId());
        po.setUserId(subscription.getUserId());
        po.setSubType(subscription.getSubType().code());
        po.setSubKey(subscription.getSubKey());
        po.setChannel(subscription.getChannel().code());
        po.setStatus(subscription.getStatus().code());
        po.setVersion((int) subscription.getVersion());
        po.setCreatedAt(
                subscription.getCreatedAt() == null
                        ? null
                        : subscription.getCreatedAt().toString());
        po.setUpdatedAt(
                subscription.getUpdatedAt() == null
                        ? null
                        : subscription.getUpdatedAt().toString());
        return po;
    }
}
