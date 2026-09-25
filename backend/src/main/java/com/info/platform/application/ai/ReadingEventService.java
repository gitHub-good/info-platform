package com.info.platform.application.ai;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.ai.ReadingEventType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 阅读行为留痕应用服务（应用层，T29 推荐相关性优化）。
 *
 * <p>受理前端埋点（标的详情页/政策详情/简报页/信息流「点原文」阅读事件，REQ-20260925-08 增 FEED）并落 {@code reading_event}：解析标的关联
 * （subjectId 优先、subjectCode 次之，均解析不到记 null 不拒收——政策/每日推荐阅读本就无单一标的）， 按「同 user + contentType +
 * contentRef 且 {@link #DEDUP_WINDOW} 内已存在」去重后 INSERT。
 *
 * <p>幂等友好口径：窗口内重复上报（前端重试 / React StrictMode 双触发）返回 {@code recorded=false}
 * 不报错不落重复行；窗口外重复阅读照常留痕（阅读次数是画像热度输入）。消费方为 {@link RecommendationPersonalizer}（近 30 天画像）。
 */
@Service
public class ReadingEventService {

    private static final Logger log = LoggerFactory.getLogger(ReadingEventService.class);

    /** 去重窗口：同内容 1 小时内重复上报跳过。 */
    static final Duration DEDUP_WINDOW = Duration.ofHours(1);

    private final ReadingEventRepository repository;
    private final SubjectRepository subjectRepository;
    private final Clock clock;

    public ReadingEventService(
            ReadingEventRepository repository, SubjectRepository subjectRepository, Clock clock) {
        this.repository = repository;
        this.subjectRepository = subjectRepository;
        this.clock = clock;
    }

    /**
     * 记录一次阅读（POST /reading-events）。
     *
     * @param userId 归属用户（JwtAuthFilter 写入 UserContext）
     * @param contentTypeName 内容类型名（SUBJECT_DETAIL/POLICY/AI_BRIEF/FEED）
     * @param contentRef 内容引用（标的代码/政策 id/简报 taskId/信息流条目稳定 contentId）
     * @param subjectCode 标的代码（可空；详情页/信息流公告/新闻/推荐条目埋点传入）
     * @param subjectId 标的 id（可空；简报页埋点传入，优先于 subjectCode）
     * @return true=本次落库；false=窗口内重复被去重跳过
     * @throws BusinessException 2001 内容类型未知/引用空或超长（400）
     */
    public boolean record(
            long userId,
            String contentTypeName,
            String contentRef,
            String subjectCode,
            Long subjectId) {
        ReadingEventType type = parseType(contentTypeName);
        try {
            Long resolvedSubjectId = resolveSubjectId(subjectId, subjectCode);
            if (inDedupWindow(userId, type, contentRef)) {
                log.debug("阅读留痕窗口内去重跳过 userId={} type={} ref={}", userId, type, contentRef);
                return false;
            }
            ReadingEvent saved =
                    repository.save(
                            ReadingEvent.record(userId, resolvedSubjectId, type, contentRef));
            log.info(
                    "阅读留痕落库 userId={} subjectId={} type={} ref={} id={}",
                    userId,
                    saved.getSubjectId(),
                    type,
                    saved.getContentRef(),
                    saved.getId());
            return true;
        } catch (IllegalArgumentException e) {
            // ReadingEvent.record 的参数校验（ref 空或超长）转业务异常
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }

    /** 解析内容类型；未知值转 2001（400）。 */
    private static ReadingEventType parseType(String contentTypeName) {
        try {
            return ReadingEventType.fromName(contentTypeName);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "contentType 取值 SUBJECT_DETAIL/POLICY/AI_BRIEF/FEED");
        }
    }

    /** 标的关联解析：subjectId 优先，次之 subjectCode（findByCode）；均无/解析不到 → null（不拒收）。 */
    private Long resolveSubjectId(Long subjectId, String subjectCode) {
        if (subjectId != null) {
            return subjectId;
        }
        if (subjectCode == null || subjectCode.isBlank()) {
            return null;
        }
        Subject subject =
                subjectRepository.findByCode(SubjectCode.of(subjectCode.trim())).orElse(null);
        return subject == null ? null : subject.getId();
    }

    /** 去重窗口检查：同 user+type+ref 1 小时内已有留痕。 */
    private boolean inDedupWindow(long userId, ReadingEventType type, String contentRef) {
        Instant since = Instant.now(clock).minus(DEDUP_WINDOW);
        return repository.existsSince(userId, type, contentRef.trim(), since);
    }
}
