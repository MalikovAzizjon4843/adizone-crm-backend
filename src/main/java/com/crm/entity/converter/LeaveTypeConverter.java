package com.crm.entity.converter;

import com.crm.entity.enums.LeaveType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code leave_requests.leave_type} — V61 dan oldingi erkin matn ham o'qiladi (OTHER). */
@Converter
public class LeaveTypeConverter implements AttributeConverter<LeaveType, String> {

    @Override
    public String convertToDatabaseColumn(LeaveType attribute) {
        return attribute != null ? attribute.name() : LeaveType.OTHER.name();
    }

    @Override
    public LeaveType convertToEntityAttribute(String dbData) {
        return LeaveType.fromDb(dbData);
    }
}
