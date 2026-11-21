package com.crm.calls;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * "Adizone Qo'ng'iroqlar" sozlamalari ({@code app.calls.*}, docs/design/calls.md §9). Hammasi env orqali o'zgaradi.
 */
@Component
@ConfigurationProperties(prefix = "app.calls")
@Getter
@Setter
public class CallsProperties {

    /** Notanish raqamdan kiruvchi / javobsiz qo'ng'iroq → avtomatik lid (manba {@code PHONE_CALL}). */
    private boolean autoLead = true;

    /** Yozuvlar katalogi (ochiq {@code GET /api/files/**} ga KIRMAYDI — u faqat ildizdagi fayllarni beradi). */
    private String recordingDir = "/opt/crm/uploads/calls";

    /** Bitta yozuv chegarasi (bayt). {@code spring.servlet.multipart.max-file-size} undan kichik bo'lmasin. */
    private long recordingMaxBytes = 50L * 1024 * 1024;

    /** Yozuv fayli qo'ng'iroqdan shuncha kun o'tib o'chiriladi (metama'lumot qoladi). 0 — hech qachon. */
    private int recordingRetentionDays = 180;

    /** Retention va muddati o'tgan so'rovlarni yopish (CallMaintenanceJob). */
    private String retentionCron = "0 10 4 * * *";

    /** {@code POST /api/calls/app/sync} — bir partiyada ko'pi bilan. */
    private int syncMaxBatch = 200;

    /** Bundan eski qo'ng'iroq sinxron qilinmaydi (birinchi o'rnatishda butun jurnal kelmasin). */
    private int syncMaxAgeDays = 30;

    /** CRM'dan qo'ng'iroq so'rovi shuncha soniyada eskiradi. */
    private int requestTtlSeconds = 120;

    /** FCM o'chiq bo'lsa ilova {@code GET requests/pending} ni shuncha soniyada so'raydi (tavsiya, heartbeat'da). */
    private int requestPollSeconds = 15;

    /** Kiruvchi qo'ng'iroq kartasi keshi (soniya). */
    private int lookupCacheSeconds = 30;

    /** Imzolangan yozuv havolasi ({@code /recording/link}) shuncha soniya amal qiladi. */
    private int recordingLinkTtlSeconds = 600;

    private App app = new App();

    private Fcm fcm = new Fcm();

    @Getter
    @Setter
    public static class App {
        /** Eng yangi APK versiyasi (masalan {@code 1.0.3}). */
        private String latestVersion = "1.0.0";
        /** Bundan past versiya — 426 {@code calls.app.upgradeRequired}. */
        private String minSupportedVersion = "1.0.0";
        /** APK yuklab olish havolasi. */
        private String apkUrl = "";
    }

    @Getter
    @Setter
    public static class Fcm {
        /** false — push yuborilmaydi, ilova pending'ni so'rab turadi. */
        private boolean enabled = false;
        /** Firebase loyiha id si. */
        private String projectId = "";
        /** Service account JSON fayli yo'li (sir — repoda emas). */
        private String credentialsFile = "";
    }
}
