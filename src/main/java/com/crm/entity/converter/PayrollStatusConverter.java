package com.crm.entity.converter;

import com.crm.entity.enums.PayrollStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code payroll.status} — v1 dagi {@code PENDING} ham o'qiladi (V54 uni DRAFT ga yozadi). */
@Converter
public class PayrollStatusConverter implements AttributeConverter<PayrollStatus, String> {

    @Override
    public String convertToDatabaseColumn(PayrollStatus attribute) {
        return attribute != null ? attribute.name() : PayrollStatus.DRAFT.name();
    }

    @Override
    public PayrollStatus convertToEntityAttribute(String dbData) {
        return PayrollStatus.fromDb(dbData);
    }
}
