package com.crm.entity.converter;

import com.crm.entity.enums.MetaCrmField;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Null saqlanadi: "mapping hali qo'yilmagan" ma'noli holat va u
 * sinxronizatsiyaga "avtomatik taxmin qil" degan signal beradi.
 *
 * <p>Tanilmagan matn ham null bo'ladi — enum qiymati o'chirilganda
 * bazadagi eski qatorlar yozuvni yiqitmasin.
 */
@Converter
public class MetaCrmFieldConverter implements AttributeConverter<MetaCrmField, String> {

    @Override
    public String convertToDatabaseColumn(MetaCrmField attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public MetaCrmField convertToEntityAttribute(String dbData) {
        return MetaCrmField.parseOrNull(dbData);
    }
}
