package com.crm.dto.response;

import com.crm.entity.enums.ContractType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplateDto {
    private Long id;
    private String uuid;
    private String title;
    private ContractType type;
    private String content;
    private boolean isDefault;
    private LocalDateTime createdAt;
}
