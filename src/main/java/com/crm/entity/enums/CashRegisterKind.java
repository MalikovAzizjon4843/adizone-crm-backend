package com.crm.entity.enums;

/**
 * Kassa turi (docs/design/cash-and-books.md §1.2.1, V86).
 * ADMIN — admin kassasi: a'zolari ({@code cash_register_members}) va SA ko'radi, har kech topshiriladi.
 * DIRECTOR — Direktor kassasi: faqat SUPER_ADMIN; boshqalar uchun "yo'q" (404).
 */
public enum CashRegisterKind {
    ADMIN,
    DIRECTOR
}
