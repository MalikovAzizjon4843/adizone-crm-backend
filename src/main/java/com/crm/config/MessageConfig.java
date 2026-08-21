package com.crm.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Xabarlarni tarjima qilish infratuzilmasi.
 *
 * <p>Bunsiz Hibernate Validator o'zining ichki {@code ValidationMessages_*.properties}
 * faylini oladi — brauzer {@code Accept-Language: ru} yuborsa, xabarlar ruscha
 * chiqadi ("не должно равняться null"). Bu yerda MessageSource validatorga
 * ulanadi, natijada {@code @NotNull(message = "{user.firstName.required}")} ishlaydi.
 */
@Configuration
public class MessageConfig {

    public static final Locale UZ = Locale.forLanguageTag("uz");
    private static final List<Locale> SUPPORTED = List.of(
        UZ, Locale.forLanguageTag("ru"), Locale.ENGLISH);

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        source.setDefaultLocale(UZ);
        // MUHIM: aks holda kalit topilmaganda serverning tili olinadi.
        source.setFallbackToSystemLocale(false);
        // Kalit umuman yo'q bo'lsa, xatoga tushmasdan kalitning o'zini qaytaradi —
        // shunda foydalanuvchi 500 emas, hech bo'lmasa kalit nomini ko'radi.
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    /** {@code @NotNull(message = "{kalit}")} shu bean orqali tarjima qilinadi. */
    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean bean = new LocalValidatorFactoryBean();
        bean.setValidationMessageSource(messageSource);
        return bean;
    }

    /** Til Accept-Language sarlavhasidan olinadi; yuborilmasa — uz. */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED);
        resolver.setDefaultLocale(UZ);
        return resolver;
    }
}
