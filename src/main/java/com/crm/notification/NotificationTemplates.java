package com.crm.notification;

import com.crm.config.MessageConfig;
import com.crm.telegram.TelegramUpdateHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Bildirishnoma shablonlari — {@code messages*.properties} (docs/design/notifications.md §3):
 * <ul>
 *   <li>{@code notify.<TUR>.title} / {@code .body} — qo'ng'iroqcha, doim o'zbekcha (xodim tili saqlanmaydi);</li>
 *   <li>{@code notify.<TUR>.telegram} — bot xabari (HTML), qabul qiluvchi tilida ({@code uz} asosiy, {@code ru}).</li>
 * </ul>
 * Argumentlar tayyor satr bo'lib keladi (summa, sana — chaqiruvchi formatlaydi): MessageFormat raqamni lokalga
 * qarab bo'lib yubormasin. Telegram matnida argumentlar HTML-escape qilinadi, shablonning o'zi — yo'q.
 * Shablonlar har doim MessageFormat orqali o'tadi — apostrof IKKI marta ({@code ''}) yoziladi.
 */
@Component
@RequiredArgsConstructor
public class NotificationTemplates {

    public static final Locale UZ = MessageConfig.UZ;
    public static final Locale RU = Locale.forLanguageTag("ru");

    private final MessageSource messageSource;

    /** Erkin til qiymati ({@code ru-RU}, {@code uz}, {@code en} …) → {@code ru} | {@code uz}; bo'sh — null. */
    public static String language(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT).startsWith("ru") ? "ru" : "uz";
    }

    public static Locale locale(String language) {
        return "ru".equals(language) ? RU : UZ;
    }

    public String title(NotificationType type, String variant, Object... args) {
        return messageSource.getMessage(type.key(variant, "title"), formatted(args), UZ);
    }

    /** Qo'ng'iroqcha matni; shablon bo'lmasa null. */
    public String body(NotificationType type, String variant, Object... args) {
        return blankToNull(messageSource.getMessage(type.key(variant, "body"), formatted(args), "", UZ));
    }

    /** Bot matni; shablon bo'lmasa null (shu tur botga yuborilmaydi). */
    public String telegram(NotificationType type, String variant, Locale locale, Object... args) {
        Object[] escaped = Arrays.stream(formatted(args))
            .map(a -> a == null ? "" : TelegramUpdateHandler.escape(String.valueOf(a)))
            .toArray();
        return blankToNull(messageSource.getMessage(type.key(variant, "telegram"), escaped, "", locale));
    }

    /** Umumiy matn (tugma yozuvi va h.k.). */
    public String text(String key, Locale locale) {
        return messageSource.getMessage(key, formatted(null), locale);
    }

    /**
     * Har doim kamida bitta argument: Spring argumentsiz xabarni MessageFormat'dan o'tkazmaydi, shunda
     * apostrof qoidasi kalitdan kalitga farq qilardi.
     */
    private static Object[] formatted(Object[] args) {
        return args == null || args.length == 0 ? new Object[]{""} : args;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
