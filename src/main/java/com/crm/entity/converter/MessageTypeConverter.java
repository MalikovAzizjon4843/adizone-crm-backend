package com.crm.entity.converter;

import com.crm.entity.enums.MessageType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class MessageTypeConverter implements AttributeConverter<MessageType, String> {

    @Override
    public String convertToDatabaseColumn(MessageType attribute) {
        return attribute != null ? attribute.name() : MessageType.TEXT.name();
    }

    @Override
    public MessageType convertToEntityAttribute(String dbData) {
        return MessageType.fromString(dbData);
    }
}
