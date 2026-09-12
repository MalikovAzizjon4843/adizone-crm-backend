package com.crm.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Qismli tahrir — null qoldirilgan maydon o'zgarmaydi.
 * Mas'ul va muddat uchun alohida endpointlar bor
 * ({@code /reassign}, {@code /postpone}) — ular audit jurnalida
 * alohida amal sifatida ko'rinishi uchun.
 */
@Data
public class TaskUpdateRequest {

    private String title;
    private String description;
    private String type;
    private LocalDateTime dueAt;
    private Boolean allDay;
}
