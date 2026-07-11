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
public class DebtorsListResponse {
    private long totalDebtors;
    private long overdue7Plus;
    private BigDecimal totalDebt;

    @Builder.Default
    private List<DebtorStudent> students = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebtorStudent {
        private Long studentId;
        private String fullName;
        private String phone;
        private String groupName;
        private LocalDate nextPaymentDate;
        private long daysOverdue;
        private BigDecimal amount;
        private long monthsUnpaid;
        private BigDecimal totalDebt;
    }
}
