package com.crm.entity.converter;

import com.crm.entity.enums.StudyFormat;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Null saqlanadi: mavjud yozuvlarda format ko'rsatilmagan va majburiy emas.
 * Tanilmagan matn ham null bo'ladi — istisno tashlanmaydi.
 */
@Converter
public class StudyFormatConverter implements AttributeConverter<StudyFormat, String> {

    @Override
    public String convertToDatabaseColumn(StudyFormat attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public StudyFormat convertToEntityAttribute(String dbData) {
        return StudyFormat.parseOrNull(dbData);
    }
}
