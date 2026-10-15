package com.crm.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpenseResponse {
    private Long id;
    private UUID uuid;
    private String category;
    private String categoryName;
    private String title;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private String teacherName;
    private String description;
    private LocalDateTime createdAt;
    private Long cashRegisterId;
    private String cashRegisterName;
}
