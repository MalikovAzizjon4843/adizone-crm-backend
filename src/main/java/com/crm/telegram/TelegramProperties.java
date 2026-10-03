package com.crm.telegram;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Telegram bot va o'quvchi/ota-ona Mini App sozlamalari (docs/design/telegram-platform.md §1, §3).
 *
 * <p>Uchta sir — uchalasi ham faqat env'dan (docs/ops/env.md):
 * <ul>
 *   <li>{@link #botToken} ({@code TELEGRAM_BOT_TOKEN}) — Bot API so'rovlari va initData HMAC kaliti;</li>
 *   <li>{@link #webhookSecret} ({@code TELEGRAM_WEBHOOK_SECRET}) — Telegram har webhook so'rovida
 *       {@code X-Telegram-Bot-Api-Secret-Token} sarlavhasida qaytaradigan qiymat;</li>
 *   <li>{@link App#jwtSecret} ({@code TELEGRAM_APP_JWT_SECRET}) — Mini App JWT kaliti, admin
 *       {@code JWT_SECRET} dan alohida.</li>
 * </ul>
 * Biror sir bo'sh bo'lsa tegishli qism yopiq ishlaydi (fail-closed): webhook 401, Mini App auth 503.
 */
@Component
@ConfigurationProperties(prefix = "telegram")
@Getter
@Setter
public class TelegramProperties {

    /** Mavjud {@code TelegramService} bilan umumiy kalit. */
    private String botToken;

    private boolean enabled = true;

    /** Kamida 32 belgi, {@code [A-Za-z0-9_-]} (Telegram {@code secret_token} talabi). */
    private String webhookSecret;

    /** setWebhook da beriladigan to'liq URL. */
    private String webhookUrl;

    /** Mini App manzili — /start dagi "Ilovani ochish" tugmasi. */
    private String webappUrl;

    private String apiBaseUrl = "https://api.telegram.org";

    private App app = new App();

    @Getter
    @Setter
    public static class App {

        private String jwtSecret;

        /** App JWT muddati; refresh token yo'q — tugasa Mini App qayta /api/app/auth qiladi. */
        private Duration tokenTtl = Duration.ofMinutes(30);

        /** initData {@code auth_date} eng ko'pi bilan shuncha eski bo'lishi mumkin. */
        private Duration initDataMaxAge = Duration.ofHours(1);
    }

    public boolean hasBotToken() {
        return botToken != null && !botToken.isBlank();
    }
}
