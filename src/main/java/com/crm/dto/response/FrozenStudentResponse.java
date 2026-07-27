package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrozenStudentResponse {
    private Long studentId;
    private String fullName;
    private String phone;
    private LocalDate frozenDate;
    private BigDecimal balance;
    private Long lastGroupId;
    private String lastGroupName;
}
