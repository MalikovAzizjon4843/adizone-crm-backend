package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.MyPasswordChangeRequest;
import com.crm.dto.request.MyProfileUpdateRequest;
import com.crm.dto.response.AuthResponse;
import com.crm.dto.response.MyProfileResponse;
import com.crm.entity.User;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.UnauthorizedException;
import com.crm.repository.RefreshTokenRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * "O'z hisobim" — {@code /api/users/me/**}, barcha xodim rollari uchun (SecurityConfig).
 *
 * <p>Foydalanuvchi faqat SecurityContext dan olinadi — id so'rovda kelmaydi, ya'ni boshqaning
 * hisobiga yo'l yo'q. Login va rol bu yerda o'zgartirilmaydi ({@code /api/users/{id}} — SA/A).
 *
 * <p>Eski {@code /api/auth/me}, {@code /api/auth/profile}, {@code /api/auth/change-password}
 * tegilmagan — frontend o'tib bo'lgach olib tashlanishi mumkin.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** Avatar chegarasi — umumiy rasm chegarasidan ({@code FileStorageService}, 4 MB) qattiqroq. */
    static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final AuthService authService;
    private final Messages messages;

    @Transactional(readOnly = true)
    public MyProfileResponse getMe() {
        return toResponse(currentUser());
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "User",
        summary = "'Profil yangilandi: ' + #result.username",
        entityId = "#result.id",
        label = "#result.username")
    public MyProfileResponse updateMe(MyProfileUpdateRequest request) {
        User user = currentUser();
        if (request.getFirstName() != null) {
            String firstName = request.getFirstName().trim();
            if (firstName.isEmpty()) {
                throw new BadRequestException(messages.get("user.firstName.required"));
            }
            AuditContext.change("firstName", user.getFirstName(), firstName);
            user.setFirstName(firstName);
        }
        if (request.getLastName() != null) {
            String lastName = request.getLastName().trim();
            if (lastName.isEmpty()) {
                throw new BadRequestException(messages.get("user.lastName.required"));
            }
            AuditContext.change("lastName", user.getLastName(), lastName);
            user.setLastName(lastName);
        }
        if (request.getPhone() != null) {
            String phone = request.getPhone().isBlank() ? null : request.getPhone().trim();
            AuditContext.change("phone", user.getPhone(), phone);
            user.setPhone(phone);
        }
        return toResponse(userRepository.save(user));
    }

    /**
     * Avatar — mavjud yuklash mexanizmi ({@code FileStorageService.saveImage}: tur, sarlavha va
     * o'lcham tekshiruvi), qo'shimcha ≤ 2 MB. Eski fayl o'chirilmaydi (admin yuklashi bilan bir xil).
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "User",
        summary = "'Profil rasmi yangilandi: ' + #result.username",
        entityId = "#result.id",
        label = "#result.username")
    public MyProfileResponse updateAvatar(MultipartFile file) {
        User user = currentUser();
        if (file != null && file.getSize() > MAX_AVATAR_BYTES) {
            throw CodedException.badRequest("user.avatar.tooLarge", MAX_AVATAR_BYTES / (1024 * 1024));
        }
        String avatarUrl;
        try {
            avatarUrl = fileStorageService.saveImage(file, UUID.randomUUID() + "_avatar_" + user.getId() + ".jpg");
        } catch (IOException e) {
            throw new BadRequestException("Rasm yuklashda xatolik");
        }
        user.setPhotoUrl(avatarUrl);
        return toResponse(userRepository.save(user));
    }

    /**
     * O'z parolini almashtirish. Joriy parol noto'g'ri — 400 {@code user.password.invalid}.
     *
     * <p>Muvaffaqiyatda: token versiyasi oshadi (barcha eski access tokenlar 401), barcha refresh
     * tokenlar bekor qilinadi — boshqa qurilmalardagi sessiyalar yopiladi. Joriy sessiya uzilmasin
     * deb javobda YANGI token juftligi qaytadi.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "User",
        summary = "'Parol o''zgartirildi: ' + #result.username",
        entityId = "#result.userId",
        label = "#result.username")
    public AuthResponse changePassword(MyPasswordChangeRequest request) {
        User user = currentUser();
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw CodedException.badRequest("user.password.invalid");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.bumpTokenVersion();
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        AuditContext.change("password", null, "***");
        return authService.issueTokens(user);
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new UnauthorizedException(messages.get("error.auth.userNotFound"));
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new UnauthorizedException(messages.get("error.auth.userNotFound")));
    }

    private MyProfileResponse toResponse(User user) {
        String fullName = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
            + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return MyProfileResponse.builder()
            .id(user.getId())
            .fullName(fullName)
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .username(user.getUsername())
            .role(user.getRole())
            .phone(user.getPhone())
            .avatarUrl(user.getPhotoUrl())
            .createdAt(user.getCreatedAt())
            .lastLoginAt(user.getLastLogin())
            .teacherId(teacherRepository.findByUser_Id(user.getId()).map(t -> t.getId()).orElse(null))
            .build();
    }
}
