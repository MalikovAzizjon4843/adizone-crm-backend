package com.crm.entity.enums;

/**
 * Oylik KPI pog'onalari nima bo'yicha sanaladi (payroll v3, V88; payroll-v2.md §14):
 * <ul>
 *   <li>{@link #ACTIVE_STUDENTS} — oy oxiridagi faol o'quvchilar (markaz bo'yicha; V82 ma'nosi, standart);</li>
 *   <li>{@link #NEW_STUDENTS} — oy davomida markazga kelgan yangi o'quvchilar ("yangi o'quvchi" ta'rifi —
 *       birinchi real to'lov bilan yopilgan davri shu oyga tushgan; {@code attributed_user} bo'yicha EMAS).</li>
 * </ul>
 */
public enum KpiMetric {

    ACTIVE_STUDENTS,
    NEW_STUDENTS
}
