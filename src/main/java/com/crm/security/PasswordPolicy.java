package com.crm.security;

/**
 * Parol siyosati (phase5-audit Q20, U-05): kamida 8 belgi, 2FA yo'q.
 *
 * <p>Yuqori chegara — BCrypt faqat birinchi 72 baytni hisobga oladi; undan
 * uzun parol "qabul qilinadi", lekin oxiri e'tiborsiz qolardi.
 * DTO annotatsiyalari ({@code @Size(min = MIN_LENGTH, max = MAX_LENGTH)}) va
 * {@code @Valid} siz yo'llar (masalan {@code create-for-teacher}) shu
 * qiymatlardan foydalanadi.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null
            && !password.isBlank()
            && password.length() >= MIN_LENGTH
            && password.length() <= MAX_LENGTH;
    }
}
