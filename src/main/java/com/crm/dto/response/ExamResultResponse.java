package com.crm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExamResultResponse {
    private Long id;
    private Long examId;
    private String examName;
    private Long studentId;
    private String studentName;
    private BigDecimal marksObtained;
    /** Alias for marksObtained */
    @JsonProperty("score")
    public BigDecimal getScore() {
        return marksObtained;
    }
    private BigDecimal totalMarks;
    private BigDecimal passMarks;
    private String grade;
    private String remarks;
    @JsonProperty("notes")
    public String getNotes() {
        return remarks;
    }
    private Boolean isPassed;
    /** Alias for isPassed */
    @JsonProperty("passed")
    public Boolean getPassed() {
        return isPassed;
    }
    private String editNote;
    private LocalDateTime editedAt;
    private String editedBy;
    private LocalDateTime createdAt;
}
