package com.crm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Meta (Facebook/Instagram) Lead Ads integratsiyasining sozlamalari.
 *
 * <p>Uchta sir bor va ular UCHALASI ham har xil narsa:
 * <ul>
 *   <li>{@link #systemUserToken} — Business Manager dagi System User tokeni.
 *       Undan sahifa tokeni olinadi.</li>
 *   <li>{@link #appSecret} — webhook imzosini ({@code X-Hub-Signature-256})
 *       tekshirish uchun. Graph so'rovlariga aloqasi yo'q.</li>
 *   <li>{@link #verifyToken} — webhook ni ro'yxatdan o'tkazishdagi bir
 *       martalik qo'l berish. Uni biz o'zimiz o'ylab topamiz.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "meta")
@Getter
@Setter
public class MetaProperties {

    /**
     * false — webhook 200 qaytaradi (Meta obunani o'chirmasin), lekin
     * scheduler ishlamaydi va Graph so'rovlari yuborilmaydi.
     */
    private boolean enabled = false;

    private String apiVersion = "v21.0";

    /** Lidlar keladigan Facebook sahifasi. */
    private String pageId;

    private String systemUserToken;

    private String appSecret;

    private String verifyToken;

    private String graphBaseUrl = "https://graph.facebook.com";

    /**
     * Webhook imzosi tekshirilsinmi.
     *
     * <p><b>Default true va u shunday qolishi kerak.</b> false — bu
     * {@code /api/meta/webhook} ni har kimga ochiq qoldirish degani:
     * endpoint autentifikatsiyasiz va imzosiz bo'lsa, uning manzilini
     * bilgan har qanday odam bizga soxta lid yuborib turadi.
     *
     * <p>Yagona maqsadi — {@code app-secret} hali olinmagan paytda
     * sinxronizatsiya va backfill ni sinab ko'rish. Shuning uchun
     * o'chirilgan holatda HAR SO'ROVDA log ga ogohlantirish yoziladi:
     * "vaqtincha" sozlama unutilib qolmasin.
     */
    private boolean verifySignature = true;

    /**
     * Avtomatik vazifa kimga biriktiriladi.
     *
     * <p>Kerak, chunki Meta lidi hech kimga biriktirilmagan holda tug'iladi
     * ("Biriktirilmagan" ustuni), {@code tasks.assigned_to} esa NOT NULL —
     * ya'ni mas'ulsiz vazifa yaratib bo'lmaydi. Bo'sh qoldirilsa
     * {@code autoCreateTask} shunchaki ishlamaydi va log ga ogohlantirish
     * yoziladi; lid baribir yaratiladi.
     */
    private Long taskAssigneeUserId;

    /** Sahifa tokenining keshda yashash muddati (soat). */
    private int pageTokenTtlHours = 6;

    /** Telefon bo'yicha takroriy murojaatni shuncha kun ichida qidiramiz. */
    private int duplicateWindowDays = 30;

    /** Graph so'rovining kutish vaqti (soniya). */
    private int requestTimeoutSeconds = 20;

    public String graphUrl(String path) {
        String base = graphBaseUrl != null ? graphBaseUrl : "https://graph.facebook.com";
        String version = apiVersion != null ? apiVersion : "v21.0";
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base.replaceAll("/+$", "") + "/" + version + suffix;
    }
}
