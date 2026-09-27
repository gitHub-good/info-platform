package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 写路径退役 Gate 3 断言（V2.3-M23 T203，方案 §7 闸门三 / ADR-0062 裁决二）。
 *
 * <p>轨 A/B 整链删除的静态与装配面收敛证明——三断言：
 *
 * <ul>
 *   <li><b>Job 面 16</b>：JobRegistry 不再收编 POLICY_FETCH/POLICY_TENDENCY（任务中心「不再出现两 Job」 为 REQ
 *       验收场景；否决种子停用留壳的核心理由）；
 *   <li><b>policy_item 零新增（静态：无写入调用方)</b>：policy domain/infra 写链类全部不存在于运行类路径——
 *       表停写由「写入调用方类缺失」构造性保证（35 行冻结留档，M10 生命周期治理处置）；
 *   <li><b>gov.cn 单一抓取面（静态：GovPolicyClient 不存在）</b>：轨 A 外呼面删除，gov.cn 抓取仅剩 gov_policy 目录源经
 *       RoutingFeedFetcher 一处（批内双抓窗口收口）。
 * </ul>
 *
 * <p>类存在性走 {@code Class.forName}（字符串引用——退役后本测试不改一行仍编译通过，锁定「不可复活」）； contentId 新形态断言在
 * FeedServiceTest（单测面，跨请求稳定）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyTrackRetirementGate3Test {

    /** 轨 B 写链（domain/policy + infrastructure/policy 整包删除清单，方案 §3.2）。 */
    private static final String[] POLICY_WRITE_CHAIN_CLASSES = {
        "com.info.platform.infrastructure.policy.PolicyFetchJob",
        "com.info.platform.infrastructure.policy.PolicyRepositoryImpl",
        "com.info.platform.infrastructure.policy.PolicyItemPO",
        "com.info.platform.infrastructure.policy.PolicyMapper",
        "com.info.platform.domain.policy.PolicyRepository",
        "com.info.platform.domain.policy.PolicyItem",
        "com.info.platform.domain.policy.PolicyIndustryClassifier",
        "com.info.platform.domain.policy.PolicyListFilter",
        "com.info.platform.domain.policy.AiTendency",
        "com.info.platform.application.policy.PolicyTendencyJob",
        "com.info.platform.application.policy.PolicyTendencyService",
    };

    /** 轨 A 外呼面（infrastructure/aggregation POLICY 链 + 路由 bean 面）。 */
    private static final String[] TRACK_A_CLASSES = {
        "com.info.platform.infrastructure.aggregation.GovPolicyClient",
        "com.info.platform.infrastructure.aggregation.PolicySourceAdapter",
        "com.info.platform.infrastructure.aggregation.MockPolicySourceAdapter",
    };

    @Autowired private JobRegistry jobRegistry;

    /** ① Job 面 16：JobRegistry 收编数与 POLICY 双键缺席（18→16，REQ 拍板四）。 */
    @Test
    void jobRegistry_exactlySixteenJobs_policyTrackKeysAbsent() {
        List<String> jobKeys = jobRegistry.jobs().stream().map(ManagedJob::jobKey).toList();

        assertThat(jobRegistry.jobs())
                .as("Job 面 18→16（POLICY_FETCH/POLICY_TENDENCY 整链删除）")
                .hasSize(16);
        assertThat(jobKeys)
                .as("POLICY 双 Job 键不得残留")
                .doesNotContain("POLICY_FETCH", "POLICY_TENDENCY");
    }

    /** ② policy_item 零新增（静态：无写入调用方）——写链类整包不存在 = 停写构造性成立。 */
    @Test
    void policyWriteChainClassesRemoved_noWriterToPolicyItem() {
        for (String fqcn : POLICY_WRITE_CHAIN_CLASSES) {
            assertThat(classExists(fqcn)).as("policy 写链类应已删除: %s", fqcn).isFalse();
        }
    }

    /** ③ gov.cn 单一抓取面（静态：GovPolicyClient 不存在）——轨 A 外呼面删除。 */
    @Test
    void trackAFetchClassesRemoved_govCnSingleFetchSurface() {
        for (String fqcn : TRACK_A_CLASSES) {
            assertThat(classExists(fqcn)).as("轨 A 抓取类应已删除: %s", fqcn).isFalse();
        }
    }

    /** 类路径存在性（字符串引用——被测类删除后本方法仍可用，false = 已退役）。 */
    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, PolicyTrackRetirementGate3Test.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
