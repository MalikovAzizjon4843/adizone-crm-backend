package com.crm.dto.response;
import com.crm.entity.enums.UserRole;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long userId;
    private String username;
    private String firstName;
    private String lastName;
    private UserRole role;
}
