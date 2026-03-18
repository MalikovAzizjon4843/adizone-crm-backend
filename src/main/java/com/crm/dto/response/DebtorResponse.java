package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DebtorResponse {
    private Long studentId;
    private String studentName;
    private String phone;
    private String parentPhone;
    private Long groupId;
    private String groupName;
    private LocalDate nextPaymentDate;
    private long daysOverdue;
    private BigDecimal monthlyAmount;
}
