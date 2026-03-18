package com.crm.service;

import com.crm.dto.request.LoginRequest;
import com.crm.dto.request.RegisterRequest;
import com.crm.dto.response.AuthResponse;
import com.crm.dto.response.UserResponse;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.DuplicateResourceException;
import com.crm.repository.UserRepository;
import com.crm.security.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new BadRequestException("User not found"));

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String accessToken = jwtUtils.generateToken(userDetails);
        String refreshToken = jwtUtils.generateRefreshToken(userDetails);

        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .userId(user.getId())
            .username(user.getUsername())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .role(user.getRole())
            .build();
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already exists: " + request.getUsername());
        }
        if (request.getEmail() != null && userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already exists: " + request.getEmail());
        }

        User user = User.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .phone(request.getPhone())
            .role(request.getRole() != null ? request.getRole() : UserRole.ADMIN)
            .isActive(true)
            .build();

        user = userRepository.save(user);

        return UserResponse.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .phone(user.getPhone())
            .role(user.getRole())
            .isActive(user.getIsActive())
            .createdAt(user.getCreatedAt())
            .build();
    }

    public UserResponse getCurrentUser(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BadRequestException("User not found"));

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
            .build();
    }
}
