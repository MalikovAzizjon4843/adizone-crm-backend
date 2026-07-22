package com.crm.service;

import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Davomat va arxivlash yordamchi xizmati.
 * To'lov statusi faqat {@link PaymentScheduleService} (sana/period) orqali yangilanadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentPaymentLifecycleService {

    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final PaymentScheduleService paymentScheduleService;

    /**
     * Davomat belgilanganda faqat dars soni / birinchi dars sanasi yangilanadi.
     * paymentStatus o'zgartirilmaydi — schedule service yagona manba.
     */
    @Transactional
    public void onLessonAttended(Long studentId, Long groupId, LocalDate lessonDate) {
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElse(null);
        if (sg == null) {
            return;
        }

        int attended = (sg.getLessonsAttended() == null ? 0 : sg.getLessonsAttended()) + 1;
        sg.setLessonsAttended(attended);

        if (sg.getFirstLessonDate() == null) {
            sg.setFirstLessonDate(lessonDate);
        }

        studentGroupRepository.save(sg);
        log.info("Lesson attended student={} group={} date={} total={} (paymentStatus unchanged)",
            studentId, groupId, lessonDate, attended);
    }

    /**
     * O'chirilgan: PaymentScheduleService.updateOverdueStatusesDaily (00:05) sana asosida qiladi.
     */
    // @Scheduled(cron = "0 0 9 * * *") — disabled, use PaymentScheduleService instead
    @Deprecated
    public void checkOverduePayments() {
        log.debug("checkOverduePayments disabled — use PaymentScheduleService.updateOverdueStatusesDaily");
    }

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void archiveInactiveStudents() {
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        Set<Long> affectedStudentIds = new HashSet<>();

        List<StudentGroup> activeGroups = studentGroupRepository.findAllActiveEnrollments();

        for (StudentGroup sg : activeGroups) {
            if ("TRIAL".equals(sg.getPaymentStatus())) {
                continue;
            }

            LocalDate lastAttendance = attendanceRepository.findLastAttendanceDate(
                sg.getStudent().getId(),
                sg.getGroup().getId());

            if (lastAttendance != null && lastAttendance.isBefore(thirtyDaysAgo)) {
                sg.setPaymentStatus("ARCHIVED");
                sg.setIsActive(false);
                sg.setLeaveDate(LocalDate.now());
                studentGroupRepository.save(sg);

                Student student = sg.getStudent();
                student.setStatus(com.crm.entity.enums.StudentStatus.FROZEN);
                studentRepository.save(student);
                affectedStudentIds.add(student.getId());

                log.info("Student {} archived — no attendance since {}", student.getId(), lastAttendance);
            }
        }

        for (Long studentId : affectedStudentIds) {
            Student student = studentRepository.findById(studentId).orElse(null);
            if (student != null) {
                paymentScheduleService.recalculateAllForStudent(student);
            }
        }
    }
}
