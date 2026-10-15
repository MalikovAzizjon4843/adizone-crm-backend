package com.crm.entity.enums;

/** Kunlik topshirish holati (docs/design/cash-and-books.md §1.2.3, V86). */
public enum CashHandoverStatus {
    /** Admin topshirdi, Direktor qabulini kutmoqda. */
    SUBMITTED,
    /** Direktor qabul qildi: naqd Direktor kassasiga o'tdi, farq yozildi. */
    ACCEPTED,
    /** Direktor rad etdi: yozuvlar bo'shatildi, admin qayta topshiradi. */
    REJECTED
}
