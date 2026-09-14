package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Import natijasi.
 *
 * <p>Har qator o'z tranzaksiyasida yoziladi, shuning uchun bitta yiqilgan
 * qator faqat o'zini yo'qotadi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadImportResult {

    private String importBatch;
    private int totalRows;
    private int created;
    /** Dublikat, moslashtirilmagan bosqich yoki bo'sh ism sababli o'tkazib yuborilgan. */
    private int skipped;
    private int failed;
    private int notesCreated;

    /** Qator SAQLANMAGAN — haqiqiy xato. */
    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    /**
     * Qator SAQLANGAN, lekin e'tibor talab qiladi — masalan telefon
     * tanilmadi va xom qiymat yozildi.
     *
     * <p>Alohida ro'yxat: {@code errors} ichida "saqlangan" yozuv turishi
     * chalkashtirardi. {@code ImportService} ning {@code ImportResult} i
     * ham shu shaklda — errors va warnings alohida.
     */
    @Builder.Default
    private List<RowError> warnings = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;
        private String reason;
    }
}
