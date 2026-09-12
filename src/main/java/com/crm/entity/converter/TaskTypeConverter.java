package com.crm.entity.converter;

import com.crm.entity.enums.TaskType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class TaskTypeConverter implements AttributeConverter<TaskType, String> {

    @Override
    public String convertToDatabaseColumn(TaskType attribute) {
        return attribute != null ? attribute.name() : TaskType.OTHER.name();
    }

    @Override
    public TaskType convertToEntityAttribute(String dbData) {
        return TaskType.fromString(dbData);
    }
}
