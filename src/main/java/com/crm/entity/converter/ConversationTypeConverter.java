package com.crm.entity.converter;

import com.crm.entity.enums.ConversationType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ConversationTypeConverter implements AttributeConverter<ConversationType, String> {

    @Override
    public String convertToDatabaseColumn(ConversationType attribute) {
        return attribute != null ? attribute.name() : ConversationType.DIRECT.name();
    }

    @Override
    public ConversationType convertToEntityAttribute(String dbData) {
        return ConversationType.fromString(dbData);
    }
}
