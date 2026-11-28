package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Frontend uchun enum varianti: qiymat + ko'rsatiladigan nom + belgi. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnumOptionDto {
    private String value;
    private String label;
    private String icon;
    /** Chiqish sabablari: TRANSFERRED — "Guruhga ko'chirish" ishlatilsin (frontend maslahati). Boshqalarda yo'q. */
    @com.fasterxml.jackson.annotation.JsonInclude(value = com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean transferHint;
}
