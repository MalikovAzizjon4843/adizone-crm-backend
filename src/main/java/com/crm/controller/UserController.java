package com.crm.controller;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.ChangePasswordRequest;
import com.crm.dto.request.CreateUserRequest;
import com.crm.dto.request.MyPasswordChangeRequest;
import com.crm.dto.request.MyProfileUpdateRequest;
import com.crm.dto.request.UpdateUserRequest;
import com.crm.dto.request.UserStatusRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.AuthResponse;
import com.crm.dto.response.MyProfileResponse;
import com.crm.dto.response.PasswordResetResponse;
import com.crm.dto.response.UserResponse;
import com.crm.dto.response.UsernamePreviewResponse;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.UserRepository;
import com.crm.service.FileStorageService;
import com.crm.service.ProfileService;
import com.crm.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final UserService userService;
    private final ProfileService profileService;

    // ── O'z hisobim — barcha xodim rollari (SecurityConfig: "/api/users/me/**" "/api/users/**" dan OLDIN) ──

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMe() {
        return ResponseEntity.ok(ApiResponse.success(profileService.getMe()));
    }

    /** Ism, familiya, telefon — qisman. Login va rol o'zgarmaydi. */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<MyProfileResponse>> updateMe(
            @Valid @RequestBody MyProfileUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Profil yangilandi", profileService.updateMe(request)));
    }

    /** Multipart {@code file}: jpg/jpeg/png/webp/gif, ≤ 2 MB. */
    @PostMapping("/me/avatar")
    public ResponseEntity<ApiResponse<MyProfileResponse>> uploadMyAvatar(
            @RequestParam(name = "file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success("Rasm saqlandi", profileService.updateAvatar(file)));
    }

    /**
     * Joriy parol shart (noto'g'ri — 400 {@code user.password.invalid}). Boshqa sessiyalar
     * yopiladi; javobda joriy sessiya uchun yangi token juftligi.
     */
    @PostMapping("/me/password")
    public ResponseEntity<ApiResponse<AuthResponse>> changeMyPassword(
            @Valid @RequestBody MyPasswordChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Parol o'zgartirildi", profileService.changePassword(request)));
    }

    /**
     * Yangi foydalanuvchi. Login band bo'lsa (registrsiz) — 409 {@code user.username.taken}
     * (phase5-audit U-06). Avvalgi "200 + message=ALREADY_EXISTS + mavjud user" javobi olib
     * tashlandi: frontend uni muvaffaqiyat deb, kiritilgan parolni ko'rsatardi.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User yaratildi", userService.createUser(request)));
    }

    /** Frontend forma to'ldirilayotganda loginni oldindan ko'rsatishi uchun. */
    @GetMapping("/username-preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UsernamePreviewResponse>> previewUsername(
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName) {
        return ResponseEntity.ok(ApiResponse.success(
            userService.previewUsername(firstName, lastName)));
    }

    /** Admin vaqtinchalik parol beradi; ochiq parol faqat shu javobda qaytadi. */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> resetPassword(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
            "Parol tiklandi", userService.resetPassword(id)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody UserStatusRequest request) {
        UserResponse updated = userService.setActive(id, request.getActive());
        return ResponseEntity.ok(ApiResponse.success(
            Boolean.TRUE.equals(request.getActive()) ? "Foydalanuvchi faollashtirildi"
                : "Foydalanuvchi nofaol qilindi",
            updated));
    }

    /**
     * O'qituvchi profiliga yangi login. Band login yoki profilda allaqachon login
     * bo'lsa — 409 (U-01); avvalgi "200 ALREADY_EXISTS + mavjud userga bog'lash"
     * xulqi olib tashlandi.
     */
    @PostMapping("/create-for-teacher/{teacherId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createForTeacher(
            @PathVariable Long teacherId,
            @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createForTeacher(teacherId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("User yaratildi", created));
    }

    /**
     * O'quvchi logini yaratilmaydi: STUDENT/PARENT uchun kabinet yo'q va ularning
     * kirishi bloklangan (phase5-audit Q1, U-02, U-08). Endpoint eski frontend
     * tushunarli javob olishi uchun qoldirilgan — doim 403.
     */
    @PostMapping("/create-for-student/{studentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createForStudent(@PathVariable Long studentId) {
        throw CodedException.forbidden("user.role.notAllowed", UserRole.STUDENT.name());
    }

    /**
     * Foydalanuvchilar (phase5-audit U-10).
     * <ul>
     *   <li>{@code page} berilmasa — eski shakl: {@code data} = to'liq {@code List} (selektorlar, eski frontend);</li>
     *   <li>{@code page} berilsa — {@code data} = {@code PageResponse}, {@code size} 1..200 (default 20).</li>
     * </ul>
     * Filtrlar ikkala shaklda: {@code q} (login/ism/familiya/telefon/email), {@code role} (takrorlanadi:
     * {@code ?role=TEACHER&role=ADMIN}), {@code active}.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Object>> getAllUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<UserRole> role,
            @RequestParam(required = false) Boolean active) {
        UserService.UserFilter filter = new UserService.UserFilter(q, role, active);
        Object data = page == null
            ? userService.listUsers(filter)
            : userService.pageUsers(filter, page, size);
        return ResponseEntity.ok(ApiResponse.success(data));
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
    @Audited(action = AuditAction.UPDATE, entity = "User",
        summary = "'Foydalanuvchi o''zgartirildi'",
        entityId = "#id")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        // Mantiq UserService da: rol o'zgarsa Teacher profili ham sinxronlanishi kerak.
        return ResponseEntity.ok(ApiResponse.success(
            "User updated", userService.updateUser(id, request)));
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @PathVariable Long id,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.setPassword(id, request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }

    @PostMapping("/{id}/photo")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> uploadUserPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        // try dan tashqarida: 403 "Rasm yuklashda xatolik" (400) ga aylanib ketmasin
        userService.assertManageable(id);
        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("User", id));
            String filename = UUID.randomUUID() + "_user_" + id + ".jpg";
            String photoUrl = fileStorageService.saveImage(file, filename);
            user.setPhotoUrl(photoUrl);
            user = userRepository.saveAndFlush(user);
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
    /**
     * Soft-delete: yozuv o'chirilmaydi, faqat nofaol qilinadi va sessiyalar yopiladi.
     * To'lovlar/davomat tarixi foydalanuvchiga bog'liq bo'lgani uchun jismoniy
     * o'chirish qo'llanmaydi.
     */
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.setActive(id, false);
        return ResponseEntity.ok(ApiResponse.success(
            "Foydalanuvchi nofaol qilindi (ma'lumotlari saqlanadi)", null));
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
