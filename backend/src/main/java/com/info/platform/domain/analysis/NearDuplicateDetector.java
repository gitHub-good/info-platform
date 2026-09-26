package com.info.platform.domain.analysis;

import com.info.platform.domain.analysis.NewsAnalysisRepository.NewsCandidate;
import java.util.ArrayList;
import java.util.List;

/**
 * L0 近重复判定器（纯 JDK 域服务，M15 T120，方案 §4.2 ② + ADR-0047 阈值勘定）。
 *
 * <p>双段确认：simhash64（标题字元 bigram 加权投票指纹）海明距离预筛（缺省 ≤18——20~60 字 CJK 标题实测「同稿系 ≤16 vs 无关对
 * ≥21」中分，ADR-0047）→ 归一化编辑距离 ≤0.25 确认，且双标题长度均 ≥8（超短标题误并风险高，直接豁免）。主条语义：按 {@code published_at, id}
 * 升序处理，先到者为 PASS 主条，后到相似条 NEAR_DUP 引用之（同刻取 id 小者 = 升序排序天然保证）。 跨语言同稿不合并（如实标注边界，方案 §8）。 序列豁免（M16 T130
 * BUG-03）：{@link SequenceExemptRule} 命中的条目永不作为被并方（PASS 入池作主条候选，真同稿可引用之）。
 *
 * <p>量级：新批 ≤200 条 × 24h 池 ≤700 条海明比较（long 位运算）+ 通过预筛的少量编辑距离（O(60²)），毫秒级。
 */
public final class NearDuplicateDetector {

    /** 缺省参数（海明预筛阈值经 ADR-0047 实测勘定 3 → 18；确认段阈值与长度豁免同方案 §4.2）。 */
    public record DupParams(int simhashDistanceMax, double editDistanceMax, int minTitleLength) {

        public static final DupParams DEFAULTS = new DupParams(18, 0.25, 8);
    }

    /** 单条判定结论（PASS 主条 detail 为 null；NEAR_DUP 带主条引用与距离明细）。 */
    public record Verdict(long newsId, L0Result result, Long nearDupOf, String detail) {}

    /** FNV-1a 64 offset basis / prime（bigram 哈希原语）。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    private static final long FNV_PRIME = 0x100000001b3L;

    /** 标题 simhash64 指纹（字元 bigram → FNV-1a 64 → 逐位加权投票）。 */
    public static long simhash64(String text) {
        if (text == null || text.length() < 2) {
            return 0L;
        }
        int[] weights = new int[64];
        for (int i = 0; i + 1 < text.length(); i++) {
            long hash = bigramHash(text.charAt(i), text.charAt(i + 1));
            for (int bit = 0; bit < 64; bit++) {
                weights[bit] += (hash >>> bit & 1L) == 1L ? 1 : -1;
            }
        }
        long fingerprint = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (weights[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return fingerprint;
    }

    /** 海明距离（两指纹异或后置位计数）。 */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** 归一化编辑距离（Levenshtein / max(len)；双空为 0）。 */
    public static double normalizedLevenshtein(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
            return (a == null || a.isEmpty()) && (b == null || b.isEmpty()) ? 0.0 : 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        return levenshtein(a, b) / (double) maxLen;
    }

    /**
     * 对新批条目逐条判定近重复。
     *
     * @param pool 24h 内 PASS 存量条目（{@code published_at, id} 升序；只读参与比较）
     * @param newItems 本批条目（{@code published_at, id} 升序——主条判定要求时间序先行）
     * @param params 距离/长度阈值
     * @return 与 newItems 等序的判定清单（PASS 条目自动作为后续条目的比较对象——批内同组只留最早主条）
     */
    public List<Verdict> evaluate(
            List<NewsCandidate> pool, List<NewsCandidate> newItems, DupParams params) {
        List<Fingerprinted> candidates = new ArrayList<>(pool.size() + newItems.size());
        for (NewsCandidate entry : pool) {
            candidates.add(Fingerprinted.of(entry));
        }
        List<Verdict> verdicts = new ArrayList<>(newItems.size());
        for (NewsCandidate item : newItems) {
            Verdict verdict = judge(item, candidates, params);
            verdicts.add(verdict);
            if (verdict.result() == L0Result.PASS) {
                // PASS 主条进入比较池——批内后续同稿引用之（主条只一个）
                candidates.add(Fingerprinted.of(item));
            }
        }
        return verdicts;
    }

    /** 单条判定：序列豁免（BUG-03）→ 超短豁免 → 海明预筛 → 编辑距离确认（首命中 = 池内最早相似主条）。 */
    private static Verdict judge(
            NewsCandidate item, List<Fingerprinted> candidates, DupParams params) {
        String title = item.title() == null ? "" : item.title();
        // 序列豁免（T130 BUG-03）：externalId 含 # 或月度标题模式 → 永不作为被并方（仍 PASS 入池作主条候选）
        String exemptToken = SequenceExemptRule.exemptToken(item.externalId(), title);
        if (exemptToken != null) {
            return new Verdict(item.newsId(), L0Result.PASS, null, exemptToken);
        }
        long fingerprint = Fingerprinted.fingerprintOf(title);
        for (Fingerprinted candidate : candidates) {
            if (title.length() < params.minTitleLength()
                    || candidate.title().length() < params.minTitleLength()) {
                // 超短标题（新条目或候选主条）误并风险高，直接豁免
                continue;
            }
            int hamming = hammingDistance(fingerprint, candidate.fingerprint());
            if (hamming > params.simhashDistanceMax()) {
                continue;
            }
            double lev = normalizedLevenshtein(title, candidate.title());
            if (lev <= params.editDistanceMax()) {
                String detail = "hamming=" + hamming + ";lev=" + String.format("%.2f", lev);
                return new Verdict(item.newsId(), L0Result.NEAR_DUP, candidate.newsId(), detail);
            }
        }
        return new Verdict(item.newsId(), L0Result.PASS, null, null);
    }

    /** 编辑距离（两行 DP，O(len²) 空间 O(len)）。 */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    private static long bigramHash(char high, char low) {
        long hash = FNV_OFFSET_BASIS;
        hash ^= high;
        hash *= FNV_PRIME;
        hash ^= low;
        hash *= FNV_PRIME;
        return hash;
    }

    /** 预计算指纹的候选条目（池 + 批内 PASS 主条共用）。 */
    private record Fingerprinted(long newsId, String title, long fingerprint) {

        private static Fingerprinted of(NewsCandidate entry) {
            String title = entry.title() == null ? "" : entry.title();
            return new Fingerprinted(entry.newsId(), title, fingerprintOf(title));
        }

        private static long fingerprintOf(String title) {
            return simhash64(title);
        }
    }
}
