package com.info.platform.interfaces.newspulse;

import com.info.platform.application.newspulse.NewsPulseService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.newspulse.NewsPulseRepository.PulseRow;
import com.info.platform.domain.newspulse.NewsPulseWindow;
import com.info.platform.interfaces.common.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资讯脉搏接口（V3.2 M28）。
 *
 * <ul>
 *   <li>{@code GET /news-pulse} —— 六窗最新快照总览（windowViews[]，从未分析的窗口 latest=null）
 *   <li>{@code GET /news-pulse/{window}} —— 单窗最新快照（无快照 data=null，前端空态「尚未分析」）
 *   <li>{@code POST /news-pulse/{window}/refresh} —— 手动重算单窗（同步；5min 最小间隔守卫；JOB/MANUAL 留痕）
 * </ul>
 *
 * <p>window ∈ {30m,1h,3h,6h,12h,24h}，非法值 2xxx/400。
 */
@RestController
@RequestMapping("/api/v1/news-pulse")
public class NewsPulseController {

    private final NewsPulseService pulseService;

    public NewsPulseController(NewsPulseService pulseService) {
        this.pulseService = pulseService;
    }

    /** 六窗总览（固定窗序返回，前端 tab 顺序即此序）。 */
    @GetMapping
    public Result<List<Map<String, Object>>> listWindows() {
        Map<String, PulseRow> latestByWindow =
                pulseService.latestEachWindow().stream()
                        .collect(Collectors.toMap(PulseRow::windowKey, Function.identity()));
        List<Map<String, Object>> windows = new ArrayList<>();
        for (NewsPulseWindow window : NewsPulseWindow.values()) {
            windows.add(
                    Map.of(
                            "windowKey", window.code(),
                            "label", window.label(),
                            "latest", latestByWindow.getOrDefault(window.code(), null)));
        }
        return Result.ok(windows);
    }

    /** 单窗最新快照。 */
    @GetMapping("/{window}")
    public Result<PulseRow> latest(@PathVariable String window) {
        return Result.ok(pulseService.latest(parseWindow(window).code()));
    }

    /** 手动重算单窗（同步返回新快照；频率守卫 30094 类业务错误见 service）。 */
    @PostMapping("/{window}/refresh")
    public Result<PulseRow> refresh(@PathVariable String window) {
        return Result.ok(pulseService.analyze(parseWindow(window), true));
    }

    private static NewsPulseWindow parseWindow(String window) {
        try {
            return NewsPulseWindow.ofCode(window);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "window 仅支持 30m/1h/3h/6h/12h/24h");
        }
    }
}
