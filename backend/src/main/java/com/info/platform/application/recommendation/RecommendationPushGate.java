package com.info.platform.application.recommendation;

import com.info.platform.application.push.PushService;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 推荐推送闸门（应用层，M16 T133，方案 §4.6 / REQ「超限/降频只静默不丢」「7 天只降推送不杀生成」）：本 tick 新卡 + 当日遗留 PENDING 卡过四段闸门——
 *
 * <ol>
 *   <li><b>降噪拦截</b>：combo_key 活跃 mute（ACTIVE 且未到期）→ card SKIPPED_MUTED + push_record {@code
 *       SILENT(3)}（建卡但静默——卡留推荐中心，通知历史可见，不弹 SSE）。
 *   <li><b>排序</b>：剩余 PENDING 按 recscore DESC、id ASC。
 *   <li><b>日配额</b>：remain = dailyLimit(10 可配) − 当日已推计数（push_status='PUSHED' 且 pushed_at ≥ 上海今日零点， 含
 *       SSE 态；SILENT 不占）——查询窗换日自动重置（零恢复代码）。
 *   <li><b>推送/静默</b>：前 remain 张经 {@link PushService} 既有推送链（PushType {@code RECOMMENDATION(10)}，幂等键
 *       userId:10:cardId——一卡一推）+ card 条件迁移 PUSHED；尾部 SKIPPED_QUOTA + SILENT 留痕。
 * </ol>
 */
@Service
public class RecommendationPushGate {

    private static final Logger log = LoggerFactory.getLogger(RecommendationPushGate.class);

    /** 统计日界（Asia/Shanghai——日配额窗口口径，沿 EventExtractionService 先例）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 铃铛摘要行上限（方案 §4.6：≤80 字截断）。 */
    static final int SUMMARY_LINE_MAX_LENGTH = 80;

    private final RecommendationCardRepository cardRepository;

    private final RecommendationMuteRepository muteRepository;

    private final PushService pushService;

    private final RecommendationSettings settings;

    private final Clock clock;

    public RecommendationPushGate(
            RecommendationCardRepository cardRepository,
            RecommendationMuteRepository muteRepository,
            PushService pushService,
            RecommendationSettings settings,
            Clock clock) {
        this.cardRepository = cardRepository;
        this.muteRepository = muteRepository;
        this.pushService = pushService;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 对单用户执行一轮闸门（FEED tick 建卡后同段调用）。
     *
     * @return 闸门计数（JobRunStats 段式明细 push/mute/quota 数据面）
     */
    public GateReport run(long userId) {
        List<RecommendationCard> pending = cardRepository.findPendingByUser(userId);
        if (pending.isEmpty()) {
            return new GateReport(0, 0, 0);
        }
        Instant now = clock.instant();
        MuteOutcome muteOutcome = applyMuteGate(userId, pending, now);
        GateReport quotaReport = applyQuotaGate(userId, muteOutcome.candidates(), now);
        return new GateReport(
                quotaReport.pushed(), muteOutcome.muted(), quotaReport.quotaSkipped());
    }

    /** 闸门 1：降噪拦截（活跃 mute → SKIPPED_MUTED + SILENT 留痕，不进排序）。 */
    private MuteOutcome applyMuteGate(long userId, List<RecommendationCard> pending, Instant now) {
        List<RecommendationCard> candidates = new ArrayList<>();
        int muted = 0;
        for (RecommendationCard card : pending) {
            if (isMuting(userId, card.getComboKey(), now)) {
                if (cardRepository.markSkipped(card.getId(), CardPushStatus.SKIPPED_MUTED) > 0) {
                    pushService.recordSilent(userId, card.getId(), summaryLine(card));
                    muted++;
                    log.info(
                            "降频拦截静默: userId={} cardId={} comboKey={}",
                            userId,
                            card.getId(),
                            card.getComboKey());
                }
                continue;
            }
            candidates.add(card);
        }
        return new MuteOutcome(candidates, muted);
    }

    /** 活跃降频判定（ACTIVE 且未到期；LIFTED/到期放行）。 */
    private boolean isMuting(long userId, String comboKey, Instant now) {
        Optional<RecommendationMute> mute =
                muteRepository.findActiveByUserAndCombo(userId, comboKey);
        return mute.map(m -> m.isMuting(now)).orElse(false);
    }

    /** 闸门 2+3+4：recscore 排序 → 日配额 → 推送/静默（返回闸门 2~4 计数）。 */
    private GateReport applyQuotaGate(
            long userId, List<RecommendationCard> candidates, Instant now) {
        candidates.sort(
                Comparator.comparingDouble(RecommendationCard::getRecscore)
                        .reversed()
                        .thenComparing(RecommendationCard::getId));
        long todayPushed = cardRepository.countPushedSince(userId, todayStart(now));
        int remain = (int) Math.max(0, settings.dailyLimit() - todayPushed);
        int pushed = 0;
        int quotaSkipped = 0;
        for (int i = 0; i < candidates.size(); i++) {
            RecommendationCard card = candidates.get(i);
            if (i < remain) {
                pushService.pushRecommendation(userId, card.getId(), summaryLine(card));
                if (cardRepository.markPushed(card.getId(), now) > 0) {
                    pushed++;
                }
            } else if (cardRepository.markSkipped(card.getId(), CardPushStatus.SKIPPED_QUOTA) > 0) {
                pushService.recordSilent(userId, card.getId(), summaryLine(card));
                quotaSkipped++;
                log.info("日上限静默: userId={} cardId={} 已推={}", userId, card.getId(), todayPushed);
            }
        }
        return new GateReport(pushed, 0, quotaSkipped);
    }

    /** 上海当日零点（日配额窗口起点；换日自动重置）。 */
    private static Instant todayStart(Instant now) {
        return LocalDate.ofInstant(now, STAT_ZONE).atStartOfDay(STAT_ZONE).toInstant();
    }

    /** 铃铛摘要行：【动态推荐】{事件类型中文}·{方向词}｜{logicChain}（≤80 字截断）。 */
    static String summaryLine(RecommendationCard card) {
        String line =
                "【动态推荐】"
                        + eventTypeLabel(card.getEventType())
                        + "·"
                        + directionLabel(card.getDirection())
                        + "｜"
                        + card.getLogicChain();
        return line.length() <= SUMMARY_LINE_MAX_LENGTH
                ? line
                : line.substring(0, SUMMARY_LINE_MAX_LENGTH);
    }

    private static String eventTypeLabel(String name) {
        try {
            return EventType.fromName(name).displayName();
        } catch (IllegalArgumentException e) {
            return name; // 冗余列异常值兜底（不阻断推送）
        }
    }

    private static String directionLabel(String name) {
        try {
            return Direction.fromName(name).displayName();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /** 闸门计数（FEED tick 明细数据面）。 */
    public record GateReport(int pushed, int muted, int quotaSkipped) {}

    /** 降噪闸门中间态（放行候选 + 拦截计数）。 */
    private record MuteOutcome(List<RecommendationCard> candidates, int muted) {}
}
