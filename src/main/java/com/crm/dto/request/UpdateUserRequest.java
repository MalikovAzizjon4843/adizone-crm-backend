package com.crm.dto.request;

import com.crm.entity.enums.UserRole;
import lombok.Data;

@Data
public class UpdateUserRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserRole role;
    private Boolean isActive;
}
