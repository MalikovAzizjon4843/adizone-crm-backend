package com.crm.service;

import com.crm.entity.Leave;
import com.crm.entity.Teacher;
import com.crm.entity.enums.LeaveStatus;
import com.crm.repository.LeaveRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * O'qituvchi statusi ta'til bo'yicha (leaves-exams-contracts §1.2): bugun APPROVED ta'tilda —
 * {@code ACTIVE → ON_LEAVE}, ta'til tugagach — {@code ON_LEAVE → ACTIVE}. Faqat shu ikki holat;
 * {@code INACTIVE} ga tegilmaydi. O'zgarish {@link StaffStatusService} orqali (user bilan izchil).
 *
 * <p>Qo'lda qo'yilgan ON_LEAVE (ta'til yozuvisiz) qaytarilmaydi — faqat tugagan APPROVED ta'tili bor
 * o'qituvchi ACTIVE ga qaytadi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaveStatusJob {

    private final LeaveRepository leaveRepository;
    private final TeacherRepository teacherRepository;
    private final StaffStatusService staffStatusService;
    private final Clock billingClock;

    @Scheduled(cron = "${app.leave.status-cron:0 10 0 * * *}", zone = "Asia/Tashkent")
    public void run() {
        try {
            sync(LocalDate.now(billingClock));
        } catch (RuntimeException e) {
            log.error("Ta'til statuslari yangilanmadi", e);
        }
    }

    /** Barcha o'qituvchilar: {@code today} bo'yicha. */
    @Transactional
    public void sync(LocalDate today) {
        Set<Long> onLeave = new HashSet<>();
        for (Leave l : leaveRepository.findTeacherLeavesCovering(LeaveStatus.APPROVED, today)) {
            onLeave.add(l.getTeacher().getId());
        }
        for (Long id : onLeave) {
            apply(id, true);
        }
        for (Long id : leaveRepository.findTeacherIdsWithLeaveEndedBefore(LeaveStatus.APPROVED, today)) {
            if (!onLeave.contains(id)) {
                apply(id, false);
            }
        }
    }

    /** Bitta o'qituvchi — ta'til tasdiqlangan/bekor qilinganda darhol. */
    @Transactional
    public void syncTeacher(Long teacherId, LocalDate today) {
        boolean covered = leaveRepository.findTeacherLeavesCovering(LeaveStatus.APPROVED, today).stream()
            .anyMatch(l -> l.getTeacher().getId().equals(teacherId));
        apply(teacherId, covered);
    }

    private void apply(Long teacherId, boolean onLeave) {
        Teacher t = teacherRepository.findById(teacherId).orElse(null);
        if (t == null) {
            return;
        }
        if (onLeave && Teacher.STATUS_ACTIVE.equals(t.getStatus())) {
            staffStatusService.changeTeacherStatus(teacherId, Teacher.STATUS_ON_LEAVE);
            log.info("O'qituvchi ta'tilda: teacherId={}", teacherId);
        } else if (!onLeave && Teacher.STATUS_ON_LEAVE.equals(t.getStatus())) {
            staffStatusService.changeTeacherStatus(teacherId, Teacher.STATUS_ACTIVE);
            log.info("O'qituvchi ta'tildan qaytdi: teacherId={}", teacherId);
        }
    }
}
