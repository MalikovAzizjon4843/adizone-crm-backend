package com.crm.service;

import com.crm.entity.Group;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Davomat qaysi darslarga MAJBURIY — "belgilanmagan" hisoblarining YAGONA manbasi:
 * {@code GET /api/attendance/missing}, {@code /missing/my} (o'qituvchi bosh sahifasi),
 * {@code GET /api/teacher/dashboard} bugungi darslari, Mini App {@code GET /api/app/teacher/today}, direktor
 * dashboard'idagi "davomat intizomi" ({@code AttendanceMetricsService} — {@link #isDueOn} orqali).
 *
 * <p>Sana {@code d} da dars davomati kerak, agar:
 * <ol>
 *   <li>guruh holati ACTIVE yoki FORMING — COMPLETED/CANCELLED guruh umuman chiqmaydi (ilgari yopilgan
 *       guruhning eski kunlari o'qituvchiga "belgilanmagan" bo'lib qolib ketardi);</li>
 *   <li>{@code d ≤ group.end_date} (bo'lsa);</li>
 *   <li>dars kuni — {@link GroupScheduleService#isLessonDay}: jadval, bayram yo'q, CANCELLED/MOVED (asl kun)
 *       emas; EXTRA va MOVED (yangi kun) — bor;</li>
 *   <li>{@code d ≥ max(group.start_date, guruhdagi eng erta join_date, oraliq boshi)} — guruh boshlanmagan
 *       yoki hali hech kim qo'shilmagan kunlar emas (ilgari 30 kun orqaga qarab, guruh 29.09 da boshlansa
 *       ham 05.09 dan "belgilanmagan" chiqardi);</li>
 *   <li>shu kuni guruhda kamida bitta faol o'quvchi: {@code join_date ≤ d}, chiqmagan ({@code d < leave_date}),
 *       muzlatilmagan ({@code d < frozen_from}); sinov ham sanaladi.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AttendanceDueService {

    /** Oraliq berilmasa — bugundan necha kun orqaga qaraladi. */
    public static final int DEFAULT_LOOKBACK_DAYS = 30;

    /** Davomat kutiladigan guruh holatlari. */
    public static final Set<GroupStatus> OPEN_STATUSES = EnumSet.of(GroupStatus.ACTIVE, GroupStatus.FORMING);

    private final GroupScheduleService groupScheduleService;
    private final HolidayRepository holidayRepository;
    private final LessonExceptionRepository lessonExceptionRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final Clock billingClock;

    /** Davomati kerak bo'lgan dars: sana, hafta kuni (KATTA harf), boshlanish vaqti ("HH:mm" yoki null). */
    public record DueLesson(LocalDate date, String dayOfWeek, String startTime) {
    }

    public LocalDate today() {
        return LocalDate.now(billingClock);
    }

    /**
     * Belgilanmagan darslar {@code [from, to]}: {@code from} null — bugun − {@value #DEFAULT_LOOKBACK_DAYS};
     * {@code to} null yoki kelajak — bugun.
     */
    @Transactional(readOnly = true)
    public List<DueLesson> missing(Group group, LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate rangeTo = to == null || to.isAfter(today) ? today : to;
        LocalDate rangeFrom = from != null ? from : today.minusDays(DEFAULT_LOOKBACK_DAYS);
        List<DueLesson> due = dueLessons(group, rangeFrom, rangeTo);
        if (due.isEmpty()) {
            return due;
        }
        Set<LocalDate> marked = new HashSet<>(attendanceRepository.findDistinctDatesByGroupAndDateBetween(
            group.getId(), due.get(0).date(), due.get(due.size() - 1).date()));
        return due.stream().filter(l -> !marked.contains(l.date())).toList();
    }

    /** Shu kuni davomat kerakmi (bugungi darslar ro'yxatlari uchun). */
    @Transactional(readOnly = true)
    public boolean isDue(Group group, LocalDate date) {
        return !dueLessons(group, date, date).isEmpty();
    }

    /** Davomati kerak bo'lgan darslar {@code [from, to]} (sana tartibida). */
    @Transactional(readOnly = true)
    public List<DueLesson> dueLessons(Group group, LocalDate from, LocalDate to) {
        if (group == null || from == null || to == null || !isOpen(group)) {
            return List.of();
        }
        if (group.getEndDate() != null && group.getEndDate().isBefore(to)) {
            to = group.getEndDate();
        }
        List<StudentGroup> enrollments = studentGroupRepository.findByGroupId(group.getId());
        LocalDate start = effectiveStart(group, enrollments, from);
        if (start == null || start.isAfter(to)) {
            return List.of();
        }

        Map<DayOfWeek, String> startTimes = new LinkedHashMap<>();
        for (GroupScheduleService.LessonSlot slot : groupScheduleService.lessonSlots(group.getId())) {
            try {
                startTimes.putIfAbsent(DayOfWeek.valueOf(slot.dayOfWeek()), slot.startTime());
            } catch (IllegalArgumentException ignored) {
                // noma'lum kun nomi — GroupScheduleService.lessonWeekdays bilan bir xil
            }
        }
        List<LessonException> exceptions = lessonExceptionRepository.findByGroupIdOrderByLessonDateDesc(group.getId());
        if (startTimes.isEmpty() && exceptions.isEmpty()) {
            return List.of();
        }
        String firstStart = startTimes.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, to).stream()
            .map(Holiday::getHolidayDate).collect(Collectors.toSet());

        List<DueLesson> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            if (!isDueOn(group, enrollments, d,
                    GroupScheduleService.isLessonDay(d, startTimes.keySet(), holidays.contains(d), exceptions))) {
                continue;
            }
            String time = startTimes.containsKey(d.getDayOfWeek()) ? startTimes.get(d.getDayOfWeek()) : firstStart;
            out.add(new DueLesson(d, d.getDayOfWeek().name(), time));
        }
        return out;
    }

    /** Guruh davomat kutadimi: ACTIVE yoki FORMING (holat null — standart ACTIVE). */
    public static boolean isOpen(Group group) {
        return group.getStatus() == null || OPEN_STATUSES.contains(group.getStatus());
    }

    /**
     * Sof qoida — bitta (guruh, kun) uchun: guruh ochiq, {@code start_date ≤ d ≤ end_date}, dars kuni
     * ({@code lessonDay} — jadval/bayram/istisno, chaqiruvchi hisoblaydi) va faol yozilma bor.
     */
    public static boolean isDueOn(Group group, List<StudentGroup> enrollments, LocalDate d, boolean lessonDay) {
        if (!lessonDay || !isOpen(group)) {
            return false;
        }
        if (group.getStartDate() != null && d.isBefore(group.getStartDate())) {
            return false;
        }
        if (group.getEndDate() != null && d.isAfter(group.getEndDate())) {
            return false;
        }
        return hasActiveStudent(enrollments, d);
    }

    /** {@code max(group.start_date, eng erta join_date, from)}; guruhda yozilma yo'q — null. */
    static LocalDate effectiveStart(Group group, List<StudentGroup> enrollments, LocalDate from) {
        LocalDate earliestJoin = enrollments.stream()
            .map(StudentGroup::getJoinDate)
            .filter(java.util.Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElse(null);
        if (earliestJoin == null) {
            return null;
        }
        LocalDate start = from;
        if (group.getStartDate() != null && group.getStartDate().isAfter(start)) {
            start = group.getStartDate();
        }
        return earliestJoin.isAfter(start) ? earliestJoin : start;
    }

    /** Shu kuni faol yozilma: qo'shilgan, chiqmagan, muzlatilmagan (sinov ham). */
    public static boolean hasActiveStudent(List<StudentGroup> enrollments, LocalDate d) {
        for (StudentGroup sg : enrollments) {
            if (sg.getJoinDate() == null || sg.getJoinDate().isAfter(d)) {
                continue;
            }
            if (sg.getFrozenFrom() != null && !d.isBefore(sg.getFrozenFrom())) {
                continue;
            }
            if (sg.getLeaveDate() != null) {
                if (d.isBefore(sg.getLeaveDate())) {
                    return true;
                }
                continue;
            }
            // leave_date yo'q: faol yozilma; nofaol bo'lsa — eski nomuvofiq qator, sanalmaydi
            if (Boolean.TRUE.equals(sg.getIsActive())) {
                return true;
            }
        }
        return false;
    }
}
