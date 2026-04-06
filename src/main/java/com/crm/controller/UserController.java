package com.crm.controller;

import com.crm.dto.request.ChangePasswordRequest;
import com.crm.dto.request.CreateUserRequest;
import com.crm.dto.request.UpdateUserRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.UserResponse;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.UserRepository;
import com.crm.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        Optional<User> existing = userRepository.findByUsername(request.getUsername());
        if (existing.isPresent()) {
            UserResponse response = toResponse(existing.get());
            return ResponseEntity.ok(
                ApiResponse.<UserResponse>builder()
                    .success(true)
                    .message("ALREADY_EXISTS")
                    .data(response)
                    .build()
            );
        }
        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(request.getRole() != null ? request.getRole() : UserRole.TEACHER)
            .phone(request.getPhone())
            .email(request.getEmail())
            .isActive(request.getIsActive())
            .build();
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User yaratildi",
                toResponse(userRepository.save(user))));
    }

    @PostMapping("/create-for-teacher/{teacherId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createForTeacher(
            @PathVariable Long teacherId,
            @RequestBody CreateUserRequest request) {

        Optional<User> existing = userRepository.findByUsername(request.getUsername());
        if (existing.isPresent()) {
            UserResponse response = toResponse(existing.get());
            return ResponseEntity.ok(
                ApiResponse.<UserResponse>builder()
                    .success(true)
                    .message("ALREADY_EXISTS")
                    .data(response)
                    .build()
            );
        }

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.TEACHER)
            .phone(request.getPhone())
            .email(request.getEmail())
            .isActive(true)
            .build();

        User saved = userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User yaratildi", toResponse(saved)));
    }

    @PostMapping("/create-for-student/{studentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createForStudent(
            @PathVariable Long studentId,
            @RequestBody CreateUserRequest request) {

        Optional<User> existing = userRepository.findByUsername(request.getUsername());
        if (existing.isPresent()) {
            UserResponse response = toResponse(existing.get());
            return ResponseEntity.ok(
                ApiResponse.<UserResponse>builder()
                    .success(true)
                    .message("ALREADY_EXISTS")
                    .data(response)
                    .build()
            );
        }

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.STUDENT)
            .phone(request.getPhone())
            .email(request.getEmail())
            .isActive(true)
            .build();

        User saved = userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User yaratildi", toResponse(saved)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {
        List<UserResponse> users = userRepository.findAll()
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        return ResponseEntity.ok(ApiResponse.success(toResponse(user)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getRole() != null) user.setRole(request.getRole());
        if (request.getIsActive() != null) user.setIsActive(request.getIsActive());
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("User updated", toResponse(user)));
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @PathVariable Long id,
            @Valid @RequestBody ChangePasswordRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }

    @PostMapping("/{id}/photo")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> uploadUserPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("User", id));
            String filename = UUID.randomUUID() + "_user_" + id + ".jpg";
            String photoUrl = fileStorageService.saveImage(file, filename);
            user.setPhotoUrl(photoUrl);
            userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success("Rasm saqlandi", toResponse(user)));
        } catch (Exception e) {
            if (e instanceof BadRequestException) {
                throw (BadRequestException) e;
            }
            throw new BadRequestException("Rasm yuklashda xatolik");
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setIsActive(false);
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("User deactivated", null));
    }

    private UserResponse toResponse(User u) {
        return UserResponse.builder()
            .id(u.getId())
            .username(u.getUsername())
            .email(u.getEmail())
            .firstName(u.getFirstName())
            .lastName(u.getLastName())
            .phone(u.getPhone())
            .role(u.getRole())
            .isActive(u.getIsActive())
            .lastLogin(u.getLastLogin())
            .createdAt(u.getCreatedAt())
            .photoUrl(u.getPhotoUrl())
            .build();
    }
}
