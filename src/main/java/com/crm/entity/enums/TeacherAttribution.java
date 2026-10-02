package com.crm.entity.enums;

/**
 * {@code billing_periods.teacher_source} / {@code balance_transactions.teacher_source} —
 * o'qituvchi qayerdan olingani (payroll-v2 §8).
 */
public final class TeacherAttribution {

    /** Davr/dars yozilgan paytdagi {@code group.teacher}. */
    public static final String LIVE = "LIVE";

    /** Taxminiy: V54 backfill yoki migratsiya — hozirgi {@code group.teacher}. */
    public static final String ESTIMATED = "ESTIMATED";

    private TeacherAttribution() {
    }
}
