package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveSubmitRequest {
    /**
     * E'TIBORSIZ — yuboruvchi har doim joriy foydalanuvchi. Maydon eski
     * frontend yuborayotgani uchun qoldirilgan (xato bermasin).
     */
    private Long requesterId;

    /**
     * SUPER_ADMIN/ADMIN uchun majburiy — kimning ta'tili. TEACHER uchun
     * e'tiborsiz: u faqat o'zi uchun yuboradi.
     */
    private Long teacherId;

    @NotBlank(message = "{leaveSubmit.leaveType.required}")
    private String leaveType;

    @NotNull(message = "{leaveSubmit.fromDate.required}")
    private LocalDate fromDate;

    @NotNull(message = "{leaveSubmit.toDate.required}")
    private LocalDate toDate;

    private String reason;
}
