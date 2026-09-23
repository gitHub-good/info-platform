package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * {@link PromptTemplateRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层，T20；T45 增补管理端口）。
 *
 * <p>PO↔Entity 转换集中于此。{@link #findActiveByBriefType} 查询语义： {@code WHERE brief_type=? AND status=1
 * ORDER BY version DESC LIMIT 1}——启用（{@code status=1}）行中按版本降序取首行，即"启用版本的最新版"（Spike-2 §7.5）。
 *
 * <p>版本为语义字符串（如 {@code v1.0}），字典序降序在同类前缀内与版本序一致（{@code v1.0 < v1.1 < v2.0}）； 用 {@code last("LIMIT
 * 1")} 兜底多启用行场景（理论上同一 {@code briefType} 同时仅一行 {@code status=1}，防御重复播种）。{@link #findAllByBriefType}
 * 不排序——真实版本序由应用层按 (major, minor) 数值表达（ADR-0021，TEXT 字典序在 v1.9/v1.10 类场景错序）。写路径时间戳统一 {@code
 * Instant.now()}（ISO-8601 文本）。
 *
 * <p><b>写路径异常翻译（DEFECT-1）</b>：管理面 create/activate 为「读后写」同事务，WAL 下并发提交使写命中过期快照 （SQLITE_BUSY
 * 族），败者必须收到可重试的 409/30070 而非 500——{@link #insert} 沿 ADR-0023-2 翻译 UNIQUE， 三个写方法统一经 {@link
 * #translateConcurrencyConflict} 把并发冲突码族翻译为 {@link CannotAcquireLockException} 供应用层转 30070。
 */
@Repository
public class PromptTemplateRepositoryImpl implements PromptTemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRepositoryImpl.class);

    private static final int STATUS_ACTIVE = 1;

    /**
     * SQLite 并发写冲突码族标识（DEFECT-1）：文件库 WAL 为 {@code SQLITE_BUSY} / {@code SQLITE_BUSY_SNAPSHOT}
     * （读写同事务的过期快照，busy_timeout 不挽救），共享内存库为 {@code SQLITE_LOCKED} / {@code
     * SQLITE_LOCKED_SHAREDCACHE}（跨连接表锁竞争）——均属「败者须回滚重试」的并发冲突。
     */
    private static final Pattern SQLITE_CONCURRENCY_CONFLICT =
            Pattern.compile("SQLITE_(BUSY|LOCKED)", Pattern.CASE_INSENSITIVE);

    private final PromptTemplateMapper promptTemplateMapper;

    public PromptTemplateRepositoryImpl(PromptTemplateMapper promptTemplateMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
    }

    @Override
    public Optional<PromptTemplate> findActiveByBriefType(BriefType briefType) {
        if (briefType == null) {
            return Optional.empty();
        }
        PromptTemplatePO po =
                promptTemplateMapper.selectOne(
                        new LambdaQueryWrapper<PromptTemplatePO>()
                                .eq(PromptTemplatePO::getBriefType, briefType.code())
                                .eq(PromptTemplatePO::getStatus, STATUS_ACTIVE)
                                .orderByDesc(PromptTemplatePO::getVersion)
                                .last("LIMIT 1"));
        if (po == null) {
            log.warn("未找到启用提示词模板: briefType={}", briefType);
            return Optional.empty();
        }
        return Optional.of(toEntity(po));
    }

    @Override
    public List<PromptTemplate> findAllByBriefType(BriefType briefType) {
        if (briefType == null) {
            return List.of();
        }
        return promptTemplateMapper
                .selectList(
                        new LambdaQueryWrapper<PromptTemplatePO>()
                                .eq(PromptTemplatePO::getBriefType, briefType.code()))
                .stream()
                .map(PromptTemplateRepositoryImpl::toEntity)
                .toList();
    }

    @Override
    public Optional<PromptTemplate> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        PromptTemplatePO po = promptTemplateMapper.selectById(id);
        return Optional.ofNullable(po).map(PromptTemplateRepositoryImpl::toEntity);
    }

    @Override
    public PromptTemplate insert(PromptTemplate template) {
        PromptTemplatePO po = toPO(template);
        String now = Instant.now().toString();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        try {
            promptTemplateMapper.insert(po);
        } catch (DataAccessException e) {
            // sqlite-jdbc 不抛 JDBC4 约束子类（Spring 不会自动译成 DuplicateKeyException），
            // 按 UNIQUE 关键字识别翻译——UNIQUE(brief_type, version) 是版本号冲突兜底防线（30070）
            if (e.getMessage() != null
                    && e.getMessage().toUpperCase(Locale.ROOT).contains("UNIQUE")) {
                throw new DuplicateKeyException(
                        "UNIQUE(brief_type, version) 冲突: briefType="
                                + template.getBriefType()
                                + " version="
                                + template.getVersion(),
                        e);
            }
            throw translateConcurrencyConflict(
                    e,
                    "insert briefType="
                            + template.getBriefType()
                            + " version="
                            + template.getVersion());
        }
        return toEntity(po);
    }

    @Override
    public int deactivateActive(BriefType briefType) {
        try {
            return promptTemplateMapper.update(
                    null,
                    new LambdaUpdateWrapper<PromptTemplatePO>()
                            .eq(PromptTemplatePO::getBriefType, briefType.code())
                            .eq(PromptTemplatePO::getStatus, STATUS_ACTIVE)
                            .set(PromptTemplatePO::getStatus, 0)
                            .set(PromptTemplatePO::getUpdatedAt, Instant.now().toString()));
        } catch (DataAccessException e) {
            // create/activate 写阶段首语句：读写同事务遇并发提交即在此撞冲突（DEFECT-1 实证点）
            throw translateConcurrencyConflict(e, "deactivateActive briefType=" + briefType.code());
        }
    }

    @Override
    public boolean updateStatus(Long id, int status) {
        try {
            return promptTemplateMapper.update(
                            null,
                            new LambdaUpdateWrapper<PromptTemplatePO>()
                                    .eq(PromptTemplatePO::getId, id)
                                    .set(PromptTemplatePO::getStatus, status)
                                    .set(PromptTemplatePO::getUpdatedAt, Instant.now().toString()))
                    > 0;
        } catch (DataAccessException e) {
            throw translateConcurrencyConflict(e, "updateStatus id=" + id + " status=" + status);
        }
    }

    @Override
    public boolean deleteById(Long id) {
        return promptTemplateMapper.deleteById(id) > 0;
    }

    /**
     * SQLite 并发写冲突翻译（DEFECT-1）：{@link #SQLITE_CONCURRENCY_CONFLICT} 码族（含 cause 链）→ {@link
     * CannotAcquireLockException}（应用层转 409/30070 请刷新重试）；其他异常原样返回由调用方继续抛出（不吞不译）。
     *
     * <p>与 {@link #insert} 的 UNIQUE 翻译同层：sqlite-jdbc 的 {@code SQLiteException} 直承 {@code
     * SQLException}，Spring 无 SQLite 错误码表，并发冲突码族不会自动翻译，落 {@code UncategorizedSQLException} 冒泡即
     * 500/50000——须在此按报文码族识别（ADR-0023-2 同款策略扩展）。
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

    private static PromptTemplate toEntity(PromptTemplatePO po) {
        return PromptTemplate.reconstruct(
                po.getId(),
                BriefType.fromCode(po.getBriefType()),
                po.getVersion(),
                po.getTemplate(),
                po.getStatus(),
                parseInstant(po.getCreatedAt()),
                parseInstant(po.getUpdatedAt()));
    }

    /** ISO-8601 文本转 {@link Instant}（历史脏数据/空值容错为 null，展示层自行兜底）。 */
    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            log.warn("prompt_template 时间戳非 ISO-8601，忽略: {}", text);
            return null;
        }
    }

    private static PromptTemplatePO toPO(PromptTemplate template) {
        PromptTemplatePO po = new PromptTemplatePO();
        po.setId(template.getId());
        po.setBriefType(template.getBriefType().code());
        po.setVersion(template.getVersion());
        po.setTemplate(template.getTemplate());
        po.setStatus(template.getStatus());
        return po;
    }
}
