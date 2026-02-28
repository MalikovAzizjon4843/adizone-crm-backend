package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Kanban ustuni sarlavhasidagi ko'rsatkichlar.
 *
 * <p>{@code status} biriktirilmaganlar ustunida null — u bosqich emas,
 * kesim.
 *
 * <p>{@code totalAmount} — {@code Lead.amount} yig'indisi. Summasi yo'q
 * lidlar 0 qo'shadi, ya'ni ustun hech qachon null qaytmaydi: 0 va "ma'lumot
 * yo'q" bir xil ko'rinmasin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadKanbanColumnDto {
    /** Bosqich kodi. Biriktirilmaganlar ustunida null. */
    private String status;
    /** Joriy tildagi nomi. */
    private String statusLabel;
    private long count;
    private BigDecimal totalAmount;
}
