package com.crm.entity.enums;

/**
 * Kassa kategoriyasi guruhi (docs/design/cash-and-books.md §1.2.2, V86).
 * SYSTEM — faqat o'z oqimi yozadi (to'lov, imtihon, oylik, qaytarish, topshirish farqi), qo'lda tanlanmaydi;
 * DAILY — kundalik mayda xarajat/kirim: admin o'z kassasidan;
 * STRATEGIC — yirik xarajat: faqat SUPER_ADMIN, Direktor kassasidan (yoki kassasiz).
 */
public enum CashCategoryScope {
    SYSTEM,
    DAILY,
    STRATEGIC
}
