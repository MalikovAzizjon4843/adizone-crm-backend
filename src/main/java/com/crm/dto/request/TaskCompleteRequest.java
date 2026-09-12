package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TaskCompleteRequest {

    /**
     * Bajarilish natijasi — MAJBURIY. amoCRM'da ham vazifani natija
     * yozmasdan yopish mumkin emas: aks holda lenta "vazifa bajarildi"
     * degan bo'sh yozuvlar bilan to'lib ketadi.
     */
    @NotBlank(message = "{task.result.required}")
    private String result;
}
