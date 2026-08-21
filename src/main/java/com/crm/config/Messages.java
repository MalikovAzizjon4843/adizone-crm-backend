package com.crm.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Servislarda tarjima qilingan xabar olish uchun qisqa yordamchi.
 *
 * <p>Til joriy so'rovning {@code Accept-Language} sarlavhasidan olinadi
 * ({@link org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver}).
 *
 * <p>Eslatma: argument berilganda MessageFormat ishga tushadi va apostrof
 * maxsus belgi bo'lib qoladi — properties faylida bunday xabarlarda
 * apostrof ikki marta yoziladi.
 */
@Component
@RequiredArgsConstructor
public class Messages {

    private final MessageSource messageSource;

    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
