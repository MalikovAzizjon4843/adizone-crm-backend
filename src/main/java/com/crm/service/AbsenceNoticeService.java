package com.crm.service;

import com.crm.dto.response.AbsenceNoticeResponse;
import com.crm.entity.AbsenceNotice;
import com.crm.entity.Group;
import com.crm.entity.Student;
import com.crm.repository.AbsenceNoticeRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sabab bildirishlarni CRM tomonida o'qish (telegram-platform §11.2): davomat ekrani va
 * {@code GET /api/absence-notices}. Yaratish/bekor qilish — Mini App ({@code MiniAppAbsenceService}).
 */
@Service
@RequiredArgsConstructor
public class AbsenceNoticeService {

    private final AbsenceNoticeRepository repository;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final AttendanceAccessService attendanceAccessService;

    /** CRM ro'yxati: SA/A — har qanday guruh; TEACHER — o'z guruhi yoki shu kungi o'rinbosarligi. */
    @Transactional(readOnly = true)
    public List<AbsenceNoticeResponse> forGroup(Long groupId, LocalDate date) {
        attendanceAccessService.assertCanRead(groupId, date);
        return List.copyOf(byStudent(groupId, date).values());
    }

    /** Shu guruh va sanadagi faol bildirishlar, o'quvchi bo'yicha (bir o'quvchiga bitta — V69). */
    @Transactional(readOnly = true)
    public Map<Long, AbsenceNoticeResponse> byStudent(Long groupId, LocalDate date) {
        List<AbsenceNotice> notices = repository.findByGroupIdAndLessonDateAndStatusOrderByIdAsc(groupId, date,
            AbsenceNotice.Status.ACTIVE);
        if (notices.isEmpty()) {
            return Map.of();
        }
        Map<Long, Student> students = studentRepository.findAllById(
                notices.stream().map(AbsenceNotice::getStudentId).distinct().toList()).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        String groupName = groupRepository.findById(groupId).map(Group::getGroupName).orElse(null);
        Map<Long, AbsenceNoticeResponse> out = new LinkedHashMap<>();
        for (AbsenceNotice n : notices) {
            Student s = students.get(n.getStudentId());
            out.put(n.getStudentId(), toResponse(n, s, groupName));
        }
        return out;
    }

    public static AbsenceNoticeResponse toResponse(AbsenceNotice n, Student s, String groupName) {
        return new AbsenceNoticeResponse(n.getId(), n.getStudentId(),
            s != null ? (s.getFirstName() + " " + s.getLastName()).trim() : null,
            n.getGroupId(), groupName, n.getLessonDate(), n.getType().name(), n.getComment(), n.getStatus().name(),
            n.getSubmittedAs(), n.getCreatedAt());
    }
}
