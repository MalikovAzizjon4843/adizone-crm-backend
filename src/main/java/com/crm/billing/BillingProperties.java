package com.crm.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.billing.*} — docs/design/billing-v2.md §10.1.
 */
@Component
@ConfigurationProperties(prefix = "app.billing")
@Getter
@Setter
public class BillingProperties {

    /**
     * false — billing yozish amallari (to'lov, accrual, muzlatish, bekor qilish)
     * 503 {@code billing.maintenance} beradi, o'qish ishlaydi. Cutover oynasi
     * va {@code BillingSchemaGuard} sxema to'liq emasligini topganda.
     */
    private boolean enabled = true;

    /** Qarz shu kundan oshganda PENDING → OVERDUE. */
    private int graceDays = 3;

    /** Kunlik accrual (Asia/Tashkent). */
    private String accrualCron = "0 10 0 * * *";

    /** Bitta {@code accrueUpTo} chaqiruvida ko'pi bilan shuncha davr ("quvib yetish"). */
    private int maxCatchUp = 24;

    /** Telegram qarz eslatmasi (Asia/Tashkent). */
    private String reminderCron = "0 0 10 * * *";

    /** Ilova ishga tushganda o'tkazib yuborilgan kunlar uchun accrual. Testlarda o'chiriladi. */
    private boolean startupCatchUp = true;

    /** To'lovni bekor qilish shuncha kundan eski bo'lmasin (§13 #23). */
    private int cancelMaxAgeDays = 31;

    /** G — tizim ishga tushgan sana (§9.1); migratsiyada R ni aniqlash uchun. */
    private java.time.LocalDate migrationGoLive = java.time.LocalDate.of(2026, 9, 18);

    /** Migratsiya qo'llangandan keyin to'liq rollback oynasi, soat (§9.7, §13 #20). */
    private int migrationRollbackHours = 72;
}
