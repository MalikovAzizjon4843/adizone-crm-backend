package com.crm.entity.converter;

import com.crm.entity.enums.StageKind;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class StageKindConverter implements AttributeConverter<StageKind, String> {

    @Override
    public String convertToDatabaseColumn(StageKind attribute) {
        return attribute != null ? attribute.name() : StageKind.OPEN.name();
    }

    @Override
    public StageKind convertToEntityAttribute(String dbData) {
        return StageKind.fromString(dbData);
    }
}
