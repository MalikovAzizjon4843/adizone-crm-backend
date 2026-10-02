package com.crm.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Foiz, o'rtacha, mediana — dashboard ko'rsatkichlari uchun (pul emas; 1 kasr). */
public final class Ratios {

    private Ratios() {
    }

    /** {@code part / whole × 100}, 1 kasr; maxraj 0 bo'lsa null. */
    public static BigDecimal percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    /** Pastki mediana (juft sonda — kichigi), qiymatlar tartiblanadi. */
    public static Long median(List<Long> values) {
        return quantile(values, 0.5);
    }

    /** Nearest-rank kvantil. */
    public static Long quantile(List<Long> values, double q) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
