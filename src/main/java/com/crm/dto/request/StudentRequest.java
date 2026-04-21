package com.crm.dto.request;

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

    @NotBlank(message = "Ism majburiy")
    private String firstName;

    @NotBlank(message = "Familiya majburiy")
    private String lastName;

    @NotBlank(message = "Telefon majburiy")
    private String phone;

    @NotBlank(message = "Holat majburiy")
    private String status;

    @NotBlank(message = "Qayerdan kelganligi majburiy")
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
}
