package com.crm.dto.request;

import lombok.Data;

import java.math.BigDecimal;

/**
 * {@code POST /api/payroll/{id}/approve} body (ixtiyoriy) — payroll-v2 §11 #4.
 * {@code expectedNetSalary} — foydalanuvchi ko'rgan summa; yakuniy summa farq qilsa 409
 * {@code payroll.netChanged} ({@code data.netSalary} — yangi summa).
 */
@Data
public class PayrollApproveRequest {
    private BigDecimal expectedNetSalary;
}
