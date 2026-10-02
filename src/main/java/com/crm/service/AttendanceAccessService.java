package com.crm.service;

import com.crm.entity.Group;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Teacher;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.LessonSubstitutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Davomat ruxsati (leaves-exams-contracts §2.3):
 * <table>
 *   <tr><td>SA, A</td><td>har doim</td></tr>
 *   <tr><td>guruh o'qituvchisi</td><td>o'z guruhi, shu sanada faol "darsni X o'tdi" belgisi bo'lmasa</td></tr>
 *   <tr><td>o'rinbosar</td><td>faqat belgi bor (CANCELLED emas) kun — o'sha guruh va sana</td></tr>
 * </table>
 * O'tgan sana uchun ochish (unlock) qoidasi o'rinbosarga ham amal qiladi ({@code teacher_id} = o'rinbosar).
 */
@Service
@RequiredArgsConstructor
public class AttendanceAccessService {

    private final TeacherAccessService teacherAccessService;
    private final LessonSubstitutionRepository substitutionRepository;
    private final GroupRepository groupRepository;

    /** Belgilash (va unlock so'rovi) — o'rinbosar kunida asosiy o'qituvchi ham 403. */
    public void assertCanMark(Group group, LocalDate date) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Teacher me = teacherAccessService.getCurrentTeacherOrThrow();
        Optional<LessonSubstitution> sub = date != null ? substitutionRepository.findActive(group.getId(), date)
            : Optional.empty();
        if (sub.isPresent()) {
            if (sub.get().getSubstituteTeacher().getId().equals(me.getId())) {
                return;
            }
            throw CodedException.forbidden("substitution.lessonTaken",
                LessonSubstitutionService.teacherName(sub.get().getSubstituteTeacher()));
        }
        teacherAccessService.assertOwnsGroup(group);
    }

    /** O'qish: guruh o'qituvchisi — har doim; o'rinbosar — faqat o'sha sana. */
    public void assertCanRead(Long groupId, LocalDate date) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        Teacher me = teacherAccessService.getCurrentTeacherOrThrow();
        if (group.getTeacher() != null && me.getId().equals(group.getTeacher().getId())) {
            return;
        }
        boolean substitute = date != null && substitutionRepository.findActive(groupId, date)
            .map(s -> s.getSubstituteTeacher().getId().equals(me.getId()))
            .orElse(false);
        if (!substitute) {
            teacherAccessService.assertOwnsGroup(group);
        }
    }
}
