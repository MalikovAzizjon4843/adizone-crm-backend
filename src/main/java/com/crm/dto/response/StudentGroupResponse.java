package com.crm.dto.response;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StudentGroupResponse {
    private Long id;
    private Long studentId;
    private String studentName;
    private Long groupId;
    private String groupName;
    private String courseName;
    private String teacherName;
    private LocalDate joinDate;
    private LocalDate nextPaymentDate;
    private BigDecimal monthlyPrice;
    private Boolean isActive;
}
