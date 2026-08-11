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
}
