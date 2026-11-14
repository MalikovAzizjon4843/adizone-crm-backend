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
import java.util.HashMap;
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
 *   <li>shu kuni guruhda kamida bitta faol o'quvchi ({@link #isActiveOn}): {@code join_date ≤ d}, chiqmagan
 *       ({@code d < leave_date}), muzlatilmagan ({@code d < frozen_from}); sinov ham sanaladi.</li>
 * </ol>
 *
 * <p>Dars "olingan" — shu kuni faol o'quvchilarning HAMMASIDA yozuv bor ({@link #marking}). Bir qismi
 * belgilangan dars — "qisman": /missing ro'yxatida qoladi ("belgilanmagan: N ta o'quvchi"), dashboardda PARTIAL.
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

    /**
     * Dars davomatining holati — {@link #marking} (YAGONA qoida: /missing, Mini App, direktor dashboardi).
     *
     * @param active    shu kuni faol o'quvchilar ({@link #isActiveOn}; o'quvchi bo'yicha, takror yozilma bir marta)
     * @param unmarked  ulardan davomat yozuvi yo'qlari
     * @param anyMarked shu kuni guruhda kamida bitta yozuv bor
     */
    public record Marking(int active, int unmarked, boolean anyMarked) {

        /** Yozuv bor, lekin faol o'quvchilarning hammasi emas — "qisman" (olingan emas). */
        public boolean partial() {
            return anyMarked && unmarked > 0;
        }

        /** Faol o'quvchilarning hammasi belgilangan — dars olingan. */
        public boolean complete() {
            return anyMarked && unmarked == 0;
        }
    }

    /**
     * Belgilanmagan yoki qisman belgilangan dars.
     *
     * @param activeStudents   shu kuni faol o'quvchilar
     * @param unmarkedStudents ulardan belgilanmaganlar ("belgilanmagan: N ta o'quvchi")
     * @param partial          kimdir belgilangan, lekin hammasi emas (aks holda — umuman olinmagan)
     */
    public record MissingLesson(LocalDate date, String dayOfWeek, String startTime,
                                int activeStudents, int unmarkedStudents, boolean partial) {
    }

    public LocalDate today() {
        return LocalDate.now(billingClock);
    }

    /**
     * Belgilanmagan darslar {@code [from, to]}: {@code from} null — bugun − {@value #DEFAULT_LOOKBACK_DAYS};
     * {@code to} null yoki kelajak — bugun. Qisman belgilangan dars (kimdir belgilanmagan) ham shu ro'yxatda —
     * {@link MissingLesson#partial()} bilan; hamma faol o'quvchi belgilangan dars chiqmaydi ({@link #marking}).
     */
    @Transactional(readOnly = true)
    public List<MissingLesson> missing(Group group, LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate rangeTo = to == null || to.isAfter(today) ? today : to;
        LocalDate rangeFrom = from != null ? from : today.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (group == null) {
            return List.of();
        }
        List<StudentGroup> enrollments = studentGroupRepository.findByGroupId(group.getId());
        List<DueLesson> due = dueLessons(group, enrollments, rangeFrom, rangeTo);
        if (due.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, Set<Long>> marked = new HashMap<>();
        for (Object[] r : attendanceRepository.findMarkedStudentsByGroupAndDateBetween(
                group.getId(), due.get(0).date(), due.get(due.size() - 1).date())) {
            marked.computeIfAbsent((LocalDate) r[0], k -> new HashSet<>()).add((Long) r[1]);
        }
        List<MissingLesson> out = new ArrayList<>();
        for (DueLesson l : due) {
            Marking m = marking(enrollments, marked.getOrDefault(l.date(), Set.of()), l.date());
            if (m.complete()) {
                continue;
            }
            out.add(new MissingLesson(l.date(), l.dayOfWeek(), l.startTime(), m.active(), m.unmarked(), m.partial()));
        }
        return out;
    }

    /** Shu kuni davomat kerakmi (bugungi darslar ro'yxatlari uchun). */
    @Transactional(readOnly = true)
    public boolean isDue(Group group, LocalDate date) {
        return !dueLessons(group, date, date).isEmpty();
    }

    /** Davomati kerak bo'lgan darslar {@code [from, to]} (sana tartibida). */
    @Transactional(readOnly = true)
    public List<DueLesson> dueLessons(Group group, LocalDate from, LocalDate to) {
        if (group == null) {
            return List.of();
        }
        return dueLessons(group, studentGroupRepository.findByGroupId(group.getId()), from, to);
    }

    private List<DueLesson> dueLessons(Group group, List<StudentGroup> enrollments, LocalDate from, LocalDate to) {
        if (group == null || from == null || to == null || !isOpen(group)) {
            return List.of();
        }
        if (group.getEndDate() != null && group.getEndDate().isBefore(to)) {
            to = group.getEndDate();
        }
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

    /** Shu kuni faol yozilma bormi ({@link #isActiveOn}). */
    public static boolean hasActiveStudent(List<StudentGroup> enrollments, LocalDate d) {
        for (StudentGroup sg : enrollments) {
            if (isActiveOn(sg, d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Yozilma D kuni faolmi — YAGONA qoida (davomat ro'yxati {@link AttendanceRosterService}, "belgilanmagan",
     * direktor dashboardi, dars eslatmasi): qo'shilgan ({@code join_date ≤ d}; eski qatorda {@code join_date}
     * yo'q — cheklov yo'q), muzlatilmagan ({@code d < frozen_from}), chiqmagan ({@code d < leave_date} — chiqish
     * kuni endi guruhda emas; {@code leave_date} yo'q bo'lsa {@code is_active}); sinov ham faol.
     */
    public static boolean isActiveOn(StudentGroup sg, LocalDate d) {
        if (sg.getJoinDate() != null && sg.getJoinDate().isAfter(d)) {
            return false;
        }
        if (sg.getFrozenFrom() != null && !d.isBefore(sg.getFrozenFrom())) {
            return false;
        }
        if (sg.getLeaveDate() != null) {
            return d.isBefore(sg.getLeaveDate());
        }
        // leave_date yo'q: faol yozilma; nofaol bo'lsa — eski nomuvofiq qator, sanalmaydi
        return Boolean.TRUE.equals(sg.getIsActive());
    }

    /** D kuni faol o'quvchilar (o'quvchi id; bir o'quvchining takror yozilmasi — bir marta). */
    public static Set<Long> activeStudentIds(List<StudentGroup> enrollments, LocalDate d) {
        Set<Long> ids = new HashSet<>();
        for (StudentGroup sg : enrollments) {
            if (sg.getStudent() != null && isActiveOn(sg, d)) {
                ids.add(sg.getStudent().getId());
            }
        }
        return ids;
    }

    /**
     * Dars davomati holati — YAGONA qoida: faol o'quvchilardan ({@link #activeStudentIds}) yozuvi
     * ({@code markedStudentIds}) yo'qlar soni. Yozuv umuman yo'q — olinmagan; bir qismi — qisman; hammasi — olingan.
     */
    public static Marking marking(List<StudentGroup> enrollments, Set<Long> markedStudentIds, LocalDate d) {
        Set<Long> active = activeStudentIds(enrollments, d);
        int unmarked = 0;
        for (Long id : active) {
            if (!markedStudentIds.contains(id)) {
                unmarked++;
            }
        }
        return new Marking(active.size(), unmarked, !markedStudentIds.isEmpty());
    }
}
