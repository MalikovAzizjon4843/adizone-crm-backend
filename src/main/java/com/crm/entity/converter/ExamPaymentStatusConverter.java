package com.crm.entity.converter;

import com.crm.entity.enums.ExamPaymentStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code exam_registrations.payment_status} — eski {@code PENDING} ham o'qiladi (FREE). */
@Converter
public class ExamPaymentStatusConverter implements AttributeConverter<ExamPaymentStatus, String> {

    @Override
    public String convertToDatabaseColumn(ExamPaymentStatus attribute) {
        return attribute != null ? attribute.name() : ExamPaymentStatus.FREE.name();
    }

    @Override
    public ExamPaymentStatus convertToEntityAttribute(String dbData) {
        return ExamPaymentStatus.fromDb(dbData);
    }
}
