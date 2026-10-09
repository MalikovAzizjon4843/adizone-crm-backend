package com.crm.dto.response;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudyFormat;
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
    /** Qayerdan keldi (V79) — lid manbasi bilan bir xil qiymatlar; null — noma'lum. */
    private String source;
    private String sourceNote;
    private StudentStatus status;
    private String notes;
    private String address;
    private String photoUrl;
    private String admissionNumber;
    private LocalDate admissionDate;
    private Long referralStudentId;
    private Long currentGroupId;
    private String currentGroupName;
    /** Joriy guruhdagi o'qish formati. Ko'rsatilmagan bo'lsa null. */
    private StudyFormat studyFormat;
    private PaymentStatus paymentStatus;
    private LocalDate paymentStartDate;
    private LocalDate nextPaymentDate;
    /** Billing v2: Σ c(sg) — faol MONTHLY SG lar, chegirmadan keyin (§8). */
    private BigDecimal monthlyFee;
    /** Σ sg.balance (barcha SG). Bir SG ning ortiqchasi boshqasining qarzini yopmaydi. */
    private BigDecimal balance;
    /** Billing v2: Σ max(0, −sg.balance). */
    private BigDecimal debt;
    /** Billing v2: eng yaqin nextPaymentDate dagi summalar. */
    private BigDecimal nextPaymentAmount;
    private LocalDateTime createdAt;
}
