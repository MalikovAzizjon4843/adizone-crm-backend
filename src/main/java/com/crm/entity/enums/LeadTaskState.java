package com.crm.entity.enums;

import java.time.LocalDateTime;

/**
 * Kanban kartasidagi rangli nuqta. Saqlanmaydi — lidning eng yaqin OCHIQ
 * vazifasi muddatidan hisoblanadi (qarang {@code TaskRepository.findOpenByLeadIds}).
 *
 * <p>Chegaralar kesishmaydi: {@link #OVERDUE} muddat allaqachon o'tgan,
 * {@link #TODAY} bugun ichida lekin hali kelmagan, {@link #PLANNED} ertadan keyin.
 * Shu sababli {@code /api/tasks/stats} dagi uchta son ochiq vazifalarni
 * ikki marta sanamaydi.
 */
public enum LeadTaskState {

    /** Ochiq vazifa yo'q — amoCRM'da "Нет задач", kulrang. */
    NONE,
    /** Muddat kelajakda — yashil. */
    PLANNED,
    /** Muddat bugun, hali o'tmagan — sariq. */
    TODAY,
    /** Muddat o'tib ketgan — qizil. */
    OVERDUE;

    public static LeadTaskState resolve(LocalDateTime dueAt, LocalDateTime now) {
        if (dueAt == null) {
            return NONE;
        }
        if (dueAt.isBefore(now)) {
            return OVERDUE;
        }
        if (dueAt.isBefore(now.toLocalDate().plusDays(1).atStartOfDay())) {
            return TODAY;
        }
        return PLANNED;
    }
}
