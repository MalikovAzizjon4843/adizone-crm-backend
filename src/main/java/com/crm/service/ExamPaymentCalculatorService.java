package com.crm.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class ExamPaymentCalculatorService {

    /**
     * Proportional amount from last paid-through date to exam date (30-day month basis).
     */
    public BigDecimal calculateExamPayment(
            LocalDate lastPaymentEnd,
            LocalDate examDate,
            BigDecimal monthlyPrice) {

        if (monthlyPrice == null || monthlyPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (lastPaymentEnd == null || examDate == null) {
            return monthlyPrice.setScale(2, RoundingMode.HALF_UP);
        }

        long days = ChronoUnit.DAYS.between(lastPaymentEnd, examDate);
        if (days <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        return monthlyPrice
            .multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
    }
}
