package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * amoCRM voronka sarlavhasidagi hisoblagichlar.
 *
 * <p>{@code overdue + today + upcoming} = jami ochiq vazifalar
 * (chegaralar kesishmaydi).
 *
 * <p>{@code noTask} — boshqa uchtasidan mustaqil: u LIDLARni sanaydi,
 * vazifalarni emas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatsResponse {
    private long overdue;
    private long today;
    private long upcoming;
    /** Ochiq vazifasi yo'q, yopilmagan lidlar soni. */
    private long noTask;
    /** SALES_MANAGER uchun bo'sh — u faqat o'z sonlarini ko'radi. */
    private List<TaskUserStatsDto> byUser;
}
