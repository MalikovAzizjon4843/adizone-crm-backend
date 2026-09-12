package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskUserStatsDto {
    private Long userId;
    private String name;
    private long overdue;
    private long today;
    private long totalOpen;
}
