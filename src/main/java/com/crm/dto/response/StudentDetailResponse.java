package com.crm.dto.response;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private List<GroupSummary> activeGroups;
    private List<PaymentSummary> paymentHistory;
    private Map<String, Integer> attendanceSummary;
    /** Linked parents via {@code student_parents}. */
    private List<StudentParentInfo> parents;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupSummary {
        private Long groupId;
        private String groupName;
        private String courseName;
        private String teacherName;
        private String paymentStatus;
        private LocalDate joinDate;
        private LocalDate leaveDate;
        private Boolean isActive;
        private BigDecimal monthlyPrice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentSummary {
        private Long id;
        private String receiptNumber;
        private BigDecimal amount;
        private String formattedAmount;
        private LocalDate paymentDate;
        private PaymentMethod paymentMethod;
        private String groupName;
        private PaymentStatus status;
    }

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
