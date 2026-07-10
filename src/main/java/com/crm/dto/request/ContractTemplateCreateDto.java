package com.crm.dto.request;

import com.crm.entity.enums.ContractType;
import lombok.Data;

@Data
public class ContractTemplateCreateDto {
    private String title;
    private ContractType type;
    private String content;
    private Boolean isDefault;
}
