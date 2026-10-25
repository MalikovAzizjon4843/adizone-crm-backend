package com.crm.dto.response;
import com.crm.entity.enums.AttendanceStatus;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AttendanceResponse {
    private Long id;
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private LocalDate attendanceDate;
    private AttendanceStatus status;
    private String notes;
    private Boolean excused;
    private String excuseReason;
    private LocalDateTime createdAt;
    /** O'quvchi / ota-onaning shu darsga sabab bildirishi (Mini App, telegram-platform §11.2); yo'q — null. */
    private AbsenceNoticeResponse absenceNotice;

    // ── Qarzdor belgisi (DebtorService.debtFlags — /api/payments/debtors bilan bir xil ta'rif, bugungi holat) ──
    /** Shu guruh yozilmasi bo'yicha qarzdor (OVERDUE). */
    private Boolean debtor;
    /** Shu yozilma qarzi qachondan (eng eski to'lanmagan majburiyat sanasi). */
    private LocalDate debtSince;
    /** Shu yozilma qarzi necha kundan beri. */
    private Long debtDays;
    /** Shu yozilma qarzi summasi — faqat SA / ADMIN / ACCOUNTANT; TEACHER uchun null. */
    private java.math.BigDecimal debtAmount;
    /** O'quvchi qarzdor (istalgan guruhi bo'yicha — boshqa guruhdagi qarzi ham). */
    private Boolean studentDebtor;
    private LocalDate studentDebtSince;
    private Long studentDebtDays;
    /** O'quvchining jami qarzi — faqat SA / ADMIN / ACCOUNTANT; TEACHER uchun null. */
    private java.math.BigDecimal studentDebtAmount;
    /** Boshqa guruhdagi yozilmasi OVERDUE. */
    private Boolean otherGroupDebt;
}
