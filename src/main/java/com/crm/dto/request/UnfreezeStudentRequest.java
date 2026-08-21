package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UnfreezeStudentRequest {
    @NotNull(message = "{unfreezeStudent.groupId.required}")
    private Long groupId;

    /**
     * Ixtiyoriy — yuborilmasa bugungi sana olinadi
     * ({@code StudentService.unfreezeStudent}). Muzlatishdan chiqarilgan o'quvchi
     * uchun to'lov davri odatda bugundan boshlanadi, shuning uchun majburiy emas.
     */
    private LocalDate paymentStartDate;
}
