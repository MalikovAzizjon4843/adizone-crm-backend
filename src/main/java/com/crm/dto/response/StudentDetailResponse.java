package com.crm.dto.response;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StudentDetailResponse {
    private Long id;
    private UUID uuid;
    private String firstName;
    private String lastName;
    private String phone;
    private String parentPhone;
    private LocalDate birthDate;
    private MarketingSource marketingSource;
    private StudentStatus status;
    private String notes;
    private String address;
    private List<StudentGroupResponse> activeGroups;
    private List<PaymentResponse> recentPayments;
    private LocalDateTime createdAt;
}
