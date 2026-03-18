package com.crm.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ParentResponse {
    private Long id;
    private UUID uuid;
    private String firstName;
    private String lastName;
    private String phone;
    private String email;
    private String occupation;
    private String address;
    private String photoUrl;
    private String relation;
    private Boolean isActive;
    private List<LinkedStudentResponse> linkedStudents;
    private LocalDateTime createdAt;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LinkedStudentResponse {
        private Long studentId;
        private String firstName;
        private String lastName;
        private String phone;
        private String relation;
        private Boolean isPrimary;
    }
}
