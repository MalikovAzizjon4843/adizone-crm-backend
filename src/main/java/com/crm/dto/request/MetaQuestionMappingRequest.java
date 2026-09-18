package com.crm.dto.request;

import com.crm.entity.enums.MetaCrmField;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Bitta savolning CRM maydoniga bog'lanishi.
 *
 * <p>{@code crmField = null} - bog'lanishni olib tashlash. Keyingi
 * sinxronizatsiya uni avtomatik taxmin bilan qaytadan to'ldiradi.
 */
@Data
public class MetaQuestionMappingRequest {

    /** XOM kalit - {@code GET /forms/{formId}} qaytargan holda. */
    @NotBlank
    private String questionKey;

    private MetaCrmField crmField;
}
