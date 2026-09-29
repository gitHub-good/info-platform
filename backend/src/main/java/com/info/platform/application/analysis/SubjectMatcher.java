package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 标的池回联（应用层，M15 T121，方案 §4.3）：标题/摘要含池内公司名 → 命中清单作 L1 提示词 companies 提示与 {@code matched_subjects}
 * 留痕（一致性信号 v1——池行业为东财口径且覆盖率不稳，不做自动行业差异比对，ADR-0046 实现附注）。
 *
 * <p>池范围：A_SHARE + HK + US 的 STOCK、status=1、名长 ≥2（M29 T253 扩美股池——{@code l1_market} 由
 * matched_subjects 主市场 派生的原料，方案 §4 C9）。池快照内存缓存 10min TTL（批窗口 10min 对齐一次装载；1.5 万行级 contains
 * 扫描毫秒级）。池加载失败降级为 空池（记 WARN——回联是增强信号，不阻断归类主链路）。
 */
@Service
public class SubjectMatcher {

    /** 单条资讯命中上限（提示词体积护栏；超限取名字更长的前 N 个——更具体主体优先）。 */
    static final int MAX_MATCHES_PER_ITEM = 5;

    /** 池名最小长度（单字名误并风险高，方案 §4.3 名长 ≥2）。 */
    private static final int MIN_NAME_LENGTH = 2;

    private static final Duration POOL_TTL = Duration.ofMinutes(10);

    private final SubjectRepository subjectRepository;
    private final Clock clock;

    private volatile PoolSnapshot poolSnapshot;

    public SubjectMatcher(SubjectRepository subjectRepository, Clock clock) {
        this.subjectRepository = subjectRepository;
        this.clock = clock;
    }

    /** 回联命中（标题或摘要包含池内公司名；按名长降序取前 {@link #MAX_MATCHES_PER_ITEM} 个）。 */
    public List<MatchedSubject> match(String title, String summary) {
        PoolSnapshot snapshot = ensurePool();
        if (snapshot.entries().isEmpty()) {
            return List.of();
        }
        String text = ((title == null ? "" : title) + "\n" + (summary == null ? "" : summary));
        List<MatchedSubject> matched = new ArrayList<>();
        for (PoolEntry entry : snapshot.entries()) { // 已按名长降序
            if (matched.size() >= MAX_MATCHES_PER_ITEM) {
                break;
            }
            if (text.contains(entry.name())) {
                matched.add(
                        new MatchedSubject(
                                entry.code(), entry.name(), entry.industry(), entry.market()));
            }
        }
        return matched;
    }

    /** 强制重载池（测试与池同步后即时刷新入口）。 */
    public synchronized void reloadPool() {
        this.poolSnapshot = loadPool();
    }

    private PoolSnapshot ensurePool() {
        PoolSnapshot snapshot = poolSnapshot;
        if (snapshot == null || snapshot.loadedAt().plus(POOL_TTL).isBefore(clock.instant())) {
            synchronized (this) {
                snapshot = poolSnapshot;
                if (snapshot == null
                        || snapshot.loadedAt().plus(POOL_TTL).isBefore(clock.instant())) {
                    poolSnapshot = loadPool();
                    snapshot = poolSnapshot;
                }
            }
        }
        return snapshot;
    }

    private PoolSnapshot loadPool() {
        try {
            List<PoolEntry> entries = new ArrayList<>();
            for (Subject subject :
                    subjectRepository.loadBucket(Market.A_SHARE, SubjectType.STOCK)) {
                collectEntry(entries, subject);
            }
            for (Subject subject : subjectRepository.loadBucket(Market.HK, SubjectType.STOCK)) {
                collectEntry(entries, subject);
            }
            for (Subject subject : subjectRepository.loadBucket(Market.US, SubjectType.STOCK)) {
                collectEntry(entries, subject);
            }
            entries.sort(Comparator.comparingInt((PoolEntry e) -> e.name().length()).reversed());
            return new PoolSnapshot(List.copyOf(entries), clock.instant());
        } catch (RuntimeException e) {
            // 降级空池：回联是增强信号，池故障不阻断归类主链路（下一条目按 TTL 再试装载）
            LoggerFactory.getLogger(SubjectMatcher.class)
                    .warn("标的池装载失败，本轮回联降级为空池: {}", String.valueOf(e.getMessage()));
            return new PoolSnapshot(List.of(), clock.instant());
        }
    }

    private static void collectEntry(List<PoolEntry> entries, Subject subject) {
        if (subject.getStatus() != SubjectStatus.ENABLED
                || subject.getName() == null
                || subject.getName().length() < MIN_NAME_LENGTH
                || subject.getSubjectCode() == null
                || subject.getMarket() == null) {
            return;
        }
        entries.add(
                new PoolEntry(
                        subject.getSubjectCode().value(),
                        subject.getName(),
                        subject.getIndustry(),
                        subject.getMarket()));
    }

    /**
     * 回联命中项（matched_subjects 列与提示词 companies 的公共载体；M29 T253 增 market——{@code l1_market} 主市场派生原料， 方案
     * §4 C9）。持久化 JSON 形态保持 {"code","name","industry"} 三键（消费侧显式选键，market 不落列）。
     */
    public record MatchedSubject(String code, String name, String industry, Market market) {}

    private record PoolEntry(String code, String name, String industry, Market market) {}

    private record PoolSnapshot(List<PoolEntry> entries, Instant loadedAt) {}
}
