package com.crm.dto.request;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /api/admin/repair/merge-into-transfer}: aniq juftliklar ({@code pairs}) YOKI tasnif bo'yicha
 * ({@code classification = "AFFECTED"} — {@code from..to} oralig'idagi barcha AFFECTED juftliklar).
 */
@Data
public class ManualTransferRepairRequest {

    private List<Pair> pairs;

    /** Faqat "AFFECTED". */
    private String classification;

    /** {@code classification} bilan: aniqlash oralig'i (standart — oxirgi 60 kun). */
    private LocalDate from;
    private LocalDate to;

    @Data
    public static class Pair {
        private Long oldEnrollmentId;
        private Long newEnrollmentId;
    }
}
