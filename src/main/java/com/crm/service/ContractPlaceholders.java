package com.crm.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shartnoma shablonidagi belgilar katalogi ({@code GET /api/contract-templates/placeholders}, C-08).
 * Shu ro'yxatda yo'q {@code {{x}}} — shablon saqlanayotganda 400 {@code contract.template.unknownPlaceholder}.
 */
public final class ContractPlaceholders {

    public record Placeholder(String key, String description, String example) {
    }

    /** Rekvizit yo'q bo'lsa qo'yiladigan chiziq (qo'lda to'ldirish uchun). */
    public static final String BLANK = "________";

    public static final Pattern PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    public static final List<Placeholder> ALL = List.of(
        new Placeholder("contractNumber", "Shartnoma raqami", "CTR-2026-00001"),
        new Placeholder("contractDate", "Shartnoma sanasi", "15.09.2026"),
        new Placeholder("currentDate", "Shartnoma sanasi (eski nom)", "15.09.2026"),
        new Placeholder("studentName", "O'quvchi F.I.Sh.", "Ali Valiyev"),
        new Placeholder("studentPhone", "O'quvchi telefoni", "+998 90 123 45 67"),
        new Placeholder("studentPassport", "Pasport (hozircha bo'sh — qo'lda)", BLANK),
        new Placeholder("parentName", "Ota-ona F.I.Sh.", "Vali Valiyev"),
        new Placeholder("parentPhone", "Ota-ona telefoni", "+998 90 765 43 21"),
        new Placeholder("groupName", "Guruh", "IELTS 2"),
        new Placeholder("courseName", "Kurs", "IELTS"),
        new Placeholder("paymentType", "To'lov turi: oylik / darsbay", "oylik"),
        new Placeholder("coursePrice", "Kurs narxi chegirmasiz (oylik yoki bir dars)", "700 000"),
        new Placeholder("discountPercent", "Chegirma, %", "10"),
        new Placeholder("discountAmount", "Chegirma summasi", "70 000"),
        new Placeholder("finalAmount", "Yakuniy summa (oylik yoki bir dars)", "630 000"),
        new Placeholder("monthlyFee", "Yakuniy summa (eski nom, = finalAmount)", "630 000"),
        new Placeholder("startDate", "To'lov boshlanish sanasi", "15.09.2026"),
        new Placeholder("centerName", "Markazning qisqa nomi (eski nom, = center.shortName)", "Adizone"),
        new Placeholder("center.legalName", "Yuridik nomi", "\"ADIZONE LC\" MChJ"),
        new Placeholder("center.legalNameRu", "Yuridik nomi (rus)", "ООО «ADIZONE LC»"),
        new Placeholder("center.shortName", "Qisqa nomi", "Adizone"),
        new Placeholder("center.inn", "STIR", "311626069"),
        new Placeholder("center.address", "Yuridik manzil", "Toshkent sh., Chilonzor t., Novza MFY, Ye mavzesi, 10-uy"),
        new Placeholder("center.phone", "Telefon", "+998 90 045 55 17"),
        new Placeholder("center.bankName", "Bank", "ОПЕРУ АКБ «Капитал Банк»"),
        new Placeholder("center.bankAccount", "Hisob raqami (h/r)", "20208000007147330001"),
        new Placeholder("center.bankMfo", "MFO", "00974"),
        new Placeholder("center.directorName", "Direktor F.I.Sh.", "Adizov Oqilbek Oybek o'g'li"),
        new Placeholder("center.contractCity", "Shartnoma tuzilgan joy", "Toshkent shahri"),
        new Placeholder("center.licenseInfo", "Litsenziya / xabarnoma", "Xabarnoma tasdiqnomasi №1180460 (reestr X-1743276)"));

    private static final Set<String> KEYS;

    static {
        Map<String, Placeholder> byKey = new LinkedHashMap<>();
        ALL.forEach(p -> byKey.put(p.key(), p));
        KEYS = byKey.keySet();
    }

    private ContractPlaceholders() {
    }

    /** Shablondagi katalogda yo'q belgilar (tartiblangan, takrorsiz). */
    public static Set<String> unknown(String content) {
        Set<String> out = new TreeSet<>();
        if (content == null) {
            return out;
        }
        Matcher m = PATTERN.matcher(content);
        while (m.find()) {
            if (!KEYS.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
