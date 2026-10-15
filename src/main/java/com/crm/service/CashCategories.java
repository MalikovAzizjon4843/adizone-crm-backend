package com.crm.service;

import com.crm.entity.CashCategory;
import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;

import java.util.List;

/**
 * Kassa kategoriyalari kodlari va boshlang'ich ro'yxat (docs/design/cash-and-books.md §1.2.2).
 * Ro'yxat V86 seed'i bilan AYNAN bir xil bo'lishi shart ({@code CashCategorySeeder} yetishmaganini qo'shadi).
 */
public final class CashCategories {

    // ── Kirim ──
    public static final String STUDENT_PAYMENT = "STUDENT_PAYMENT";
    public static final String EXAM = "EXAM";
    public static final String BOOK_SALE = "BOOK_SALE";
    public static final String OTHER_INCOME = "OTHER_INCOME";
    public static final String CASH_SURPLUS = "CASH_SURPLUS";

    // ── Chiqim ──
    public static final String SALARY = "SALARY";
    public static final String REFUND = "REFUND";
    public static final String BOOK_PURCHASE = "BOOK_PURCHASE";
    public static final String CASH_SHORTAGE = "CASH_SHORTAGE";
    public static final String OTHER_DAILY = "OTHER_DAILY";
    public static final String OTHER = "OTHER";

    private CashCategories() {
    }

    /** Boshlang'ich ro'yxat: kod, yo'nalish, guruh, nomi, tartib. */
    public static List<CashCategory> defaults() {
        return List.of(
            c(STUDENT_PAYMENT, CashDirection.IN, CashCategoryScope.SYSTEM, "O'quvchi to'lovi", 10),
            c(EXAM, CashDirection.IN, CashCategoryScope.SYSTEM, "Imtihon", 20),
            c(BOOK_SALE, CashDirection.IN, CashCategoryScope.SYSTEM, "Kitob sotuvi", 30),
            c(OTHER_INCOME, CashDirection.IN, CashCategoryScope.DAILY, "Boshqa kirim", 40),
            c(CASH_SURPLUS, CashDirection.IN, CashCategoryScope.SYSTEM, "Topshirishda ortiqcha", 90),
            c(SALARY, CashDirection.OUT, CashCategoryScope.SYSTEM, "Oylik", 10),
            c(REFUND, CashDirection.OUT, CashCategoryScope.SYSTEM, "O'quvchiga qaytarildi", 20),
            c(BOOK_PURCHASE, CashDirection.OUT, CashCategoryScope.SYSTEM, "Kitob xaridi", 30),
            c(CASH_SHORTAGE, CashDirection.OUT, CashCategoryScope.SYSTEM, "Topshirishda kamomad", 40),
            c("DELIVERY", CashDirection.OUT, CashCategoryScope.DAILY, "Dostavka", 100),
            c("STATIONERY", CashDirection.OUT, CashCategoryScope.DAILY, "Kanselyariya, qog'oz", 110),
            c("HOUSEHOLD", CashDirection.OUT, CashCategoryScope.DAILY, "Xo'jalik mollari", 120),
            c(OTHER_DAILY, CashDirection.OUT, CashCategoryScope.DAILY, "Boshqa mayda xarajat", 190),
            c("RENT", CashDirection.OUT, CashCategoryScope.STRATEGIC, "Ijara", 200),
            c("MARKETING", CashDirection.OUT, CashCategoryScope.STRATEGIC, "Reklama (target)", 210),
            c("UTILITIES", CashDirection.OUT, CashCategoryScope.STRATEGIC, "Kommunal", 220),
            c("EQUIPMENT", CashDirection.OUT, CashCategoryScope.STRATEGIC, "Jihozlar", 230),
            c(OTHER, CashDirection.OUT, CashCategoryScope.STRATEGIC, "Boshqa yirik xarajat", 290));
    }

    private static CashCategory c(String code, CashDirection dir, CashCategoryScope scope, String name, int order) {
        return CashCategory.builder()
            .code(code).direction(dir).scope(scope).name(name).active(true).sortOrder(order)
            .build();
    }
}
