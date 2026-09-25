package com.info.platform.interfaces.common;

import com.info.platform.application.retention.RetentionConfigFacade;
import com.info.platform.application.retention.RetentionConfigFacade.WindowsUpdate;
import com.info.platform.application.retention.RetentionConfigFacade.WindowsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 留痕窗口接口（T72，方案 §4.3 契约增量）：{@code GET/PATCH /api/v1/retention/windows}。
 *
 * <p>受 JWT 保护（不入 JwtAuthFilter 白名单）。GET 返回当前窗口 + 各表下限/默认（任务中心 RETENTION_CLEANUP 编辑 Dialog
 * 的预填与校验提示数据源）；PATCH 四字段全量整体替换 + 可选 expectedUpdatedAt 并发防呆（30065）——保存即热生效（下一轮清理
 * 现读按新窗口；改大窗口不复活已删行）。无新错误码（2001/30065 复用）。
 */
@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {

    private final RetentionConfigFacade facade;

    public RetentionController(RetentionConfigFacade facade) {
        this.facade = facade;
    }

    /** 当前窗口视图（键缺失时窗口=代码默认、updatedAt=null）。 */
    @GetMapping("/windows")
    public Result<WindowsView> windows() {
        return Result.ok(facade.view());
    }

    /** 全量替换窗口（非法值 2001 字段级、原值保留；expectedUpdatedAt 不符 30065）。 */
    @PatchMapping("/windows")
    public Result<WindowsView> updateWindows(@RequestBody WindowsUpdate update) {
        return Result.ok(facade.update(update));
    }
}
