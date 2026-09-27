package com.info.platform.domain.valuation;

import java.util.Locale;

/**
 * 评分引擎参数（{@code score.weight} 键载荷，M20 方案 §4.3 冻结缺省；HeatParams 先例）：五维权重 + 双窗 + 半衰期 + 双饱和常数 +
 * 「有突破」三阈值。basis() 派生参数指纹串（复算审计锚，ADR-0058 裁决 4）。
 *
 * <p>参数热改（ADR-0017 快照热读）→ 下一轮 FACTOR_SNAPSHOT 按新参数计算；非法组合由写路径校验器拦截（T172，30087），
 * 读侧（ValuationSettings）字段级回退缺省。
 *
 * @param wCatalyst F1 权重（缺省 0.40，主维）
 * @param wConduction F2 权重（缺省 0.20）
 * @param wFundamental F3 权重（缺省 0.20）
 * @param wRisk F4 权重（缺省 0.20）
 * @param wValuation F5 权重（缺省 0.00——条件因子默认不进基线，ADR-0058 裁决 5）
 * @param catalystWindowDays 事件窗 W1（缺省 10，可配 5~30）
 * @param assocWindowDays 行业关联窗 W2（缺省 30，可配 10~60）
 * @param halfLifeDays 事件衰减半衰期（缺省 5.0 天，可配 1~15）
 * @param k1Saturation F1 饱和常数 K1（缺省 3.0）
 * @param k3Saturation F3 tanh 缩放常数 K3（缺省 1.5）
 * @param btCatalystMin 「有突破」F1 下限（缺省 20，M21 T180 校准 OBS-M20-2：一条 3 日内 HIGH 利好 ≈ raw 0.75 → F1=20）
 * @param btConductionMin 「有突破」F2 下限（缺省 50）
 * @param btRiskMin 「有突破」F4 下限（缺省 80）
 */
public record ValuationParams(
        double wCatalyst,
        double wConduction,
        double wFundamental,
        double wRisk,
        double wValuation,
        int catalystWindowDays,
        int assocWindowDays,
        double halfLifeDays,
        double k1Saturation,
        double k3Saturation,
        int btCatalystMin,
        int btConductionMin,
        int btRiskMin) {

    /** 代码缺省（方案 §4.3 键文档冻结值；btCatalystMin 60→20 为 M21 T180 校准——V31 迁移守卫同步既有 DB 行）。 */
    public static ValuationParams defaults() {
        return new ValuationParams(0.40, 0.20, 0.20, 0.20, 0.00, 10, 30, 5.0, 3.0, 1.5, 20, 50, 80);
    }

    /** 权重和（Σ&gt;0 由校验器保证；读侧兜底全零回落缺省）。 */
    public double weightSum() {
        return wCatalyst + wConduction + wFundamental + wRisk + wValuation;
    }

    /**
     * 参数指纹串（快照行 weight_basis 直落；§4.3 例：{@code vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;
     * hl=5.0;k=3.0|1.5;bt=60|50|80}）——历史快照按当时参数可复现的审计锚。
     */
    public String basis() {
        return "vs-v1:w="
                + weight(wCatalyst)
                + "|"
                + weight(wConduction)
                + "|"
                + weight(wFundamental)
                + "|"
                + weight(wRisk)
                + "|"
                + weight(wValuation)
                + ";win="
                + catalystWindowDays
                + "|"
                + assocWindowDays
                + ";hl="
                + halfLifeDays
                + ";k="
                + k1Saturation
                + "|"
                + k3Saturation
                + ";bt="
                + btCatalystMin
                + "|"
                + btConductionMin
                + "|"
                + btRiskMin;
    }

    private static String weight(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * weight_basis 指纹串回读（详情端点按<b>当时权重</b>展示分解的解析锚，ADR-0058 裁决 4）： {@code
     * vs-v1:w=…;win=…;hl=…;k=…;bt=…} → 参数；损坏段回退缺省（历史快照不因解析失败 500）。
     */
    public static ValuationParams fromBasis(String basis) {
        ValuationParams defaults = defaults();
        if (basis == null || basis.isBlank() || !basis.startsWith("vs-v1:")) {
            return defaults;
        }
        try {
            ValuationParams params = defaults;
            for (String section : basis.substring("vs-v1:".length()).split(";")) {
                int eq = section.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = section.substring(0, eq);
                String[] values = section.substring(eq + 1).split("\\|");
                params = assign(params, key, values);
            }
            return params;
        } catch (Exception e) {
            return defaults;
        }
    }

    private static ValuationParams assign(ValuationParams params, String key, String[] values) {
        return switch (key) {
            case "w" -> params.withWCatalyst(Double.parseDouble(values[0]))
                    .withWConduction(Double.parseDouble(values[1]))
                    .withWFundamental(Double.parseDouble(values[2]))
                    .withWRisk(Double.parseDouble(values[3]))
                    .withWValuation(Double.parseDouble(values[4]));
            case "win" -> params.withCatalystWindowDays(Integer.parseInt(values[0]))
                    .withAssocWindowDays(Integer.parseInt(values[1]));
            case "hl" -> params.withHalfLifeDays(Double.parseDouble(values[0]));
            case "k" -> params.withK1Saturation(Double.parseDouble(values[0]))
                    .withK3Saturation(Double.parseDouble(values[1]));
            case "bt" -> params.withBtCatalystMin(Integer.parseInt(values[0]))
                    .withBtConductionMin(Integer.parseInt(values[1]))
                    .withBtRiskMin(Integer.parseInt(values[2]));
            default -> params;
        };
    }

    // ---- 单参数 wither（配置读取侧逐字段回退用；记录不可变） ----

    public ValuationParams withWCatalyst(double value) {
        return new ValuationParams(
                value,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withWConduction(double value) {
        return new ValuationParams(
                wCatalyst,
                value,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withWFundamental(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                value,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withWRisk(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                value,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withWValuation(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                value,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withCatalystWindowDays(int value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                value,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withAssocWindowDays(int value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                value,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withHalfLifeDays(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                value,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withK1Saturation(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                value,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withK3Saturation(double value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                value,
                btCatalystMin,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withBtCatalystMin(int value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                value,
                btConductionMin,
                btRiskMin);
    }

    public ValuationParams withBtConductionMin(int value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                value,
                btRiskMin);
    }

    public ValuationParams withBtRiskMin(int value) {
        return new ValuationParams(
                wCatalyst,
                wConduction,
                wFundamental,
                wRisk,
                wValuation,
                catalystWindowDays,
                assocWindowDays,
                halfLifeDays,
                k1Saturation,
                k3Saturation,
                btCatalystMin,
                btConductionMin,
                value);
    }
}
