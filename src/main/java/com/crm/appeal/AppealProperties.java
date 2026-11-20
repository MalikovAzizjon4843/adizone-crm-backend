package com.crm.appeal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Murojaatlar sozlamalari ({@code app.appeals.*}, docs/design/telegram-platform.md §12). Buyurtmachi hali
 * tasdiqlamagan standartlar — hammasi env orqali o'zgaradi.
 */
@Component
@ConfigurationProperties(prefix = "app.appeals")
@Getter
@Setter
public class AppealProperties {

    /** false — bot tugmasi va {@code /taklif} yo'q, Mini App'da yangi murojaat/xabar 403; CRM o'qishi ishlaydi. */
    private boolean enabled = true;

    /** Bir chat (Telegram foydalanuvchi) — kuniga shuncha yangi murojaat (Toshkent kuni). */
    private int dailyLimit = 5;

    /** Murojaat va xabar matni chegarasi (bazada CHECK ≤ 3000). */
    private int textMax = 3000;

    /** Bot suhbat holati shuncha daqiqa harakatsizlikdan keyin eskiradi. */
    private int stateTtlMinutes = 30;

    /** Raqam prefiksi: {@code MRJ-00012}. */
    private String numberPrefix = "MRJ";

    /** Murojaat RESOLVED / REJECTED bo'lganda yuboruvchiga bot xabari. */
    private boolean notifySenderOnClose = true;
}
