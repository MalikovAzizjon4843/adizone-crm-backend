package com.crm.util;

/**
 * Telefon raqamini {@code +998XXXXXXXXX} kanonik shakliga keltiradi.
 *
 * <p>Mantiq avval {@code LeadImportService.canonicalPhone} da edi va faqat
 * Excel importiga xizmat qilardi. Endi u shu yerda: bir xil qoida ham
 * importga, ham HTTP so'rovlaridagi har bir telefon maydoniga tegishli —
 * aks holda baza ikki xil formatni saqlab qolardi.
 *
 * <p>Raqamdan boshqa HAMMA belgi tashlanadi: probel (oddiy va uzilmas
 * {@code U+00A0}), apostrof, chiziqcha, qavs, nuqta va {@code +}. Shu
 * sababli "Turk tili" kabi matndan raqam qolmaydi.
 */
public final class PhoneUtils {

    private PhoneUtils() {
    }

    /**
     * Kanonik shakl yoki {@code null} — raqamni tanib bo'lmasa.
     *
     * <p>Import shu {@code null} ga tayanadi: u "bu telefon emas" degani va
     * chaqiruvchi xom qiymatni saqlab qo'yadi. HTTP oqimi uchun
     * {@link #canonicalOrRaw} kerak.
     *
     * <p>Amalda uchragan shakllar: {@code '+998 507723109} (Excel apostrofi
     * va probel bilan), {@code 901204729} (kodsiz), {@code 70 483 15 03},
     * {@code +998(90)123-45-67}, {@code 8 90 123 45 67}.
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() == 12 && d.startsWith("998")) {
            return "+" + d;
        }
        if (d.length() == 9) {
            return "+998" + d;
        }
        // 8 bilan boshlanadigan ichki format: 8 tashlanadi va 9 raqam qoladi.
        // Faqat 10 xonali shakl — 11 xonalisidan "+998" + 10 raqam chiqardi,
        // ya'ni kanonik shakldan bitta raqam ortiq va hech qanday validatsiya
        // uni o'tkazmasdi.
        if (d.length() == 10 && d.startsWith("8")) {
            return "+998" + d.substring(1);
        }
        return null;
    }

    /**
     * Kanonik shakl, tanib bo'lmasa — XOM qiymat.
     *
     * <p>Xom qiymat ataylab qaytariladi: validatsiya undan keyin ishlaydi va
     * foydalanuvchi o'zi yozgan narsani xato xabarida ko'radi. Agar bu yerda
     * {@code null} qaytsa, {@code @NotBlank} "telefon kiritilmagan" deb
     * aytardi — foydalanuvchi esa uni kiritgan bo'lardi.
     */
    public static String canonicalOrRaw(String raw) {
        String canonical = canonical(raw);
        return canonical != null ? canonical : raw;
    }

    /**
     * Dublikat kaliti — kanonik shaklning raqamlari ({@code 998XXXXXXXXX}).
     * Tanib bo'lmasa null: bunday qiymatlar dublikat sifatida
     * solishtirilmaydi, chunki ular telefon emas.
     *
     * <p>Kalit AYNAN kanonik shakldan olinadi, xom qiymatdan emas — shunda
     * {@code 901204729} va {@code '+998 901204729} bitta lid deb taniladi.
     */
    public static String canonicalDigits(String raw) {
        String canonical = canonical(raw);
        return canonical != null ? canonical.replaceAll("[^0-9]", "") : null;
    }
}
