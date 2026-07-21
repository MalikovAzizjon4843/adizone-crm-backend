package com.crm.entity.converter;

import com.crm.entity.enums.LeadStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class LeadStatusConverter implements AttributeConverter<LeadStatus, String> {

    @Override
    public String convertToDatabaseColumn(LeadStatus attribute) {
        return attribute != null ? attribute.name() : LeadStatus.NEW.name();
    }

    @Override
    public LeadStatus convertToEntityAttribute(String dbData) {
        return LeadStatus.fromString(dbData);
    }
}
