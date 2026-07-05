package com.crm.service;

import com.crm.dto.request.CreateUserRequest;
import com.crm.dto.response.UserResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse createForTeacher(Long teacherId, CreateUserRequest request) {
        Teacher teacher = teacherRepository.findById(teacherId)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", teacherId));

        Optional<User> existing = userRepository.findByUsername(request.getUsername());
        if (existing.isPresent()) {
            linkTeacherToUser(teacher, existing.get());
            return toResponse(existing.get());
        }

        User user = User.builder()
            .firstName(request.getFirstName() != null ? request.getFirstName() : teacher.getFirstName())
            .lastName(request.getLastName() != null ? request.getLastName() : teacher.getLastName())
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.TEACHER)
            .phone(request.getPhone() != null ? request.getPhone() : teacher.getPhone())
            .email(request.getEmail() != null ? request.getEmail() : teacher.getEmail())
            .isActive(true)
            .build();

        User saved = userRepository.save(user);
        linkTeacherToUser(teacher, saved);
        return toResponse(saved);
    }

    private void linkTeacherToUser(Teacher teacher, User user) {
        if (teacher.getUser() == null || !teacher.getUser().getId().equals(user.getId())) {
            teacher.setUser(user);
            teacherRepository.save(teacher);
        }
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
