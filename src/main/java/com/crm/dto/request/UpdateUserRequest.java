package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Qisman yangilash: faqat yuborilgan maydonlar o'zgaradi, shuning uchun
 * majburiylik tekshiruvi yo'q — lekin yuborilgan qiymat formatga mos bo'lishi shart.
 */
@Data
public class UpdateUserRequest {

    @Size(max = 100, message = "{user.firstName.size}")
    private String firstName;

    @Size(max = 100, message = "{user.lastName.size}")
    private String lastName;

    @Email(message = "{user.email.invalid}")
    @Size(max = 255, message = "{user.email.size}")
    private String email;

    @Pattern(
        regexp = "^$|^\\+998\\d{9}$|^\\d{9}$",
        message = "{user.phone.pattern}")
    private String phone;

    private UserRole role;

    private Boolean isActive;
}
