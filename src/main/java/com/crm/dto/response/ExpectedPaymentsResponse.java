package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectedPaymentsResponse {
    private long totalStudents;
    private BigDecimal totalAmount;

    @Builder.Default
    private List<DayBucket> days = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayBucket {
        private LocalDate date;
        private int studentCount;
        private BigDecimal dayTotal;

        @Builder.Default
        private List<ExpectedStudent> students = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedStudent {
        private Long studentId;
        private String fullName;
        private String phone;
        private String groupName;
        private BigDecimal amount;
        private String paymentStatus;
        private long daysUntil;
    }
}
