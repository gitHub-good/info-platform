package com.info.platform.domain.feed;

/**
 * robots.txt 判读端口（M13 T105，方案 §4.5/§5 合规红线）：保存时硬拦截（30075）+ 连通性测试复判。
 *
 * <p>判读口径对齐普查报告 §1.1（RFC 9309 子集）：{@code 200 且含 Disallow} → 逐条遵守； 404 / 空文件 / 302 跳页 / 403 / 5xx /
 * 网络不可达 → 按无限制处理（注记原因，可观测）。仅显式命中通配组 {@code Disallow} 规则才拒绝。
 */
public interface RobotsPolicyChecker {

    /**
     * 判读端点是否允许抓取。
     *
     * @param endpoint 拉取端点（http/https）
     * @return 判读结论（allowed=false 时 note 含命中规则，供 30075 文案与诊断）
     */
    RobotsVerdict check(String endpoint);

    /**
     * 判读结论。
     *
     * @param allowed 是否允许抓取
     * @param note 判读依据（robots 状态 / 命中规则 / 不可达原因——页面诊断与留档用）
     */
    record RobotsVerdict(boolean allowed, String note) {}
}
