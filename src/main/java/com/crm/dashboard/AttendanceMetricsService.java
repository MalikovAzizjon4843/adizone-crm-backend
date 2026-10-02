package com.crm.dashboard;

import com.crm.dashboard.DirectorDtos.AttendanceSection;
import com.crm.dashboard.DirectorDtos.LessonRow;
import com.crm.entity.Group;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.Teacher;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.repository.LessonSubstitutionRepository;
import com.crm.entity.LessonException;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Davomat intizomi (director-dashboard §1.4, §7 #7/#8 qarorlari). Rejadagi dars — ACTIVE guruh,
 * jadval kuni, faol (muzlatilmagan) yozilma bor, bayram/bekor emas; bir kunda bitta dars.
 * O'z vaqtida — o'sha kun 23:59 gacha birinchi belgi.
 */
@Service
@RequiredArgsConstructor
public class AttendanceMetricsService {

    public enum Status { TAKEN, LATE_MARKED, MISSING, UPCOMING, UNPLANNED }

    private final DashboardQueries queries;
    private final GroupScheduleService scheduleService;
    private final HolidayRepository holidayRepository;
    private final LessonExceptionRepository exceptionRepository;
    private final LessonSubstitutionRepository substitutionRepository;

    @Transactional(readOnly = true)
    public AttendanceSection summary(DashboardPeriod p, Long teacherId) {
        List<LessonRow> rows = rows(p, teacherId);
        long taken = count(rows, Status.TAKEN) + count(rows, Status.LATE_MARKED);
        long planned = taken + count(rows, Status.MISSING) + count(rows, Status.UPCOMING);
        return new AttendanceSection(planned, taken, count(rows, Status.TAKEN), count(rows, Status.MISSING),
            count(rows, Status.UNPLANNED), Ratios.percent(taken, planned), count(rows, Status.UPCOMING));
    }

    /** Drill-down; {@code status = PLANNED} — rejadagi hammasi (TAKEN, LATE_MARKED, MISSING, UPCOMING). */
    @Transactional(readOnly = true)
    public List<LessonRow> rows(DashboardPeriod p, Long teacherId, String status) {
        List<LessonRow> all = rows(p, teacherId);
        String s = status == null ? "PLANNED" : status.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PLANNED" -> all.stream().filter(r -> !Status.UNPLANNED.name().equals(r.status())).toList();
            case "TAKEN" -> all.stream().filter(r -> Status.TAKEN.name().equals(r.status())
                || Status.LATE_MARKED.name().equals(r.status())).toList();
            case "MISSING", "LATE_MARKED", "UPCOMING", "UNPLANNED" -> all.stream().filter(r -> s.equals(r.status())).toList();
            default -> throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", "status");
        };
    }

    List<LessonRow> rows(DashboardPeriod p, Long teacherId) {
        LocalDate from = p.from();
        LocalDate to = p.to();
        // "Darsni X o'tdi" (leaves-exams-contracts §2.3): shu dars intizomi o'rinbosarga yoziladi
        Map<String, Teacher> substitutes = new HashMap<>();
        Set<Long> substitutedGroups = new HashSet<>();
        for (LessonSubstitution s : substitutionRepository.findActiveBetween(from, to, SubstitutionStatus.CANCELLED)) {
            substitutes.put(key(s.getGroup().getId(), s.getLessonDate()), s.getSubstituteTeacher());
            if (teacherId != null && teacherId.equals(s.getSubstituteTeacher().getId())) {
                substitutedGroups.add(s.getGroup().getId());
            }
        }
        List<Group> groups = queries.em().createQuery("""
                SELECT g FROM Group g LEFT JOIN FETCH g.teacher WHERE g.status = :active
                """, Group.class).setParameter("active", GroupStatus.ACTIVE).getResultList().stream()
            .filter(g -> teacherId == null || (g.getTeacher() != null && teacherId.equals(g.getTeacher().getId()))
                || substitutedGroups.contains(g.getId()))
            .toList();
        Map<Long, Group> groupById = new HashMap<>();
        groups.forEach(g -> groupById.put(g.getId(), g));

        Map<Long, List<StudentGroup>> enrollments = new HashMap<>();
        if (!groupById.isEmpty()) {
            queries.em().createQuery("SELECT sg FROM StudentGroup sg WHERE sg.group.id IN :ids", StudentGroup.class)
                .setParameter("ids", groupById.keySet()).getResultList()
                .forEach(sg -> enrollments.computeIfAbsent(sg.getGroup().getId(), k -> new ArrayList<>()).add(sg));
        }
        Set<LocalDate> holidays = new HashSet<>();
        holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to).forEach(h -> holidays.add(h.getHolidayDate()));
        Set<String> removed = new HashSet<>();
        Set<String> added = new HashSet<>();
        List<LessonException> exceptions = new ArrayList<>(exceptionRepository.findByLessonDateBetween(from, to));
        exceptions.addAll(exceptionRepository.findByMovedToBetween(from, to));
        for (LessonException e : exceptions) {
            switch (e.getKind()) {
                case CANCELLED -> removed.add(key(e.getGroupId(), e.getLessonDate()));
                case MOVED -> {
                    removed.add(key(e.getGroupId(), e.getLessonDate()));
                    if (e.getMovedTo() != null) {
                        added.add(key(e.getGroupId(), e.getMovedTo()));
                    }
                }
                case EXTRA -> added.add(key(e.getGroupId(), e.getLessonDate()));
            }
        }

        // Davomat: (guruh, kun) → belgilar soni va birinchi belgi payti
        Map<String, Object[]> marks = new HashMap<>();
        for (Object[] r : queries.em().createQuery("""
                SELECT a.group.id, a.attendanceDate, COUNT(a), MIN(a.createdAt) FROM Attendance a
                WHERE a.attendanceDate BETWEEN :f AND :t GROUP BY a.group.id, a.attendanceDate
                """, Object[].class).setParameter("f", from).setParameter("t", to).getResultList()) {
            marks.put(key((Long) r[0], (LocalDate) r[1]), r);
        }

        LocalDateTime now = p.now();
        List<LessonRow> rows = new ArrayList<>();
        Set<String> plannedKeys = new HashSet<>();
        for (Group g : groups) {
            List<GroupScheduleService.LessonSlot> slots = scheduleService.lessonSlots(g.getId());
            Map<DayOfWeek, GroupScheduleService.LessonSlot> slotByDay = new HashMap<>();
            for (GroupScheduleService.LessonSlot s : slots) {
                try {
                    slotByDay.putIfAbsent(DayOfWeek.valueOf(s.dayOfWeek()), s);
                } catch (IllegalArgumentException ignored) {
                    // noma'lum kun nomi
                }
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                String k = key(g.getId(), d);
                boolean scheduled = slotByDay.containsKey(d.getDayOfWeek()) && !removed.contains(k);
                boolean planned = (scheduled || added.contains(k))
                    && !holidays.contains(d)
                    && (g.getStartDate() == null || !d.isBefore(g.getStartDate()))
                    && (g.getEndDate() == null || !d.isAfter(g.getEndDate()));
                if (!planned) {
                    continue;
                }
                int active = activeOn(enrollments.getOrDefault(g.getId(), List.of()), d);
                if (active == 0) {
                    continue;
                }
                plannedKeys.add(k);
                Teacher lessonTeacher = substitutes.getOrDefault(k, g.getTeacher());
                if (teacherId != null && (lessonTeacher == null || !teacherId.equals(lessonTeacher.getId()))) {
                    continue;
                }
                GroupScheduleService.LessonSlot slot = slotByDay.get(d.getDayOfWeek());
                Object[] m = marks.get(k);
                LocalDateTime firstMarked = m != null ? (LocalDateTime) m[3] : null;
                String status;
                if (m != null) {
                    status = firstMarked != null && firstMarked.isBefore(d.plusDays(1).atStartOfDay())
                        ? Status.TAKEN.name() : Status.LATE_MARKED.name();
                } else if (d.isBefore(now.toLocalDate()) || (d.equals(now.toLocalDate()) && ended(slot, d, now))) {
                    status = Status.MISSING.name();
                } else {
                    status = Status.UPCOMING.name();
                }
                rows.add(row(g, lessonTeacher, d, slot, active, m, firstMarked, status));
            }
        }
        // Rejada yo'q, lekin davomat qilingan (jadval eskirgan bo'lishi mumkin)
        for (Map.Entry<String, Object[]> e : marks.entrySet()) {
            Object[] m = e.getValue();
            Group g = groupById.get((Long) m[0]);
            if (g == null || plannedKeys.contains(e.getKey())) {
                continue;
            }
            LocalDate d = (LocalDate) m[1];
            Teacher lessonTeacher = substitutes.getOrDefault(e.getKey(), g.getTeacher());
            if (teacherId != null && (lessonTeacher == null || !teacherId.equals(lessonTeacher.getId()))) {
                continue;
            }
            rows.add(row(g, lessonTeacher, d, null, activeOn(enrollments.getOrDefault(g.getId(), List.of()), d), m,
                (LocalDateTime) m[3], Status.UNPLANNED.name()));
        }
        rows.sort(Comparator.comparing(LessonRow::date).thenComparing(LessonRow::groupId));
        return rows;
    }

    /** {@code teacher} — darsni o'tgan o'qituvchi: o'rinbosar belgisi bo'lsa u, aks holda guruh o'qituvchisi. */
    private static LessonRow row(Group g, Teacher teacher, LocalDate d, GroupScheduleService.LessonSlot slot, int active,
                                 Object[] m, LocalDateTime firstMarked, String status) {
        return new LessonRow(g.getId(), g.getGroupName(),
            teacher != null ? teacher.getId() : null,
            teacher != null ? FunnelMetricsService.name(teacher.getFirstName(), teacher.getLastName()) : null,
            d, slot != null ? slot.startTime() : null, slot != null ? slot.endTime() : null, active,
            m != null ? (Long) m[2] : 0, firstMarked, status);
    }

    /** D kuni faol yozilmalar: qo'shilgan, chiqmagan, muzlatilmagan (§1.4). */
    static int activeOn(List<StudentGroup> sgs, LocalDate d) {
        int n = 0;
        for (StudentGroup sg : sgs) {
            if (sg.getJoinDate() != null && sg.getJoinDate().isAfter(d)) {
                continue;
            }
            if (sg.getLeaveDate() != null && d.isAfter(sg.getLeaveDate()) && sg.getFrozenFrom() == null) {
                continue;
            }
            if (sg.getLeaveDate() == null && !Boolean.TRUE.equals(sg.getIsActive()) && sg.getFrozenFrom() == null) {
                continue;
            }
            if (sg.getFrozenFrom() != null && !d.isBefore(sg.getFrozenFrom())) {
                continue;
            }
            n++;
        }
        return n;
    }

    private static boolean ended(GroupScheduleService.LessonSlot slot, LocalDate d, LocalDateTime now) {
        if (slot == null || slot.endTime() == null) {
            return false;
        }
        try {
            return !now.isBefore(d.atTime(LocalTime.parse(slot.endTime())));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String key(Long groupId, LocalDate d) {
        return groupId + ":" + d;
    }

    private static long count(List<LessonRow> rows, Status s) {
        return rows.stream().filter(r -> s.name().equals(r.status())).count();
    }

}
