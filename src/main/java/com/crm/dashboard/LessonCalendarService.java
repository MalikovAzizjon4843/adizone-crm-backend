package com.crm.dashboard;

import com.crm.entity.Group;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.GroupRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.UserRepository;
import com.crm.service.TeacherAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dam olish kunlari va dars istisnolari (director-dashboard §3.5, G8) — SA, A; istisnoni guruh
 * o'qituvchisi ham kiritadi. Rejadagi darslar ({@link AttendanceMetricsService}) va
 * {@code GroupScheduleService.hasLessonOn} shulardan foydalanadi.
 */
@Service
@RequiredArgsConstructor
public class LessonCalendarService {

    private final HolidayRepository holidayRepository;
    private final LessonExceptionRepository exceptionRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;
    private final DirectorDashboardService dashboardService;
    private final Clock billingClock;

    public record HolidayRequest(LocalDate date, String name) {
    }

    public record ExceptionRequest(LocalDate lessonDate, LessonException.Kind kind, LocalDate movedTo, String reason) {
    }

    @Transactional(readOnly = true)
    public List<Holiday> holidays(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now(billingClock).withDayOfYear(1);
        LocalDate t = to != null ? to : f.plusYears(1).minusDays(1);
        if (ChronoUnit.DAYS.between(f, t) > 731) {
            throw CodedException.badRequest("dashboard.period.invalid", 731);
        }
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(f, t);
    }

    @Transactional
    public Holiday addHoliday(HolidayRequest r) {
        if (r == null || r.date() == null || r.name() == null || r.name().isBlank()) {
            throw CodedException.badRequest("holiday.invalid");
        }
        if (holidayRepository.existsById(r.date())) {
            throw new ConflictException("holiday.exists", r.date());
        }
        Holiday h = holidayRepository.save(Holiday.builder().holidayDate(r.date()).name(r.name().trim())
            .createdBy(currentUserId()).createdAt(LocalDateTime.now(billingClock)).build());
        dashboardService.invalidate();
        return h;
    }

    @Transactional
    public void deleteHoliday(LocalDate date) {
        if (!holidayRepository.existsById(date)) {
            throw CodedException.notFound("holiday.notFound", date);
        }
        holidayRepository.deleteById(date);
        dashboardService.invalidate();
    }

    @Transactional(readOnly = true)
    public List<LessonException> exceptions(Long groupId) {
        teacherAccessService.assertOwnsGroup(requireGroup(groupId));
        return exceptionRepository.findByGroupIdOrderByLessonDateDesc(groupId);
    }

    @Transactional
    public LessonException addException(Long groupId, ExceptionRequest r) {
        Group group = requireGroup(groupId);
        teacherAccessService.assertOwnsGroup(group);
        if (r == null || r.lessonDate() == null || r.kind() == null) {
            throw CodedException.badRequest("lessonException.invalid");
        }
        if (r.kind() == LessonException.Kind.MOVED && (r.movedTo() == null || r.movedTo().equals(r.lessonDate()))) {
            throw CodedException.badRequest("lessonException.movedToRequired");
        }
        boolean duplicate = exceptionRepository.findByGroupIdAndLessonDate(groupId, r.lessonDate()).stream()
            .anyMatch(e -> e.getKind() == r.kind());
        if (duplicate) {
            throw new ConflictException("lessonException.exists");
        }
        LessonException e = exceptionRepository.save(LessonException.builder()
            .groupId(groupId).lessonDate(r.lessonDate()).kind(r.kind())
            .movedTo(r.kind() == LessonException.Kind.MOVED ? r.movedTo() : null)
            .reason(r.reason() != null ? r.reason().trim() : null)
            .createdBy(currentUserId()).createdAt(LocalDateTime.now(billingClock)).build());
        dashboardService.invalidate();
        return e;
    }

    @Transactional
    public void deleteException(Long groupId, Long id) {
        teacherAccessService.assertOwnsGroup(requireGroup(groupId));
        LessonException e = exceptionRepository.findById(id)
            .filter(x -> x.getGroupId().equals(groupId))
            .orElseThrow(() -> CodedException.notFound("lessonException.notFound", id));
        exceptionRepository.delete(e);
        dashboardService.invalidate();
    }

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
            .orElseThrow(() -> CodedException.notFound("error.group.notFound", groupId));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).map(u -> u.getId()).orElse(null);
    }
}
