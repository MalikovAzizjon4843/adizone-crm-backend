package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** {@code POST /api/meta/forms/sync} natijasi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaSyncResultDto {

    private int total;
    private int created;
    private int updated;
    private int questionsCreated;
    private int questionsUpdated;

    /** Savollari olinmagan formalar - sinxronizatsiya to'xtamaydi, ogohlantiradi. */
    private List<String> warnings;
}
