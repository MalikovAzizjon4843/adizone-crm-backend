package com.crm.dto.response;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentResponse {
    private Long id;
    private UUID uuid;
    private String firstName;
    private String lastName;
    private String phone;
    private String parentPhone;
    private LocalDate birthDate;
    private String gender;
    private MarketingSource marketingSource;
    private StudentStatus status;
    private String notes;
    private String address;
    private String photoUrl;
    private String admissionNumber;
    private LocalDate admissionDate;
    private Long referralStudentId;
    private Long currentGroupId;
    private String currentGroupName;
    private PaymentStatus paymentStatus;
    private LocalDate paymentStartDate;
    private LocalDate nextPaymentDate;
    private BigDecimal monthlyFee;
    private LocalDateTime createdAt;
}
