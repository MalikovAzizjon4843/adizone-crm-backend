package com.crm.service;

import com.crm.entity.Group;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ForbiddenException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * TEACHER roli uchun joriy o'qituvchini aniqlash va egalik tekshiruvlari.
 * Barcha teacher-scoped API lar shu helper orqali ishlashi kerak.
 */
@Service
@RequiredArgsConstructor
public class TeacherAccessService {

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final StudentRepository studentRepository;

    public User getCurrentUserOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ForbiddenException("Avtorizatsiya talab qilinadi");
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new ForbiddenException("Foydalanuvchi topilmadi"));
    }

    public boolean isCurrentUserTeacher() {
        User user = currentUserOrNull();
        return user != null && user.getRole() == UserRole.TEACHER;
    }

    public boolean isCurrentUserAdmin() {
        User user = currentUserOrNull();
        return user != null && (user.getRole() == UserRole.ADMIN
            || user.getRole() == UserRole.SUPER_ADMIN);
    }

    /**
     * TEACHER roli uchun Teacher yozuvini qaytaradi (teacher.user orqali).
     * Rol TEACHER, lekin profil yo'q bo'lsa → 403.
     */
    public Teacher getCurrentTeacherOrThrow() {
        User user = getCurrentUserOrThrow();
        if (user.getRole() != UserRole.TEACHER) {
            throw new ForbiddenException("Faqat o'qituvchilar uchun");
        }
        return teacherRepository.findByUser_Id(user.getId())
            .orElseThrow(() -> new ForbiddenException(
                "O'qituvchi profili topilmadi. Admin bilan bog'laning."));
    }

    /** TEACHER bo'lsa teacher, aks holda empty (ADMIN/boshqalar — cheklovsiz). */
    public java.util.Optional<Teacher> resolveTeacherScope() {
        if (!isCurrentUserTeacher()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(getCurrentTeacherOrThrow());
    }

    public void assertOwnsGroup(Long groupId) {
        if (!isCurrentUserTeacher()) {
            return;
        }
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        assertOwnsGroup(group);
    }

    public void assertOwnsGroup(Group group) {
        if (!isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = getCurrentTeacherOrThrow();
        if (group.getTeacher() == null
            || group.getTeacher().getId() == null
            || !group.getTeacher().getId().equals(teacher.getId())) {
            throw new ForbiddenException("Bu guruh sizga tegishli emas");
        }
    }

    public void assertOwnsStudent(Long studentId) {
        if (!isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = getCurrentTeacherOrThrow();
        if (!studentRepository.existsActiveInTeacherGroups(studentId, teacher.getId())) {
            throw new ForbiddenException("Bu o'quvchi sizga tegishli emas");
        }
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }
}
