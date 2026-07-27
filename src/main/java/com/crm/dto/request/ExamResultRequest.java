package com.crm.dto.request;

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

    /** O'zgartirish sababi — PUT da majburiy */
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
