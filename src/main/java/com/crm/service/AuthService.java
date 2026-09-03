package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.AuditRecorder;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.LoginRequest;
import com.crm.dto.response.AuthResponse;
import com.crm.dto.response.UserResponse;
import com.crm.entity.RefreshToken;
import com.crm.entity.User;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.UnauthorizedException;
import com.crm.repository.RefreshTokenRepository;
import com.crm.repository.UserRepository;
import com.crm.security.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final Messages messages;
    private final AuditRecorder auditRecorder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    @Transactional
    @Audited(action = AuditAction.LOGIN, entity = "User",
        summary = "'Tizimga kirdi: ' + #result.username",
        entityId = "#result.userId",
        label = "#result.username")
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (RuntimeException e) {
            // Muvaffaqiyatsiz urinish ham qayd qilinadi. @Audited buni ushlay olmaydi:
            // metod exception bilan tugagani uchun aspect ataylab log yozmaydi.
            recordFailedLogin(request.getUsername(), e);
            throw e;
        }
        User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new BadRequestException(messages.get("error.auth.userNotFound")));

        // SecurityContext bu bosqichda hali bo'sh — JWT filtri keyingi so'rovlarda
        // to'ldiradi. Kim kirganini shu yerda o'zimiz bilamiz, aspectga beramiz.
        AuditContext.actor(user.getId(), user.getUsername(),
            user.getRole() != null ? user.getRole().name() : null);

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String accessToken = jwtUtils.generateToken(userDetails);

        String refreshTokenValue = UUID.randomUUID().toString();
        refreshTokenRepository.deleteByUserId(user.getId());

        RefreshToken refreshToken = RefreshToken.builder()
            .token(refreshTokenValue)
            .user(user)
            .expiresAt(LocalDateTime.now().plus(Duration.ofMillis(refreshExpiration)))
            .isRevoked(false)
            .build();
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshTokenValue)
            .tokenType("Bearer")
            .userId(user.getId())
            .username(user.getUsername())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .role(user.getRole())
            .expiresIn(86400L)
            .build();
    }

    /** Parol hech qachon logga tushmaydi — faqat login va sabab. */
    private void recordFailedLogin(String username, RuntimeException cause) {
        try {
            auditRecorder.record(com.crm.entity.AuditLog.builder()
                .createdAt(LocalDateTime.now())
                .username(username)
                .action(AuditAction.LOGIN_FAILED)
                .entityType("User")
                .entityLabel(username)
                .summary("Tizimga kirish muvaffaqiyatsiz: " + username
                    + " (" + cause.getClass().getSimpleName() + ")")
                .build());
        } catch (Exception ignored) {
            // Audit hech qachon login oqimini buzmasligi kerak
        }
    }

    @Transactional
    public AuthResponse refreshToken(String refreshTokenValue) {
        RefreshToken refreshToken = refreshTokenRepository
            .findByTokenAndIsRevokedFalse(refreshTokenValue)
            .orElseThrow(() -> new UnauthorizedException(messages.get("error.auth.invalidRefreshToken")));

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshToken.setIsRevoked(true);
            refreshTokenRepository.save(refreshToken);
            throw new UnauthorizedException(messages.get("error.auth.refreshTokenExpired"));
        }

        User user = refreshToken.getUser();
        UserDetails userDetails =
            userDetailsService.loadUserByUsername(user.getUsername());
        String newAccessToken = jwtUtils.generateToken(userDetails);

        String newRefreshTokenValue = UUID.randomUUID().toString();
        refreshToken.setToken(newRefreshTokenValue);
        refreshToken.setExpiresAt(LocalDateTime.now().plus(Duration.ofMillis(refreshExpiration)));
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
            .accessToken(newAccessToken)
            .refreshToken(newRefreshTokenValue)
            .tokenType("Bearer")
            .userId(user.getId())
            .username(user.getUsername())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .role(user.getRole())
            .expiresIn(86400L)
            .build();
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenAndIsRevokedFalse(refreshTokenValue)
            .ifPresent(rt -> {
                rt.setIsRevoked(true);
                refreshTokenRepository.save(rt);
            });
    }

    public UserResponse getCurrentUser(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BadRequestException(messages.get("error.auth.userNotFound")));

        return UserResponse.builder()
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
            .photoUrl(user.getPhotoUrl())
            .build();
    }
}
