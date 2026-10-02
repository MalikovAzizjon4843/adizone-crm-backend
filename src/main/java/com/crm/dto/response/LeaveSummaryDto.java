package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** {@code GET /api/leaves/summary} — yil bo'yicha APPROVED ta'tillar (kvota yo'q, faqat hisobot; §9 Q-F). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveSummaryDto {
    private Long userId;
    private int year;
    /** Kalendar kunlari. */
    private int paidDays;
    private int unpaidDays;
    /** Ish kunlari (Du–Sha, bayramlarsiz) — haqsizdagi oylik ayirmasi shu bo'yicha. */
    private int paidWorkdays;
    private int unpaidWorkdays;
    /** Tur → kalendar kunlari. */
    private Map<String, Integer> byType;
}
