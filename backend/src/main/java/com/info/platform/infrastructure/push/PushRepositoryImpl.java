package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.push.PushRecord;
import com.info.platform.domain.push.PushRepository;
import com.info.platform.domain.push.PushStatus;
import com.info.platform.domain.push.PushType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PushRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本。
 *
 * <h2>幂等防重推</h2>
 *
 * {@link #saveIfAbsent} 双保险：先应用层 {@code exists(idempotency_key)} 查重（常态命中直接跳过，避免异常驱动控制流）； 再
 * INSERT，并发竞态下 DB {@code UNIQUE(idempotency_key)} 冲突（{@link DuplicateKeyException}）也视为已存在返回
 * empty（§4.4「DB UNIQUE 是防重推最后防线」）。绝不抛防重冲突给上层。
 *
 * <h2>history 游标分页与重连补拉</h2>
 *
 * {@link #findByUserIdCursor} 走 {@code WHERE user_id=? AND id > cursor ORDER BY id ASC LIMIT
 * n}（防深分页 §4.4），命中 {@code idx_push_user_status(user_id, status)} 的前缀 user_id； {@link
 * #findLatestByUser} 一次取该用户最近 N 条（P1-1 通知面板兜底）； {@link #findPendingByUser} 取 status=0 待推记录供 SSE
 * 重连补拉（带保留期过滤，超期积压不再补）。
 */
@Repository
public class PushRepositoryImpl implements PushRepository {

    private static final Logger log = LoggerFactory.getLogger(PushRepositoryImpl.class);

    private final PushMapper pushMapper;

    public PushRepositoryImpl(PushMapper pushMapper) {
        this.pushMapper = pushMapper;
    }

    @Override
    @Transactional
    public Optional<PushRecord> saveIfAbsent(PushRecord record) {
        String key = record.getIdempotencyKey();
        // 应用层查重：常态命中直接跳过（防重推），不靠异常驱动控制流
        if (pushMapper.exists(
                new LambdaQueryWrapper<PushRecordPO>().eq(PushRecordPO::getIdempotencyKey, key))) {
            log.debug("推送记录已存在（防重跳过）: idempotencyKey={}", key);
            return Optional.empty();
        }
        PushRecordPO po = toPO(record);
        String now = Instant.now().toString();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        if (po.getStatus() == null) {
            po.setStatus(PushStatus.PENDING.code());
        }
        if (po.getRetryCount() == null) {
            po.setRetryCount(0);
        }
        if (po.getVersion() == null) {
            po.setVersion(0);
        }
        try {
            pushMapper.insert(po);
        } catch (DataIntegrityViolationException e) {
            // 并发竞态：应用层查重与 INSERT 之间另一事务已插入同 key → DB UNIQUE 兜底，视为已存在跳过。
            // 用父类 DataIntegrityViolationException 兜底（SQLite/xerial 经 Spring 转译的 UNIQUE 冲突可能落子类）
            log.info("推送记录并发冲突（DB UNIQUE 兜底，视为已存在）: idempotencyKey={}", key);
            return Optional.empty();
        }
        log.info(
                "新增推送记录: id={}, userId={}, pushType={}, status={}, idempotencyKey={}",
                po.getId(),
                po.getUserId(),
                po.getPushType(),
                po.getStatus(),
                key);
        return Optional.of(toEntity(po));
    }

    @Override
    @Transactional
    public void update(PushRecord record) {
        PushRecordPO po = toPO(record);
        po.setUpdatedAt(Instant.now().toString());
        // updateById 受 @Version 乐观锁守护：影响 0 行=并发被改，记 WARN（推送状态翻转非资金类，不抛）
        int rows = pushMapper.updateById(po);
        if (rows == 0) {
            log.warn("推送记录更新乐观锁冲突（0 行）: id={}, version={}", po.getId(), po.getVersion());
        } else {
            log.info(
                    "更新推送记录: id={}, status={}, retryCount={}",
                    po.getId(),
                    po.getStatus(),
                    po.getRetryCount());
        }
    }

    @Override
    public List<PushRecord> findByUserIdCursor(long userId, Long cursor, PushType type, int limit) {
        LambdaQueryWrapper<PushRecordPO> wrapper =
                new LambdaQueryWrapper<PushRecordPO>()
                        .eq(PushRecordPO::getUserId, userId)
                        .gt(cursor != null, PushRecordPO::getId, cursor)
                        .eq(
                                type != null,
                                PushRecordPO::getPushType,
                                type == null ? null : type.code())
                        .orderByAsc(PushRecordPO::getId)
                        .last("LIMIT " + limit);
        List<PushRecordPO> pos = pushMapper.selectList(wrapper);
        return toEntities(pos);
    }

    @Override
    public List<PushRecord> findLatestByUser(long userId, PushType type, int limit) {
        // id 降序取尾部（最新）LIMIT n，再反转为升序返回（面板按时间正序展示）
        List<PushRecordPO> pos =
                pushMapper.selectList(
                        new LambdaQueryWrapper<PushRecordPO>()
                                .eq(PushRecordPO::getUserId, userId)
                                .eq(
                                        type != null,
                                        PushRecordPO::getPushType,
                                        type == null ? null : type.code())
                                .orderByDesc(PushRecordPO::getId)
                                .last("LIMIT " + limit));
        List<PushRecord> entities = new ArrayList<>(toEntities(pos));
        Collections.reverse(entities);
        return entities;
    }

    @Override
    public List<PushRecord> findPendingByUser(long userId, Instant createdSince) {
        // 保留期过滤（P1-1 批 1 遗留项）：超期 PENDING 不再补拉（对齐 findPending 的 pending-retention-days 语义）
        List<PushRecordPO> pos =
                pushMapper.selectList(
                        new LambdaQueryWrapper<PushRecordPO>()
                                .eq(PushRecordPO::getUserId, userId)
                                .eq(PushRecordPO::getStatus, PushStatus.PENDING.code())
                                .ge(PushRecordPO::getCreatedAt, createdSince.toString())
                                .orderByAsc(PushRecordPO::getId));
        return toEntities(pos);
    }

    @Override
    public List<PushRecord> findPending(int limit, Instant createdSince) {
        // status=0 记录按 id 升序（与 findPendingByUser 同索引前缀 status，无 user 过滤）；
        // LIMIT 防无界全量拉取 + created_at >= 截止过滤超期 PENDING（系统体检 20260924 P1-1 后端半段：
        // 前端未接 SSE 期间积压无消费者，超保留期直接跳过，防 30s 轮询永久全量扫描）
        List<PushRecordPO> pos =
                pushMapper.selectList(
                        new LambdaQueryWrapper<PushRecordPO>()
                                .eq(PushRecordPO::getStatus, PushStatus.PENDING.code())
                                .ge(PushRecordPO::getCreatedAt, createdSince.toString())
                                .orderByAsc(PushRecordPO::getId)
                                .last("LIMIT " + limit));
        return toEntities(pos);
    }

    private List<PushRecord> toEntities(List<PushRecordPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(PushRepositoryImpl::toEntity).toList();
    }

    private static PushRecord toEntity(PushRecordPO po) {
        return PushRecord.reconstruct(
                po.getId(),
                po.getUserId(),
                po.getSubjectId(),
                PushType.fromCode(po.getPushType()),
                po.getRefId(),
                po.getContent(),
                po.getIdempotencyKey(),
                PushStatus.fromCode(po.getStatus()),
                po.getPushedAt() == null ? null : Instant.parse(po.getPushedAt()),
                po.getRetryCount() == null ? 0 : po.getRetryCount(),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static PushRecordPO toPO(PushRecord record) {
        PushRecordPO po = new PushRecordPO();
        po.setId(record.getId());
        po.setUserId(record.getUserId());
        po.setSubjectId(record.getSubjectId().orElse(null));
        po.setPushType(record.getPushType().code());
        po.setRefId(record.getRefId().orElse(null));
        po.setContent(record.getContent());
        po.setIdempotencyKey(record.getIdempotencyKey());
        po.setStatus(record.getStatus().code());
        po.setPushedAt(record.getPushedAt().map(Instant::toString).orElse(null));
        po.setRetryCount(record.getRetryCount());
        po.setVersion((int) record.getVersion());
        po.setCreatedAt(record.getCreatedAt().map(Instant::toString).orElse(null));
        po.setUpdatedAt(record.getUpdatedAt().map(Instant::toString).orElse(null));
        return po;
    }
}
