package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherKpiRankingResponse {
    private String period;
    private LocalDate from;
    private LocalDate to;

    @Builder.Default
    private List<TeacherKpiRankingItemDto> teachers = new ArrayList<>();
}
