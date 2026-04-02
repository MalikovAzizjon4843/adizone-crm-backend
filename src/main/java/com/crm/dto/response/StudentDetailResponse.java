package com.crm.dto.response;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentDetailResponse {
    private Long id;
    private UUID uuid;
    private String firstName;
    private String lastName;
    private String phone;
    private String parentPhone;
    private LocalDate birthDate;
    private String gender;
    private MarketingSource marketingSource;
    private StudentStatus status;
    private String notes;
    private String address;
    private String photoUrl;
    private String admissionNumber;
    private LocalDate admissionDate;
    private Long referralStudentId;
    private List<StudentGroupResponse> activeGroups;
    private List<PaymentResponse> recentPayments;
    /** Linked parents via {@code student_parents}. */
    private List<StudentParentInfo> parents;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentParentInfo {
        private Long parentId;
        private String fullName;
        private String phone;
        private String address;
        private String relation;
        private Boolean isPrimary;
    }
}
