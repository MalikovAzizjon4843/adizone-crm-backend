package com.crm.dto.response;

import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDto {
    private Long id;
    private String uuid;
    private String contractNumber;
    private Long studentId;
    private String studentName;
    private Long templateId;
    private String templateTitle;
    private ContractType type;
    private String renderedContent;
    private ContractStatus status;
    private boolean offerAccepted;
    private LocalDateTime acceptedAt;
    private LocalDate contractDate;
    private LocalDateTime createdAt;
}
