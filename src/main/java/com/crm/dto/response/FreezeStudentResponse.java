package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeStudentResponse {
    private Long studentId;
    private BigDecimal totalBalance;

    @Builder.Default
    private List<FrozenGroupBreakdown> groups = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrozenGroupBreakdown {
        private Long groupId;
        private String groupName;
        /** O'qilgan (PRESENT/ABSENT/LATE) darslar */
        private Integer lessonsAttended;
        /** Alias: lessonsAttended */
        private int lessonsUsed;
        private BigDecimal lessonPrice;
        private BigDecimal used;
        private BigDecimal paid;
        private BigDecimal balance;
    }
}
