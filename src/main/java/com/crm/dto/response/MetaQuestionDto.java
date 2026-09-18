package com.crm.dto.response;

import com.crm.entity.enums.MetaCrmField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Meta formasining bitta savoli va uning CRM maydoniga bog'lanishi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaQuestionDto {

    private Long id;

    /**
     * XOM kalit. Frontend uni AYNAN shu holda qaytarishi kerak
     * ({@code PUT /forms/{formId}/mapping}) - apostrof, {@code ?} va
     * oxirgi {@code _} bilan birga.
     */
    private String questionKey;

    private String label;

    private String type;

    /** {@code {optionKey: label}} - bo'sh bo'lsa savol variantsiz. */
    private Map<String, String> options;

    /** null - mapping hali qo'yilmagan. */
    private MetaCrmField crmField;

    private Integer sortOrder;
}
