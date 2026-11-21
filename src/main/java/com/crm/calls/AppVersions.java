package com.crm.calls;

/** {@code 1.2.10} ko'rinishidagi versiyalarni raqam bo'laklari bo'yicha solishtirish ({@code -beta} kabi qo'shimcha e'tiborsiz). */
final class AppVersions {

    private AppVersions() {
    }

    static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null || v.isBlank()) {
            return new int[0];
        }
        String core = v.trim().split("[^0-9.]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
