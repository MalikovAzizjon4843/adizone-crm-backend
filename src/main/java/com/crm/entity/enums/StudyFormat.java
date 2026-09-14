package com.crm.entity.enums;

import java.util.Locale;

/**
 * O'qish formati — {@code StudentGroup} darajasida, {@code Student} da emas:
 * bitta o'quvchi bir guruhda onlayn, boshqasida oflayn o'qishi mumkin.
 *
 * <p>Lid o'quvchiga aylantirilganda shu qiymat qaysi CONVERTED bosqichiga
 * tushishini hal qiladi ({@code LeadStageService.convertedCodeFor}).
 */
public enum StudyFormat {

    ONLINE,
    OFFLINE;

    public static StudyFormat parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
