package com.crm.service;

import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudentPaymentLifecycleService {

    private final StudentGroupRepository studentGroupRepository;
    private final PaymentRepository paymentRepository;
    private final AttendanceRepository attendanceRepository;
    private final TelegramService telegramService;
    private final ParentRepository parentRepository;
    private final StudentRepository studentRepository;

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

        updatePaymentStatus(sg, attended, lessonDate);
        studentGroupRepository.save(sg);
    }

    private void updatePaymentStatus(StudentGroup sg, int attended, LocalDate lessonDate) {
        if (attended == 1) {
            sg.setPaymentStatus("TRIAL");
            log.info("Student {} — 1st lesson (TRIAL)", sg.getStudent().getId());
            return;
        }

        boolean hasPaid = hasCurrentMonthPayment(
            sg.getStudent().getId(),
            sg.getGroup().getId(),
            lessonDate);

        if (hasPaid) {
            sg.setPaymentStatus("PAID");
            return;
        }

        if (attended == 2) {
            sg.setPaymentStatus("PENDING");
            notifyPaymentDue(sg);
            return;
        }

        if (attended == 3) {
            sg.setPaymentStatus("SUSPENDED");
            sg.setSuspendedAt(LocalDateTime.now());
            sg.setSuspensionReason("3 ta darsdan keyin to'lov qilinmadi");
            log.info("Student {} SUSPENDED", sg.getStudent().getId());
            notifySuspension(sg);
            return;
        }

        if (attended > 3) {
            if (!"SUSPENDED".equals(sg.getPaymentStatus())) {
                sg.setPaymentStatus("OVERDUE");
            }
        }
    }

    private boolean hasCurrentMonthPayment(Long studentId, Long groupId, LocalDate date) {
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());
        return paymentRepository.countPaidForStudentGroupInPeriod(studentId, groupId, monthStart, monthEnd) > 0;
    }

    @Transactional
    public void onPaymentReceived(Long studentId, Long groupId, LocalDate paymentDate) {
        StudentGroup sg = studentGroupRepository
            .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, groupId)
            .orElse(null);
        if (sg == null) {
            return;
        }

        String prevStatus = sg.getPaymentStatus();
        sg.setPaymentStatus("PAID");
        sg.setLastPaymentDate(paymentDate);
        sg.setNextPaymentDue(paymentDate.plusMonths(1).withDayOfMonth(1));
        sg.setSuspendedAt(null);
        sg.setSuspensionReason(null);

        if ("ARCHIVED".equals(prevStatus) || "SUSPENDED".equals(prevStatus)) {
            log.info("Student {} reactivated after payment", studentId);
        }

        studentGroupRepository.save(sg);
    }

    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void checkOverduePayments() {
        log.info("Checking overdue payments...");
        LocalDate today = LocalDate.now();

        List<StudentGroup> activeGroups = studentGroupRepository.findAllActiveEnrollments();

        for (StudentGroup sg : activeGroups) {
            if ("TRIAL".equals(sg.getPaymentStatus())) {
                continue;
            }

            boolean hasPaid = hasCurrentMonthPayment(
                sg.getStudent().getId(),
                sg.getGroup().getId(),
                today);

            if (!hasPaid && today.getDayOfMonth() > 5 && "PAID".equals(sg.getPaymentStatus())) {
                sg.setPaymentStatus("OVERDUE");
                studentGroupRepository.save(sg);
                notifyPaymentDue(sg);
            }
        }
    }

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void archiveInactiveStudents() {
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);

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
                studentGroupRepository.save(sg);

                Student student = sg.getStudent();
                student.setStatus(com.crm.entity.enums.StudentStatus.FROZEN);
                studentRepository.save(student);

                log.info("Student {} archived — no attendance since {}", student.getId(), lastAttendance);
            }
        }
    }

    private void notifyPaymentDue(StudentGroup sg) {
        try {
            List<Parent> parents = parentRepository.findByStudentId(sg.getStudent().getId());
            BigDecimal monthly = sg.getMonthlyPriceOverride() != null
                ? sg.getMonthlyPriceOverride()
                : (sg.getGroup().getCourse() != null ? sg.getGroup().getCourse().getMonthlyPrice() : BigDecimal.ZERO);
            double amt = monthly != null ? monthly.doubleValue() : 0.0;

            for (Parent p : parents) {
                if (p.getTelegramChatId() != null && !p.getTelegramChatId().isBlank()) {
                    String msg = telegramService.buildPaymentMessage(
                        sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName(),
                        sg.getGroup().getGroupName(),
                        0,
                        amt);
                    telegramService.sendMessage(p.getTelegramChatId(), msg);
                }
            }
        } catch (Exception e) {
            log.error("Notify error: {}", e.getMessage());
        }
    }

    private void notifySuspension(StudentGroup sg) {
        try {
            List<Parent> parents = parentRepository.findByStudentId(sg.getStudent().getId());
            String msg = String.format(
                "⚠️ <b>Darsga qabul qilinmadi</b>%n%n"
                    + "👤 <b>O'quvchi:</b> %s%n"
                    + "📚 <b>Guruh:</b> %s%n%n"
                    + "❌ 3 ta darsdan keyin to'lov qilinmagani "
                    + "uchun o'quvchi darsga qabul qilinmadi.%n"
                    + "To'lov qiling va davom eting.%n%n"
                    + "📞 Adizone o'quv markazi",
                sg.getStudent().getFirstName() + " " + sg.getStudent().getLastName(),
                sg.getGroup().getGroupName()
            );
            for (Parent p : parents) {
                if (p.getTelegramChatId() != null && !p.getTelegramChatId().isBlank()) {
                    telegramService.sendMessage(p.getTelegramChatId(), msg);
                }
            }
        } catch (Exception e) {
            log.error("Suspension notify error: {}", e.getMessage());
        }
    }
}
