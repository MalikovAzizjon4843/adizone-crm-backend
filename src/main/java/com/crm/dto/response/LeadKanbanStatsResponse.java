package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Kanban sarlavhalari uchun hisoblagichlar.
 *
 * <p>{@code columns} — enum tartibida, bo'sh bosqichlar ham {@code count = 0}
 * bilan qaytadi: frontend ustunlarni shu ro'yxatdan qurishi mumkin va
 * bosqich qo'shilganda kod o'zgarmaydi.
 *
 * <p>{@code unassigned} — amoCRM'dagi "Неразобранное": {@code assignedUser}
 * yo'q lidlar, BOSQICHIDAN QAT'I NAZAR. Shu sababli u {@code columns}
 * bilan kesishadi va yig'indiga qo'shilmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadKanbanStatsResponse {
    private List<LeadKanbanColumnDto> columns;
    private LeadKanbanColumnDto unassigned;
}
