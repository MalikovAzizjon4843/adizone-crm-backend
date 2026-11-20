package com.crm.appeal;

import com.crm.entity.Appeal;
import com.crm.notification.NotificationTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Murojaat matnlari — {@code messages*.properties} ({@code appeal.bot.*}, {@code appeal.type.*}), uz asosiy, ru.
 * Har doim MessageFormat orqali o'tadi (kamida bitta argument) — apostrof IKKI marta ({@code ''}) yoziladi.
 * Foydalanuvchi matni argument sifatida keladi va chaqiruvchi uni HTML-escape qiladi.
 */
@Component
@RequiredArgsConstructor
public class AppealTexts {

    private final MessageSource messageSource;

    public static Locale locale(String language) {
        return NotificationTemplates.locale(language);
    }

    public String t(String key, Locale locale, Object... args) {
        Object[] a = args == null || args.length == 0 ? new Object[]{""} : args;
        return messageSource.getMessage(key, a, locale);
    }

    public String type(Appeal.Type type, Locale locale) {
        return t("appeal.type." + type.name(), locale);
    }

    public String status(Appeal.Status status, Locale locale) {
        return t("appeal.status." + status.name(), locale);
    }
}
