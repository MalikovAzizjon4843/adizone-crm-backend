package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassroomBriefDto {
    private Long id;
    private String name;
    private Integer capacity;
}
