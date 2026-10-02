package com.crm.dto.request;

import lombok.Data;

@Data
public class ContractCreateDto {
    private Long studentId;
    /**
     * Narx olinadigan yozilma (leaves-exams-contracts §6.1). Berilmasa: o'quvchining yagona faol
     * yozilmasi; bir nechta bo'lsa — 400 {@code contract.studentGroupRequired}; umuman yo'q bo'lsa —
     * narxsiz shartnoma (snapshot maydonlari bo'sh).
     */
    private Long studentGroupId;
    /** Optional — uses default template when null. */
    private Long templateId;
}
