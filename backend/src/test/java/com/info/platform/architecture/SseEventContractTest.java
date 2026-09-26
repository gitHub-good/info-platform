package com.info.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.push.PushType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 跨仓 SSE 事件契约对账断言（M17 T147 / GAP-03，M16 测试报告 §5 可测性缺口——BUG-01 键断裂双绿复发的根因放大器）： 后端 {@link PushType}
 * 全枚举 eventName ∈ 前端 {@code types/notification.ts} 订阅常量集（反向亦然——无悬挂常量），徽章文案表 TYPE_LABELS
 * 全覆盖。<b>断言常驻回归</b>：任一侧改键/新增类型未同步，本测试即红（人为改键演示修前红实证沿 REQ 故事 4 场景 1）。
 *
 * <p>实现裁量（任务 T147）：后端测试直读前端源文件相对路径（surefire 工作目录 = backend 模块目录，同仓 monorepo 内 {@code ../frontend}
 * 恒可达；多候选路径兜底防 IDE 工作目录差异）——最小实现，无需双侧共享常量文件与发布配套。
 */
class SseEventContractTest {

    /** 前端通知类型源文件候选路径（模块目录/仓库根目录两种工作目录形态）。 */
    private static final List<String> FRONTEND_TYPE_FILE_CANDIDATES =
            List.of(
                    "../frontend/src/types/notification.ts",
                    "../../frontend/src/types/notification.ts",
                    "frontend/src/types/notification.ts");

    private static final Pattern EVENT_TYPES_BLOCK =
            Pattern.compile(
                    "NOTIFICATION_EVENT_TYPES\\s*=\\s*\\[(.*?)]\\s*as\\s*const", Pattern.DOTALL);

    private static final Pattern QUOTED_ITEM = Pattern.compile("'([^']+)'");

    private static final Pattern LABELS_BLOCK =
            Pattern.compile("TYPE_LABELS[^=]*=\\s*\\{(.*?)}", Pattern.DOTALL);

    /** TYPE_LABELS 键形态（`anomaly: '异动'`——键非引号）。 */
    private static final Pattern LABEL_KEY_ITEM = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*:");

    @Test
    @DisplayName("GAP-03：PushType 全枚举 eventName 与前端订阅常量集一致（双向）+ 徽章文案全覆盖")
    void pushTypeEventNames_matchFrontendSubscriptionConstants() throws IOException {
        String source = readFrontendNotificationTypes();

        Set<String> frontendConstants = extractSet(source, EVENT_TYPES_BLOCK, QUOTED_ITEM);
        Set<String> backendEventNames = new LinkedHashSet<>();
        for (PushType type : PushType.values()) {
            backendEventNames.add(type.eventName());
        }

        // 方向一：后端每个 SSE 事件名 ∈ 前端订阅集（缺一 = 实时事件铃铛不可达——M16 BUG-01 形态）
        assertThat(backendEventNames)
                .as("后端 PushType.eventName 须全部出现在前端 NOTIFICATION_EVENT_TYPES（新增类型必须同步前端）")
                .allSatisfy(
                        name ->
                                assertThat(frontendConstants)
                                        .as("前端订阅常量缺: %s", name)
                                        .contains(name));

        // 方向二：前端常量无悬挂（后端已删类型的前端残留 = 订阅永不触达的死键）
        assertThat(frontendConstants)
                .as("前端 NOTIFICATION_EVENT_TYPES 不得含后端不存在的常量")
                .allSatisfy(
                        name -> assertThat(backendEventNames).as("后端枚举缺: %s", name).contains(name));

        // 徽章文案面：每个事件名有 TYPE_LABELS 文案（BUG-01 第二症状——徽章英文裸串）
        Set<String> labelKeys = extractSet(source, LABELS_BLOCK, LABEL_KEY_ITEM);
        assertThat(labelKeys).as("前端 TYPE_LABELS 须覆盖全部 SSE 事件名").containsAll(backendEventNames);
    }

    private static String readFrontendNotificationTypes() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String candidate : FRONTEND_TYPE_FILE_CANDIDATES) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
            missing.add(path.toString());
        }
        throw new IllegalStateException(
                "GAP-03 契约对账源文件不可达（前端 types/notification.ts）——候选路径均缺失: " + missing);
    }

    private static Set<String> extractSet(
            String source, Pattern blockPattern, Pattern itemPattern) {
        Matcher block = blockPattern.matcher(source);
        if (!block.find()) {
            throw new IllegalStateException("前端 notification.ts 常量块解析失败（结构变更须同步本对账断言）");
        }
        Set<String> values = new LinkedHashSet<>();
        Matcher item = itemPattern.matcher(block.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("前端 notification.ts 常量块为空（结构变更须同步本对账断言）");
        }
        return values;
    }
}
