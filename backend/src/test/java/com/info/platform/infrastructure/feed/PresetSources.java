package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;

/** 预置源目录条目 → 实体（单测直取 InfoSourceCatalog 现行行，配置漂移红灯）。 */
final class PresetSources {

    private PresetSources() {}

    static InfoSource fromCode(String sourceCode) {
        InfoSourceCatalog.PresetEntry entry =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals(sourceCode))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("目录缺预置源: " + sourceCode));
        return InfoSource.create(
                entry.sourceCode(),
                entry.name(),
                entry.category(),
                entry.adapterType(),
                entry.adapterRef(),
                entry.endpoint(),
                new SourceConfigCodec().parse(entry.configJson()),
                entry.intervalMinutes(),
                true,
                true);
    }
}
