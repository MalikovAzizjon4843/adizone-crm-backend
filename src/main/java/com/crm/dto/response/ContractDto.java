package com.crm.dto.response;

import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.PaymentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDto {
    private Long id;
    private String uuid;
    private String contractNumber;
    private Long studentId;
    private String studentName;
    private Long templateId;
    private String templateTitle;
    private ContractType type;
    /** Tozalangan XHTML bo'lagi (belgilar qo'yilgan, HTML-escape bilan). */
    private String renderedContent;
    private ContractStatus status;
    private boolean offerAccepted;
    private LocalDateTime acceptedAt;
    private LocalDate contractDate;
    private LocalDateTime createdAt;

    // Narx snapshot'i (§6.1) — eski shartnomalarda null
    private Long studentGroupId;
    private String groupName;
    private String courseName;
    private PaymentType paymentType;
    private BigDecimal listPrice;
    private BigDecimal discountPercent;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private LocalDate startDate;

    // Holatlar (§6.2)
    private LocalDateTime signedAt;
    private String signedByName;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    /** Muzlatilgan PDF bor (SIGNED/ACCEPTED). */
    private boolean hasPdf;
    /** Sozlamalarda bo'sh majburiy rekvizitlar — PDF da {@code ________}. */
    private List<String> missingRequisites;
}
