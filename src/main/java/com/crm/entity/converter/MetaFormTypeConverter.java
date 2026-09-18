package com.crm.entity.converter;

import com.crm.entity.enums.MetaFormType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Tanilmagan qiymat UNMAPPED bo'ladi — istisno tashlanmaydi. */
@Converter
public class MetaFormTypeConverter implements AttributeConverter<MetaFormType, String> {

    @Override
    public String convertToDatabaseColumn(MetaFormType attribute) {
        return attribute != null ? attribute.name() : MetaFormType.UNMAPPED.name();
    }

    @Override
    public MetaFormType convertToEntityAttribute(String dbData) {
        return MetaFormType.fromString(dbData);
    }
}
