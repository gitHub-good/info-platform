package com.info.platform.domain.valuation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 因子计算共用数学件（包内纯函数）：窗口 age（锚定 snapshotDate、越窗/未来日哨兵）与半衰期衰减。五个因子计算器共用， 公式冻结值见方案 §4.2。 */
final class FactorMath {

    /** 越窗/未来日期哨兵（age &lt; 0 或 age ≥ window）。 */
    static final long OUT_OF_WINDOW = -1L;

    private FactorMath() {}

    /** 日历日 age = snapshotDate − eventDate（floor）；event_date 越窗（含未来日）返回 {@link #OUT_OF_WINDOW}。 */
    static long ageInWindow(LocalDate eventDate, LocalDate snapshotDate, int windowDays) {
        long age = ChronoUnit.DAYS.between(eventDate, snapshotDate);
        if (age < 0 || age >= windowDays) {
            return OUT_OF_WINDOW;
        }
        return age;
    }

    /** 衰减因子 0.5^(ageDays/halfLifeDays)（age≥0 保证 ≤1）。 */
    static double decay(long ageDays, double halfLifeDays) {
        return Math.pow(0.5, ageDays / halfLifeDays);
    }

    /** [0,100] 夹逼。 */
    static double clampHundred(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
