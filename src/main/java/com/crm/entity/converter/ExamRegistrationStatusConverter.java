package com.crm.entity.converter;

import com.crm.entity.enums.ExamRegistrationStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code exam_registrations.status} — V62 dan oldingi erkin matn ham o'qiladi. */
@Converter
public class ExamRegistrationStatusConverter implements AttributeConverter<ExamRegistrationStatus, String> {

    @Override
    public String convertToDatabaseColumn(ExamRegistrationStatus attribute) {
        return attribute != null ? attribute.name() : ExamRegistrationStatus.REGISTERED.name();
    }

    @Override
    public ExamRegistrationStatus convertToEntityAttribute(String dbData) {
        return ExamRegistrationStatus.fromDb(dbData);
    }
}
