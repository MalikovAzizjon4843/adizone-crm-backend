package com.crm.dto.request;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.StudentStatus;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDate;
@Data
public class StudentRequest {
    @NotBlank(message="First name required") private String firstName;
    @NotBlank(message="Last name required") private String lastName;
    @NotBlank(message="Phone required") private String phone;
    private String parentPhone;
    private LocalDate birthDate;
    private MarketingSource marketingSource = MarketingSource.OTHER;
    private Long referralStudentId;
    private StudentStatus status = StudentStatus.ACTIVE;
    private String notes;
    private String address;
}
