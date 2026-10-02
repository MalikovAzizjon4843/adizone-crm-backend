package com.crm.dashboard;

import com.crm.entity.StudentGroup;
import com.crm.entity.enums.TrialOutcome;

import java.time.LocalDate;

/**
 * Sinov yozilmasi hayot sikli belgilari (director-dashboard §1.5, §3.3, G6). Yozilmaning
 * o'z maydonlarini o'zgartiradi; saqlash — chaqiruvchida (u SG ni allaqachon saqlaydi).
 *
 * <p>Boshlang'ich {@code IN_TRIAL} — {@code StudentGroup.@PrePersist} da (har yaratish yo'li).
 */
public final class TrialTracking {

    private TrialTracking() {
    }

    /** Sinovda birinchi PRESENT/LATE: eng erta sana saqlanadi (orqaga sanalangan davomat ham). */
    public static boolean markAttended(StudentGroup sg, LocalDate lessonDate) {
        if (!Boolean.TRUE.equals(sg.getIsTrial()) || lessonDate == null) {
            return false;
        }
        if (sg.getTrialStartedAt() != null && !lessonDate.isBefore(sg.getTrialStartedAt())) {
            return false;
        }
        sg.setTrialStartedAt(lessonDate);
        if (sg.getTrialOutcome() == null) {
            sg.setTrialOutcome(TrialOutcome.IN_TRIAL);
            sg.setTrialSource("LIVE");
        }
        return true;
    }

    /** Sinov → to'lovli (to'lov yoki {@code payment-start-date} bilan). */
    public static void markConverted(StudentGroup sg, LocalDate day) {
        if (sg.getTrialOutcome() == TrialOutcome.CONVERTED) {
            return;
        }
        sg.setTrialConvertedAt(day);
        sg.setTrialOutcome(TrialOutcome.CONVERTED);
        if (sg.getTrialSource() == null) {
            sg.setTrialSource("LIVE");
        }
    }

    /** Yozilma qayta sinovga qaytarildi ({@code isTrial = true}). */
    public static void markTrialAgain(StudentGroup sg) {
        sg.setTrialOutcome(TrialOutcome.IN_TRIAL);
        sg.setTrialConvertedAt(null);
        if (sg.getTrialSource() == null) {
            sg.setTrialSource("LIVE");
        }
    }

    /** Sinovdagi yozilma yopildi: kelgan bo'lsa LEFT, hech kelmagan bo'lsa NO_SHOW. */
    public static void markClosed(StudentGroup sg) {
        if (!Boolean.TRUE.equals(sg.getIsTrial())) {
            return;
        }
        sg.setTrialOutcome(sg.getTrialStartedAt() != null ? TrialOutcome.LEFT : TrialOutcome.NO_SHOW);
        if (sg.getTrialSource() == null) {
            sg.setTrialSource("LIVE");
        }
    }
}
