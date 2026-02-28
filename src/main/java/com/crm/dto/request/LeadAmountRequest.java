package com.crm.dto.request;

import lombok.Data;

import java.math.BigDecimal;

/**
 * To'lov summasini tuzatish uchun.
 *
 * <p>{@code amount} ATAYLAB {@code @NotNull} EMAS: null yuborish summani
 * butunlay olib tashlash degani. Lekin lid to'lov bosqichida turgan bo'lsa
 * ({@code requiresAmount}) bo'shatishga ruxsat berilmaydi — servis buni
 * tekshiradi.
 */
@Data
public class LeadAmountRequest {
    private BigDecimal amount;
}
