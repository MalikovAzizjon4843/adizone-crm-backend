package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * Ta'til arizasi (leaves-exams-contracts §1.3). Xodim — o'zi uchun; SUPER_ADMIN/ADMIN — istalgan
 * xodim uchun ({@code userId}, eski frontend uchun {@code teacherId} ham tushuniladi).
 */
@Data
public class LeaveSubmitRequest {
    /** E'TIBORSIZ — yuboruvchi har doim joriy foydalanuvchi (eski frontend yuborardi). */
    private Long requesterId;

    /** SA/A: kimning ta'tili. Boshqa rollar uchun e'tiborsiz — faqat o'zi. */
    private Long userId;

    /** @deprecated {@code userId}. SA/A: o'qituvchi profili bo'yicha (unga bog'langan user). */
    @Deprecated
    private Long teacherId;

    /** ANNUAL, SICK, FAMILY, STUDY, OTHER. */
    @NotBlank(message = "{leaveSubmit.leaveType.required}")
    private String leaveType;

    @NotNull(message = "{leaveSubmit.fromDate.required}")
    private LocalDate fromDate;

    @NotNull(message = "{leaveSubmit.toDate.required}")
    private LocalDate toDate;

    private String reason;
}
