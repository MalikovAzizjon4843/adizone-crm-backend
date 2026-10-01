package com.crm.service;

import com.crm.billing.BillingStatusService;
import com.crm.billing.EnrollmentLifecycleService;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Davomat hisoblagichlari va avto-arxiv. Pul/holat — billing paketida (ledger, snapshot).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentPaymentLifecycleService {

    private static final List<AttendanceStatus> ATTENDED =
        List.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);
    static final int AUTO_ARCHIVE_DAYS = 30;

    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final EnrollmentLifecycleService lifecycleService;
    private final BillingStatusService statusService;

    /**
     * Dars soni va birinchi dars sanasi davomatdan QAYTA HISOBLANADI — qayta yuborish
     * yoki holat o'zgarishi hisoblagichni buzmaydi (billing v2 §6.9, idempotent).
     */
    @Transactional
    public void onLessonAttended(Long studentId, Long groupId, LocalDate lessonDate) {
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElse(null);
        if (sg == null) {
            return;
        }
        int attended = (int) attendanceRepository.countByStudentAndGroupAndStatuses(studentId, groupId, ATTENDED);
        sg.setLessonsAttended(attended);
        sg.setFirstLessonDate(attendanceRepository.findFirstDateByStatuses(studentId, groupId, ATTENDED));
        studentGroupRepository.save(sg);
        log.debug("Lesson counters student={} group={} date={} attended={}", studentId, groupId, lessonDate, attended);
    }

    /**
     * Avto-arxiv (§13 #25): 30 kundan beri davomati yo'q faol yozilma §6.7 dagi muzlatish
     * bilan yopiladi ({@code freezeDate = bugun}, qaytarim va tarix bilan). Har SG o'z
     * tranzaksiyasida — biri yiqilsa qolganlari davom etadi.
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tashkent")
    public void archiveInactiveStudents() {
        LocalDate threshold = statusService.today().minusDays(AUTO_ARCHIVE_DAYS);
        int frozen = 0;
        for (StudentGroup sg : studentGroupRepository.findAllActiveEnrollments()) {
            if (Boolean.TRUE.equals(sg.getIsTrial()) || sg.getStudent() == null || sg.getGroup() == null) {
                continue;
            }
            LocalDate last = attendanceRepository.findLastAttendanceDate(sg.getStudent().getId(), sg.getGroup().getId());
            if (last == null || !last.isBefore(threshold)) {
                continue;
            }
            try {
                lifecycleService.freezeInNewTransaction(sg.getId(), "AUTO_ARCHIVE",
                    "Avto-arxiv: " + AUTO_ARCHIVE_DAYS + " kundan beri davomat yo'q (oxirgi " + last + ")");
                frozen++;
            } catch (RuntimeException e) {
                log.error("Avto-arxiv sg={} muzlatilmadi: {}", sg.getId(), e.getMessage());
            }
        }
        if (frozen > 0) {
            log.info("Avto-arxiv: {} yozilma muzlatildi", frozen);
        }
    }
}
