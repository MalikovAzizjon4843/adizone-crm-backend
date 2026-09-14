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
 * <p>{@code errors} — faqat yiqilgan qatorlar. Bitta qator butun importni
 * to'xtatmaydi: partiya saqlanmasa ham qolganlari davom etadi.
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

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private int row;
        private String reason;
    }
}
