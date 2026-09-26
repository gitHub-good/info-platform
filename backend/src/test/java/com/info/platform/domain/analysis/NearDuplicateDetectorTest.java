package com.info.platform.domain.analysis;

import static com.info.platform.domain.analysis.NearDuplicateDetector.DupParams;
import static com.info.platform.domain.analysis.NearDuplicateDetector.Verdict;
import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.NewsAnalysisRepository.NewsCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * NearDuplicateDetector 单测（T120，方案 §4.2 ② / §6 L0 组）：simhash 稳定性与海明边界（改 1~3 字 vs 4+ 字）、 编辑距离阈值、
 * minTitleLength 豁免、主条取最早 published_at（同刻取 id 小者）、跨源同稿实测样本回放（2110/2113 双源同题）。 AAA 结构，纯函数零依赖。
 */
class NearDuplicateDetectorTest {

    private static final Instant BASE = Instant.parse("2026-09-22T01:00:00Z");

    private final NearDuplicateDetector detector = new NearDuplicateDetector();

    private static NewsCandidate item(long id, String title, int plusMinutes) {
        return item(id, null, title, plusMinutes);
    }

    private static NewsCandidate item(long id, String externalId, String title, int plusMinutes) {
        return new NewsCandidate(
                id,
                1L,
                externalId,
                title,
                "摘要",
                null,
                BASE.plusSeconds(plusMinutes * 60L),
                BASE.plusSeconds(600));
    }

    // ---- simhash / 距离原语 ----

    @Test
    void simhash_sameTitleSameHash_stable() {
        String title = "央行宣布下调存款准备金率0.5个百分点";
        assertThat(NearDuplicateDetector.simhash64(title))
                .isEqualTo(NearDuplicateDetector.simhash64(title));
        // 不同标题指纹不同（非退化）
        assertThat(NearDuplicateDetector.simhash64(title))
                .isNotEqualTo(NearDuplicateDetector.simhash64("锂电池排产持续回暖，上游材料价格企稳"));
    }

    @Test
    void simhash_blankOrSingleChar_safeZero() {
        assertThat(NearDuplicateDetector.simhash64(null)).isZero();
        assertThat(NearDuplicateDetector.simhash64("")).isZero();
        assertThat(NearDuplicateDetector.simhash64("涨")).isZero(); // 无 bigram
    }

    @Test
    void hamming_matchesBitCount() {
        assertThat(NearDuplicateDetector.hammingDistance(0b1010L, 0b1001L)).isEqualTo(2);
        assertThat(NearDuplicateDetector.hammingDistance(0L, 0L)).isZero();
        assertThat(NearDuplicateDetector.hammingDistance(-1L, 0L)).isEqualTo(64);
    }

    @Test
    void normalizedLevenshtein_boundaries() {
        assertThat(NearDuplicateDetector.normalizedLevenshtein("abcdef", "abcdef")).isZero();
        assertThat(NearDuplicateDetector.normalizedLevenshtein("abcdef", "abcdeg"))
                .isEqualTo(1.0 / 6);
        assertThat(NearDuplicateDetector.normalizedLevenshtein("", "")).isZero();
        assertThat(NearDuplicateDetector.normalizedLevenshtein("", "abcd")).isEqualTo(1.0);
        assertThat(NearDuplicateDetector.normalizedLevenshtein("abcd", "abZZ")).isEqualTo(0.5);
    }

    // ---- 判定主路径 ----

    @Test
    void evaluate_identicalTitle_laterOneIsNearDup() {
        NewsCandidate main = item(100, "美元兑日元USD/JPY日内下跌1.00%，现报157.25", 0);
        NewsCandidate dup = item(200, "美元兑日元USD/JPY日内下跌1.00%，现报157.25", 5);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(main, dup), DupParams.DEFAULTS);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(100L);
        assertThat(verdicts.get(1).detail()).startsWith("hamming=").contains(";lev=");
    }

    @Test
    void evaluate_crossSourceSameTitle_replays2110And2113() {
        // 实测样本回放（附录 A）：2113「美元兑日元USD/JPY日内下跌1.00%」被 2110 加前缀装饰转载（快讯常见形态）——先到者为主条
        NewsCandidate ths = item(2113, "美元兑日元USD/JPY日内下跌1.00%", 0);
        NewsCandidate decorated = item(2110, "【快讯】美元兑日元USD/JPY日内下跌1.00%", 2);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(ths, decorated), DupParams.DEFAULTS);

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(2113L);
    }

    @Test
    void evaluate_longSourceSpecificTail_beyondConfirmThreshold_staysPass() {
        // 附录 A 字面样本（2110 原文带 10 字尾「，现报157.25。」）：lev=10/31≈0.32 > 0.25——按方案确认段阈值不合并
        // （ADR-0047：精细边界由编辑距离承担，该形态如实落 PASS 而非静默合并）
        NewsCandidate shortTitle = item(2113, "美元兑日元USD/JPY日内下跌1.00%", 0);
        NewsCandidate tailed = item(2110, "美元兑日元USD/JPY日内下跌1.00%，现报157.25。", 2);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(shortTitle, tailed), DupParams.DEFAULTS);

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).nearDupOf()).isNull();
    }

    @Test
    void evaluate_smallEdits_withinHammingBoundary_nearDup() {
        // 改 1~3 字：标题尾部数字微调（快讯滚动常见形态）
        NewsCandidate a = item(1, "沪深两市今日成交额突破1.2万亿元创年内新高", 0);
        NewsCandidate b = item(2, "沪深两市今日成交额突破1.3万亿元创年内新高", 3);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(a, b), DupParams.DEFAULTS);

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(1L);
    }

    @Test
    void evaluate_manyEdits_beyondBoundary_pass() {
        // 4+ 字差异且长度不同的独立标题：不得误并
        NewsCandidate a = item(1, "国家发改委召开专题会议部署四季度稳增长重点工作", 0);
        NewsCandidate b = item(2, "欧洲央行管委表示需要对通胀保持足够警惕", 3);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(a, b), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).nearDupOf()).isNull();
    }

    @Test
    void evaluate_editDistanceThreshold_boundary() {
        // 24 字标题改 6 字 = 0.25（边界，含）；改 7 字 ≈ 0.29（阈值外）——同长度标题直接用编辑距离控制变量
        // （ADR-0047：海明预筛只剪无关对，精细边界由确认段承担）
        String base = "半导体设备公司发布第三季度业绩预告净利润同比预增";
        NewsCandidate main = item(1, base, 0);

        StringBuilder six = new StringBuilder(base);
        six.replace(18, 24, "甲乙丙丁戊己");
        NewsCandidate edge = item(2, six.toString(), 1);
        List<Verdict> edgeVerdicts =
                detector.evaluate(List.of(), List.of(main, edge), DupParams.DEFAULTS);

        StringBuilder seven = new StringBuilder(base);
        seven.replace(17, 24, "庚辛壬癸甲乙丙");
        NewsCandidate over = item(3, seven.toString(), 2);
        List<Verdict> overVerdicts =
                detector.evaluate(List.of(), List.of(main, over), DupParams.DEFAULTS);

        assertThat(edgeVerdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(overVerdicts.get(1).result()).isEqualTo(L0Result.PASS);
    }

    @Test
    void evaluate_shortTitle_exemptFromMerging() {
        // minTitleLength=8：7 字短标题与同文长标题不并（超短误并风险高，直接豁免为 PASS）
        NewsCandidate longMain = item(1, "美联储主席鲍威尔表示未来政策路径取决于数据", 0);
        NewsCandidate shortTitle = item(2, "美联储议息", 5);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(longMain, shortTitle), DupParams.DEFAULTS);

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).nearDupOf()).isNull();
    }

    @Test
    void evaluate_poolEntryTooShort_notUsedAsMain() {
        // 池内短标题同样豁免：7 字池条目不作主条吸收后续长标题
        NewsCandidate shortPool = item(1, "央行降准落地", 0);
        NewsCandidate later = item(2, "央行降准落地释放长期资金约一万亿元", 5);

        List<Verdict> verdicts =
                detector.evaluate(List.of(shortPool), List.of(later), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(0).nearDupOf()).isNull();
    }

    @Test
    void evaluate_sameTimestamp_smallerIdIsMain() {
        // 同刻取 id 小者：升序处理天然保证（2113 同刻晚到 → 引用 2110）
        NewsCandidate a = item(2113, "多家银行下调存款挂牌利率十个基点", 0);
        NewsCandidate b = item(2110, "多家银行下调存款挂牌利率十个基点", 0);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(b, a), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(2110L);
    }

    @Test
    void evaluate_poolMatch_earliestPoolEntryWins() {
        // 池内已有多条相似：引用最早（pool 升序首命中）
        NewsCandidate early = item(10, "证监会就程序化交易管理规定公开征求意见", 0);
        NewsCandidate late = item(20, "证监会就程序化交易管理规定公开征求意见稿说明", 5);
        NewsCandidate incoming = item(30, "证监会就程序化交易管理规定公开征求意见", 10);

        List<Verdict> verdicts =
                detector.evaluate(List.of(early, late), List.of(incoming), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(0).nearDupOf()).isEqualTo(10L);
    }

    @Test
    void evaluate_chainOfDups_eachReferencesSameMain() {
        // 三连同稿：全部引用最早主条（主条只一个）
        NewsCandidate a = item(1, "利比亚国家石油公司表示沙拉拉油田遭遇不可抗力停产", 0);
        NewsCandidate b = item(2, "利比亚国家石油公司表示沙拉拉油田遭遇不可抗力停产", 4);
        NewsCandidate c = item(3, "利比亚国家石油公司表示沙拉拉油田遭遇不可抗力停产", 9);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(a, b, c), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(1L);
        assertThat(verdicts.get(2).nearDupOf()).isEqualTo(1L);
    }

    @Test
    void evaluate_customParams_tighterThreshold() {
        // 阈值收紧（editDistanceMax=0.1）：改 5 字（lev≈0.19）不再合并——海明预筛或确认段任一拦截即 PASS
        String base = "半导体设备公司发布第三季度业绩预告同比增长百分之四十";
        NewsCandidate main = item(1, base, 0);
        StringBuilder five = new StringBuilder(base);
        five.replace(20, 25, "八九十甲乙");
        NewsCandidate edited = item(2, five.toString(), 1);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(main, edited), new DupParams(18, 0.10, 8));

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.PASS);
    }

    @Test
    void evaluate_emptyInputs_emptyVerdicts() {
        assertThat(detector.evaluate(List.of(), List.of(), DupParams.DEFAULTS)).isEmpty();
    }

    // ---- T130 · BUG-03 序列豁免（M15 测试报告 P2：CPI 月度序列 19 条被误并；M16 方案 §4.2）----

    @Test
    void evaluate_monthlySeriesTitles_neverMarkedNearDup() {
        // BUG-03 回归（修前红）：CPI 月度序列标题逐月仅数字不同，双段判定下后到月份被误并 NEAR_DUP——
        // 序列豁免后两条各自 PASS（19 条恢复各自 L1 归类与 L2 事件资格）
        NewsCandidate aug = item(101, "CPI：2026年08月份 同比 0.8%", 0);
        NewsCandidate sep = item(102, "CPI：2026年09月份 同比 0.7%", 5);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(aug, sep), DupParams.DEFAULTS);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).result())
                .as("月度序列条目永不标记 NEAR_DUP（BUG-03 豁免）")
                .isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(1).nearDupOf()).isNull();
    }

    @Test
    void evaluate_nonSeriesNearDuplicate_stillMerged_regression() {
        // 回归红线：豁免只作用于两类强序列信号，普通近重复照并（既有语义零变化）
        NewsCandidate a = item(301, "某公司公告回购股份计划提振市场信心", 0);
        NewsCandidate b = item(302, "某公司公告回购股份计划提振市场信心", 5);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(a, b), DupParams.DEFAULTS);

        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(301L);
    }

    @Test
    void evaluate_volumeMarkedExternalId_exemptWithDetailToken() {
        // 谓词①：externalId 含 #（标题无月度模式）→ PASS + seq-exempt:# 留痕
        NewsCandidate marked = item(401, "RPT_ECONOMY_CPI#2026-08-01", "全国居民消费价格指数发布", 0);
        NewsCandidate later = item(402, "全国居民消费价格指数发布", 3);

        List<Verdict> verdicts =
                detector.evaluate(List.of(), List.of(marked, later), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(0).detail()).isEqualTo("seq-exempt:#");
        // 豁免条目仍入池作主条候选：无 # 的真同稿照常引用之（方案 §4.2「真同稿可引用之」）
        assertThat(verdicts.get(1).result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(verdicts.get(1).nearDupOf()).isEqualTo(401L);
    }

    @Test
    void evaluate_monthlyExemptCarriesMonthlyDetailToken() {
        NewsCandidate monthly = item(501, "CPI：2026年08月份 同比 0.8%", 0);

        List<Verdict> verdicts = detector.evaluate(List.of(), List.of(monthly), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(0).detail()).isEqualTo("seq-exempt:monthly");
        assertThat(verdicts.get(0).nearDupOf()).isNull();
    }

    @Test
    void evaluate_exemptCandidateVersusPoolMain_stillPass() {
        // 池内已有相似主条，豁免候选到达 → 不并（豁免条目永不作为被并方）
        NewsCandidate poolMain = item(10, "PMI：2026年08月份 制造业指数 49.8", 0);
        NewsCandidate incoming = item(11, "PMI：2026年09月份 制造业指数 49.6", 10);

        List<Verdict> verdicts =
                detector.evaluate(List.of(poolMain), List.of(incoming), DupParams.DEFAULTS);

        assertThat(verdicts.get(0).result()).isEqualTo(L0Result.PASS);
        assertThat(verdicts.get(0).detail()).isEqualTo("seq-exempt:monthly");
    }
}
