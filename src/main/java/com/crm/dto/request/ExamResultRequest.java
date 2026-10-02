package com.crm.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ExamResultRequest {
    /** POST uchun majburiy; PUT da ixtiyoriy */
    private Long studentId;

    /** Eski maydon nomi */
    private BigDecimal marksObtained;

    /** Yangi alias: score */
    private BigDecimal score;

    private String grade;

    /** Eski maydon nomi */
    private String remarks;

    /** Yangi alias: notes */
    private String notes;

    /**
     * O'zgartirish sababi — PUT da majburiy.
     *
     * <p>{@code changeReason} — eski frontend nomi (deprecated alias, phase5-audit
     * E-03): eski UI shu nom bilan yuborardi va har tahrir 400 qaytarardi. Yangi
     * mijozlar {@code editNote} yuborsin; alias eski UI o'chirilgach olib tashlanadi.
     */
    @JsonAlias("changeReason")
    private String editNote;

    /** Deprecated: server o'tish bali bo'yicha hisoblaydi */
    private Boolean isPassed;

    public BigDecimal resolveScore() {
        if (score != null) {
            return score;
        }
        return marksObtained;
    }

    public String resolveNotes() {
        if (notes != null) {
            return notes;
        }
        return remarks;
    }
}
