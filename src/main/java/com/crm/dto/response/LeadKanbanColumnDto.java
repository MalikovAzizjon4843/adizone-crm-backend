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
 * <p>{@code totalAmount} HOZIRCHA HAR DOIM null: {@code Lead} entity'sida
 * summa/budjet maydoni yo'q. Maydon shartnomada ataylab qoldirilgan —
 * budjet qo'shilsa javob shakli o'zgarmaydi, faqat null o'rniga son
 * keladi. {@code NON_NULL} qo'llanmagan, ya'ni frontend bu maydon
 * mavjudligini va hozircha bo'sh ekanini ko'rib turadi.
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
