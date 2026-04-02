package com.crm.dto.request;

import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/**
 * Student profile for a language / course center (not a K–12 school).
 * <p>
 * Enrollment is modeled via {@link com.crm.entity.StudentGroup}: UI "class" maps to {@code groupId},
 * "section" / course type maps to {@code courseId} on the group’s course (handled in group-enrollment APIs, not here).
 */
@Data
public class StudentRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone is required")
    private String phone;

    private String parentPhone;
    private LocalDate birthDate;
    private String gender;
    private MarketingSource marketingSource = MarketingSource.OTHER;
    private Long referralStudentId;
    private StudentStatus status = StudentStatus.ACTIVE;
    private String notes;
    private String address;
    private String photoUrl;

    private String admissionNumber;
    private LocalDate admissionDate;
}
