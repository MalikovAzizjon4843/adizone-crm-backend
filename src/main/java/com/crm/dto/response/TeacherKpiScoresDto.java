package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 4 mezonli o'qituvchi KPI. Har ko'rsatkich maxraji 0 bo'lsa — {@code null} ("ma'lumot yetarli
 * emas"), 100% emas. {@code overallScore} — mavjud (null bo'lmagan) ko'rsatkichlar o'rtachasi;
 * hammasi null bo'lsa null va {@code insufficientData = true}.
 *
 * <ul>
 *   <li>{@code attendanceRate} — oraliqdagi belgilangan davomat: (PRESENT + LATE) / hammasi;</li>
 *   <li>{@code paymentRate} — muddati oraliqda kelgan, natijasi ma'lum davrlardan to'langani;</li>
 *   <li>{@code onTimePaymentRate} — o'shalardan {@code grace_until} gacha to'langani;</li>
 *   <li>{@code retentionRate} — (oxirida ochiq + bitirgan) / (… + ketgan).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherKpiScoresDto {
    private Double attendanceRate;
    private Double paymentRate;
    private Double onTimePaymentRate;
    private Double retentionRate;
    /** null agar ma'lumot yetarli emas */
    private Double overallScore;
    private Boolean insufficientData;

    /** "YYYY-MM" — oy bo'yicha so'ralganda; oraliq (from/to) bo'yicha — null. */
    private String month;
    /** LIVE — jonli hisob; SNAPSHOT — teacher_kpi_monthly dan (yopilgan oy). */
    private String source;
    /** SNAPSHOT bo'lsa — yozilgan vaqt. */
    private LocalDateTime computedAt;

    // ── xom sonlar (foiz qanday chiqqani) ──────────────────────────────
    private Long attendancePresent;
    private Long attendanceTotal;
    /** Muddati oraliqda: to'langan yoki grace tugagan (to'lov ko'rsatkichlari maxraji). */
    private Long periodsDecided;
    private Long periodsPaid;
    private Long periodsOnTime;
    /** Hali grace ichida va to'lanmagan — maxrajga kirmaydi. */
    private Long periodsPending;
    private Long openAtEnd;
    private Long graduated;
    private Long churned;
}
