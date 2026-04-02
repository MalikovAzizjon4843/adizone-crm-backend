package com.crm.controller;

import com.crm.dto.request.ChangePasswordRequest;
import com.crm.dto.request.LoginRequest;
import com.crm.dto.request.RefreshTokenRequest;
import com.crm.dto.request.RegisterRequest;
import com.crm.dto.request.UpdateUserRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.AuthResponse;
import com.crm.dto.response.UserResponse;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.UserRepository;
import com.crm.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logged out", null));
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("User registered successfully", response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        UserResponse response = authService.getCurrentUser(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Profile updated",
            UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .build()));
    }

    @PutMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changeOwnPassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByUsername(userDetails.getUsername())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }
}
