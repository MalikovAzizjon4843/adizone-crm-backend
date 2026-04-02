package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParentResponse {
    private Long id;
    private String fullName;
    private String phone;
    private String address;
    private String relation;
    private Long studentId;
    private String studentName;
    private Boolean isActive;
    private List<LinkedStudentResponse> linkedStudents;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedStudentResponse {
        private Long studentId;
        private String firstName;
        private String lastName;
        private String phone;
        private String relation;
        private Boolean isPrimary;
    }
}
