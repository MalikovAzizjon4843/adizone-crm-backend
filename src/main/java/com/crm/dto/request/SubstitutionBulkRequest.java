package com.crm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Ko'plikda (ta'til oynasidan) — hammasi yoki hech biri (bitta tranzaksiya). */
@Data
public class SubstitutionBulkRequest {

    @NotEmpty(message = "{substitution.items.required}")
    @Size(max = 200, message = "{substitution.items.required}")
    private List<@Valid SubstitutionRequest> items;
}
