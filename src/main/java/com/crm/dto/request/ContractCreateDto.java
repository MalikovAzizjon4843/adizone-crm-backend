package com.crm.dto.request;

import lombok.Data;

@Data
public class ContractCreateDto {
    private Long studentId;
    /** Optional — uses default template when null. */
    private Long templateId;
}
