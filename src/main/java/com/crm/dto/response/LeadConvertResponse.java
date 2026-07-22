package com.crm.dto.response;

import com.crm.entity.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadConvertResponse {
    private Long id;
    private String admissionNumber;
    private PaymentStatus paymentStatus;
    private LocalDate nextPaymentDate;
    private Long leadId;
}
