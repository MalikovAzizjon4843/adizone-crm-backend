package com.crm.dto.response;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private UUID uuid;
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private BigDecimal amount;
    private LocalDateTime paymentDate;
    private PaymentMethod paymentMethod;
    private PaymentStatus status;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String description;
    private LocalDateTime createdAt;
}
