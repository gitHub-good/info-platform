package com.info.platform.infrastructure.ai;

/**
 * LLM provider 可变测试夹具（T35 起的热改测试拨动载体；原 {@code LlmConfig.Provider} yml 形状平移，ADR-0020 后 yml 绑定类退役）。
 *
 * <p>{@code ConfigCenterStubs} 按本夹具现算 {@code ConfigCenter} 运行时视图——测试改字段（单价/启停/默认）即模拟页面保存； apiKey
 * 字段模拟环境变量来源（无 DB 密文时按 ENV 解析）。
 */
final class LlmProviderFixtures {

    private LlmProviderFixtures() {}

    /** 可变 provider 形状（字段语义同 {@link LlmDefaults.Provider} + 模拟 ENV 的 apiKey）。 */
    static final class ProviderFixture {
        private String name;
        private String baseUrl;
        private String model;
        private String apiKey = "";
        private boolean enabled = true;
        private boolean isDefault = false;
        private String fallback;
        private double inputPricePerMillion = 0;
        private double outputPricePerMillion = 0;

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        String getBaseUrl() {
            return baseUrl;
        }

        void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        String getModel() {
            return model;
        }

        void setModel(String model) {
            this.model = model;
        }

        String getApiKey() {
            return apiKey;
        }

        void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        boolean isEnabled() {
            return enabled;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        boolean isDefault() {
            return isDefault;
        }

        void setDefault(boolean isDefault) {
            this.isDefault = isDefault;
        }

        String getFallback() {
            return fallback;
        }

        void setFallback(String fallback) {
            this.fallback = fallback;
        }

        double getInputPricePerMillion() {
            return inputPricePerMillion;
        }

        void setInputPricePerMillion(double inputPricePerMillion) {
            this.inputPricePerMillion = inputPricePerMillion;
        }

        double getOutputPricePerMillion() {
            return outputPricePerMillion;
        }

        void setOutputPricePerMillion(double outputPricePerMillion) {
            this.outputPricePerMillion = outputPricePerMillion;
        }
    }

    /** deepseek 默认 provider（ENV key=test-key，价格未配即 0——留待测试按需拨动）。 */
    static ProviderFixture deepseek() {
        ProviderFixture p = new ProviderFixture();
        p.setName("deepseek");
        p.setBaseUrl("https://api.deepseek.com");
        p.setModel("deepseek-flash");
        p.setApiKey("test-key");
        p.setEnabled(true);
        p.setDefault(true);
        p.setFallback("glm");
        return p;
    }

    /** glm fallback provider（ENV key=test-glm-key）。 */
    static ProviderFixture glm() {
        ProviderFixture p = new ProviderFixture();
        p.setName("glm");
        p.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        p.setModel("glm-4-flash-250414");
        p.setApiKey("test-glm-key");
        p.setEnabled(true);
        p.setDefault(false);
        p.setFallback("deepseek");
        return p;
    }

    /** qwen 预留 provider（停用、无 key → NOT_SET，同生产缺省形态）。 */
    static ProviderFixture qwen() {
        ProviderFixture p = new ProviderFixture();
        p.setName("qwen");
        p.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        p.setModel("qwen-plus");
        p.setApiKey(""); // 未配置 key → NOT_SET
        p.setEnabled(false);
        p.setDefault(false);
        p.setFallback("deepseek");
        return p;
    }
}
