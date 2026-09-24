package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.ai.AiBrief;
import com.info.platform.domain.ai.AiBriefRepository;
import com.info.platform.domain.ai.BriefStatus;
import com.info.platform.domain.ai.BriefType;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AiBriefRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层，T21）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本；终态写入乐观锁由 {@code @Version} + {@code
 * OptimisticLockerInnerInterceptor} 守护（{@code updateById}）；CAS 领取走 {@link AiBriefMapper#claimById}
 * 显式 SQL （不经拦截器，version 条件显式）。
 *
 * <p>{@link #save}：id 空走 INSERT（回填主键 + version=0 + 时间戳），id 非空走 updateById（乐观锁）； {@link #claim}：CAS
 * bump version（{@code WHERE id=? AND status=0 AND version=?}），成功重选回读返回 bumped 实体。
 *
 * <p><b>写路径异常翻译（体检 P2 后端条目，照抄 ADR-0023-2/5 先例）</b>：并发双 POST 败者在应用层查重后 INSERT 撞 {@code
 * uq_ai_brief_idempotency}，或读写同事务命中 SQLITE_BUSY/LOCKED 码族——均须翻译为语义异常（败者按幂等命中 / 冲突重试），
 * 而非 UncategorizedSQLException 冒泡 500/50000。
 */
@Repository
public class AiBriefRepositoryImpl implements AiBriefRepository {

    private static final Logger log = LoggerFactory.getLogger(AiBriefRepositoryImpl.class);

    /**
     * SQLite 并发写冲突码族标识（对齐 {@code PromptTemplateRepositoryImpl}）：文件库 WAL 为 {@code SQLITE_BUSY} /
     * {@code SQLITE_BUSY_SNAPSHOT}，共享内存库为 {@code SQLITE_LOCKED} / {@code SQLITE_LOCKED_SHAREDCACHE}——
     * 均属「败者须回滚重试」的并发冲突。
     */
    private static final Pattern SQLITE_CONCURRENCY_CONFLICT =
            Pattern.compile("SQLITE_(BUSY|LOCKED)", Pattern.CASE_INSENSITIVE);

    private final AiBriefMapper mapper;

    public AiBriefRepositoryImpl(AiBriefMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AiBrief save(AiBrief brief) {
        AiBriefPO po = toPO(brief);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getVersion() == null) {
                po.setVersion(0);
            }
            try {
                mapper.insert(po);
            } catch (DataAccessException e) {
                // sqlite-jdbc 不抛 JDBC4 约束子类（Spring 不会自动译成 DuplicateKeyException），
                // 按 UNIQUE 关键字识别翻译——uq_ai_brief_idempotency 是幂等防重最后防线（体检 P2 条目）
                if (e.getMessage() != null
                        && e.getMessage().toUpperCase(Locale.ROOT).contains("UNIQUE")) {
                    throw new DuplicateKeyException(
                            "UNIQUE(idempotency_key) 冲突: idempotencyKey=" + po.getIdempotencyKey(),
                            e);
                }
                throw translateConcurrencyConflict(e, "insert idempotencyKey=" + po.getIdempotencyKey());
            }
            log.info(
                    "新增 AI 简报任务: id={}, subjectId={}, briefType={}, idempotencyKey={}",
                    po.getId(),
                    po.getSubjectId(),
                    po.getBriefType(),
                    po.getIdempotencyKey());
        } else {
            po.setUpdatedAt(now);
            mapper.updateById(po);
            log.info(
                    "更新 AI 简报任务: id={}, status={}, version={}",
                    po.getId(),
                    po.getStatus(),
                    po.getVersion());
        }
        return toEntity(po);
    }

    @Override
    public Optional<AiBrief> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        AiBriefPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(AiBriefRepositoryImpl::toEntity);
    }

    @Override
    public Optional<AiBrief> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        AiBriefPO po =
                mapper.selectOne(
                        new LambdaQueryWrapper<AiBriefPO>()
                                .eq(AiBriefPO::getIdempotencyKey, idempotencyKey));
        return Optional.ofNullable(po).map(AiBriefRepositoryImpl::toEntity);
    }

    @Override
    @Transactional
    public Optional<AiBrief> claim(Long id, long expectedVersion) {
        if (id == null) {
            return Optional.empty();
        }
        String now = Instant.now().toString();
        int rows = mapper.claimById(id, expectedVersion, now);
        if (rows == 0) {
            log.info("AI 简报 CAS 领取未命中（被并发领走或已终态）: id={}, expectedVersion={}", id, expectedVersion);
            return Optional.empty();
        }
        // 领取成功，重选回读（version 已 bump），供 worker 持有 bumped 实体做终态 updateById
        AiBriefPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(AiBriefRepositoryImpl::toEntity);
    }

    /**
     * SQLite 并发写冲突翻译（对齐 {@code PromptTemplateRepositoryImpl#translateConcurrencyConflict}）：
     * {@link #SQLITE_CONCURRENCY_CONFLICT} 码族（含 cause 链）→ {@link CannotAcquireLockException}；其他异常原样返回由
     * 调用方继续抛出（不吞不译）。
     */
    private static DataAccessException translateConcurrencyConflict(
            DataAccessException e, String operation) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && SQLITE_CONCURRENCY_CONFLICT.matcher(message).find()) {
                throw new CannotAcquireLockException("SQLITE 并发写冲突(" + operation + ")：请回滚后刷新重试", e);
            }
        }
        return e;
    }

    private static AiBrief toEntity(AiBriefPO po) {
        return AiBrief.reconstruct(
                po.getId(),
                po.getSubjectId(),
                BriefType.fromCode(po.getBriefType()),
                po.getPromptVersion(),
                po.getModel(),
                po.getContent(),
                po.getSourceLinks(),
                po.getCostTokens(),
                BriefStatus.fromCode(po.getStatus()),
                po.getIdempotencyKey(),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static AiBriefPO toPO(AiBrief brief) {
        AiBriefPO po = new AiBriefPO();
        po.setId(brief.getId());
        po.setSubjectId(brief.getSubjectId());
        po.setBriefType(brief.getBriefType().code());
        po.setPromptVersion(brief.getPromptVersion());
        po.setModel(brief.getModel());
        po.setContent(brief.getContent());
        po.setSourceLinks(brief.getSourceLinks());
        po.setCostTokens(brief.getCostTokens());
        po.setStatus(brief.getStatus().code());
        po.setIdempotencyKey(brief.getIdempotencyKey());
        po.setVersion((int) brief.getVersion());
        po.setCreatedAt(brief.getCreatedAt() == null ? null : brief.getCreatedAt().toString());
        po.setUpdatedAt(brief.getUpdatedAt() == null ? null : brief.getUpdatedAt().toString());
        return po;
    }
}
