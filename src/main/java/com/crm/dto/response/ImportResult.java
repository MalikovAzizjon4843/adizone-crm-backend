package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportResult {
    private int totalRows;
    private int imported;
    /** Dry-run (validate) uchun: xatosiz qatorlar soni. Haqiqiy importda = imported. */
    private int validRows;
    private int skipped;
    @Builder.Default
    private List<ImportIssue> errors = new ArrayList<>();
    @Builder.Default
    private List<ImportIssue> warnings = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportIssue {
        private int row;
        private String reason;
    }
}
