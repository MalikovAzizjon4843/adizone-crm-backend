package com.crm.dto.request;

import com.crm.entity.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * {@code POST /api/exams/{id}/registrations} (leaves-exams-contracts §4.2). Pullik imtihonda
 * {@code cashRegisterId} va {@code paymentMethod} majburiy — summa {@code exams.fee} dan olinadi.
 */
@Data
public class ExamRegistrationRequest {

    @NotNull(message = "{examRegistration.studentId.required}")
    private Long studentId;

    private Long cashRegisterId;

    private PaymentMethod paymentMethod;

    /** Faqat CASH_AND_CARD: naqd va karta qismlari (yig'indisi = fee). */
    private BigDecimal cashPart;
    private BigDecimal cardPart;

    private String note;
}
