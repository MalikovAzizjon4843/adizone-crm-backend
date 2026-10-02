package com.crm.entity.converter;

import com.crm.entity.enums.LeaveStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code leave_requests.status} — kichik harfli yoki noma'lum eski qiymat ham o'qiladi. */
@Converter
public class LeaveStatusConverter implements AttributeConverter<LeaveStatus, String> {

    @Override
    public String convertToDatabaseColumn(LeaveStatus attribute) {
        return attribute != null ? attribute.name() : LeaveStatus.PENDING.name();
    }

    @Override
    public LeaveStatus convertToEntityAttribute(String dbData) {
        return LeaveStatus.fromDb(dbData);
    }
}
