package com.info.platform.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * InfoSource 实体单测（T100，ADR-0038）：频控红线 1~60 分钟在构造/编辑两路径把守；preset/adapter_ref 一致性； 软删语义（停用 +
 * 可恢复）。AAA 结构。
 */
class InfoSourceTest {

    private static InfoSource rssSource(int intervalMinutes) {
        return InfoSource.create(
                "t100_demo", "演示源", "媒体", AdapterType.RSS, null,
                "https://example.com/rss", SourceConfig.empty(), intervalMinutes, true, false);
    }

    @Test
    void create_intervalWithinBounds_accepted() {
        InfoSource min = rssSource(InfoSource.MIN_INTERVAL_MINUTES);
        InfoSource max = rssSource(InfoSource.MAX_INTERVAL_MINUTES);

        assertThat(min.getIntervalMinutes()).isEqualTo(1);
        assertThat(max.getIntervalMinutes()).isEqualTo(60);
        assertThat(min.isEnabled()).isTrue();
        assertThat(min.isPreset()).isFalse();
        assertThat(min.isDeleted()).isFalse();
        assertThat(min.getConfig()).isNotNull();
    }

    @Test
    void create_intervalOutOfRange_rejectedOnBothBounds() {
        assertThatThrownBy(() -> rssSource(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalMinutes");
        assertThatThrownBy(() -> rssSource(61))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~60");
    }

    @Test
    void create_blankRequiredFields_rejected() {
        assertThatThrownBy(
                        () ->
                                InfoSource.create(
                                        " ", "名", "媒体", AdapterType.RSS, null,
                                        "https://example.com/rss", SourceConfig.empty(), 15, true,
                                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceCode");
        assertThatThrownBy(
                        () ->
                                InfoSource.create(
                                        "t100_x", "名", "媒体", AdapterType.RSS, null, "  ",
                                        SourceConfig.empty(), 15, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void create_presetAdapterRefConsistency_enforced() {
        // preset 未声明 bean 名 → 拒绝
        assertThatThrownBy(
                        () ->
                                InfoSource.create(
                                        "t100_p1", "预置", "快讯", AdapterType.PRESET, null,
                                        "https://example.com/api", SourceConfig.empty(), 5, true,
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter_ref");
        // 非 preset 声明 bean 名 → 拒绝
        assertThatThrownBy(
                        () ->
                                InfoSource.create(
                                        "t100_p2", "混装", "快讯", AdapterType.RSS,
                                        "someAdapterBean", "https://example.com/rss",
                                        SourceConfig.empty(), 5, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 preset");
    }

    @Test
    void edit_updatesMutableFields_andKeepsIdentity() {
        InfoSource source = rssSource(15);

        source.edit("新名", "自建", "https://example.com/rss2", SourceConfig.empty(), 30, false);

        assertThat(source.getName()).isEqualTo("新名");
        assertThat(source.getCategory()).isEqualTo("自建");
        assertThat(source.getEndpoint()).isEqualTo("https://example.com/rss2");
        assertThat(source.getIntervalMinutes()).isEqualTo(30);
        assertThat(source.isEnabled()).isFalse();
        // 身份字段不可变：sourceCode/adapterType/preset
        assertThat(source.getSourceCode()).isEqualTo("t100_demo");
        assertThat(source.getAdapterType()).isEqualTo(AdapterType.RSS);
        assertThat(source.isPreset()).isFalse();
    }

    @Test
    void edit_intervalOutOfRange_rejected() {
        InfoSource source = rssSource(15);

        assertThatThrownBy(() -> source.edit(null, null, null, null, 61, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1~60");
    }

    @Test
    void markDeleted_disablesAndIsReversible() {
        InfoSource source = rssSource(15);

        source.markDeleted();
        assertThat(source.isDeleted()).isTrue();
        assertThat(source.isEnabled()).isFalse();

        // 软删可恢复（配置归档可逆，蓝图故事 1 场景 4）
        source.restore();
        assertThat(source.isDeleted()).isFalse();
    }
}
