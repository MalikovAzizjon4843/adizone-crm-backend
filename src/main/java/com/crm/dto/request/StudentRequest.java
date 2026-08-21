package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Student profile for a language / course center (not a K–12 school).
 * <p>
 * Enrollment is modeled via {@link com.crm.entity.StudentGroup}: UI "class" maps to {@code groupId},
 * "section" / course type maps to {@code courseId} on the group’s course (handled in group-enrollment APIs, not here).
 */
@Data
public class StudentRequest {

    @NotBlank(message = "{student.firstName.required}")
    private String firstName;

    @NotBlank(message = "{student.lastName.required}")
    private String lastName;

    @NotBlank(message = "{student.phone.required}")
    private String phone;

    @NotBlank(message = "{student.status.required}")
    private String status;

    @NotBlank(message = "{student.marketingSource.required}")
    private String marketingSource;

    private String admissionNumber;
    private LocalDate admissionDate;
    private Long groupId;
    private Long courseId;
    private String parentPhone;
    private String address;
    private String notes;

    private LocalDate birthDate;
    private String gender;
    private Long referralStudentId;
    private String photoUrl;

    /** Ota-onalar — StudentParent orqali bog'lanadi. */
    private List<StudentParentRequest> parents;
}
