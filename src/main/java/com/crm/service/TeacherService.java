package com.crm.service;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.PageResponse;
import com.crm.dto.response.TeacherKpiAttendanceDto;
import com.crm.dto.response.TeacherKpiConversionDto;
import com.crm.dto.response.TeacherKpiDto;
import com.crm.dto.response.TeacherKpiGroupDto;
import com.crm.dto.response.TeacherKpiRankingResponse;
import com.crm.dto.response.TeacherKpiSatisfactionDto;
import com.crm.dto.response.TeacherKpiStudentAttendanceDto;
import com.crm.dto.response.TeacherKpiTrendPointDto;
import com.crm.dto.response.TeacherResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.entity.Classroom;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Payroll;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.PayrollRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeacherService {

    /** /kpi/trend: months berilmasa. */
    private static final int DEFAULT_TREND_MONTHS_PARAM = 12;
    /** /kpi/daily: har bir kun alohida hisoblanadi — oraliq bir oydan oshmasin. */
    private static final int MAX_DAILY_RANGE_DAYS = 31;
    /** Band kod uchrasa keyingisiga o'tamiz; shundan keyin UUID suffiksi. */
    private static final int MAX_TEACHER_CODE_ATTEMPTS = 1000;

    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupScheduleDayRepository groupScheduleDayRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PayrollRepository payrollRepository;
    private final AttendanceRepository attendanceRepository;
    private final TeacherKpiService teacherKpiService;

    @Transactional(readOnly = true)
    public List<TeacherResponse> getAllTeachers(boolean activeOnly) {
        List<Teacher> teachers = activeOnly
            ? teacherRepository.findByIsActiveTrue()
            : teacherRepository.findAll();
        return teachers.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TeacherResponse getTeacherById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public TeacherResponse createTeacher(TeacherRequest request) {
        Teacher teacher = buildFromRequest(new Teacher(), request);
        // is_active ni status belgilaydi (Teacher.setStatus); bo'sh bo'lsa ACTIVE.
        teacher.setStatus(request.getStatus());
        teacher.setTeacherCode(generateTeacherCode());

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            teacher.setUser(user);
        }

        teacher = teacherRepository.save(teacher);
        assignGroupsToTeacher(teacher, request.getGroupIds());
        return toResponse(findById(teacher.getId()));
    }

    /**
     * Status bu yerda O'ZGARTIRILMAYDI: u bog'langan userga ham ta'sir qiladi,
     * shuning uchun {@link StaffStatusService#updateTeacher} orqali o'tadi.
     */
    @Transactional
    public TeacherResponse updateTeacher(Long id, TeacherRequest request) {
        Teacher teacher = findById(id);
        buildFromRequest(teacher, request);

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            teacher.setUser(user);
        }

        teacherRepository.save(teacher);
        assignGroupsToTeacher(teacher, request.getGroupIds());
        return toResponse(findById(id));
    }

    /**
     * Faqat Teacher yozuvining statusi — userga TEGMAYDI. Kirish nuqtasi
     * {@link StaffStatusService#changeTeacherStatus}; bu metod uning ichki qadami.
     *
     * <p>Holat o'zgarmagan bo'lsa hech narsa yozilmaydi.
     */
    @Transactional
    public void changeStatus(Long teacherId, String status) {
        Teacher teacher = findById(teacherId);
        String normalized = Teacher.normalizeStatus(status);
        boolean expectedActive = !Teacher.STATUS_INACTIVE.equals(normalized);
        if (normalized.equals(teacher.getStatus())
                && Boolean.valueOf(expectedActive).equals(teacher.getIsActive())) {
            return;
        }
        String previous = teacher.getStatus();
        teacher.setStatus(normalized);
        teacherRepository.save(teacher);
        log.info("Teacher statusi o'zgardi: teacherId={}, {} -> {}", teacherId, previous, normalized);
    }

    @Transactional
    public TeacherResponse updatePhoto(Long id, String photoUrl) {
        Teacher teacher = findById(id);
        teacher.setPhotoUrl(photoUrl);
        return toResponse(teacherRepository.save(teacher));
    }

    @Transactional(readOnly = true)
    public PageResponse<TeacherResponse> searchTeachers(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Teacher> p = teacherRepository.searchTeachers(query, pageable);
        return PageResponse.<TeacherResponse>builder()
            .content(p.getContent().stream().map(this::toResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTeacherStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", teacherRepository.count());
        stats.put("active", teacherRepository.countByIsActiveTrue());

        Map<String, Long> byStatus = new LinkedHashMap<>();
        teacherRepository.countByStatus().forEach(row -> byStatus.put((String) row[0], (Long) row[1]));
        stats.put("byStatus", byStatus);

        return stats;
    }

    @Transactional(readOnly = true)
    public byte[] exportTeachersCsv() {
        List<Teacher> teachers = teacherRepository.findAll(Sort.by("createdAt").descending());
        StringBuilder csv = new StringBuilder();
        csv.append("ID,UUID,First Name,Last Name,Phone,Email,Subject,Status,Hire Date,Created At\n");
        for (Teacher t : teachers) {
            csv.append(t.getId()).append(",")
               .append(t.getUuid()).append(",")
               .append(esc(t.getFirstName())).append(",")
               .append(esc(t.getLastName())).append(",")
               .append(esc(t.getPhone())).append(",")
               .append(esc(t.getEmail())).append(",")
               .append(esc(t.getSubjectSpecialization())).append(",")
               .append(esc(t.getStatus())).append(",")
               .append(t.getHireDate()).append(",")
               .append(t.getCreatedAt()).append("\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTeacherDashboard(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(
                "User not found with username: " + username));

        Teacher teacher = findTeacherByUserId(user.getId());

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("teacherId", teacher.getId());
        dashboard.put("teacherName", teacher.getFirstName() + " " + teacher.getLastName());

        List<Group> myGroups = groupRepository.findByTeacher_IdAndStatus(
            teacher.getId(), GroupStatus.ACTIVE);
        dashboard.put("totalGroups", myGroups.size());

        dashboard.put("groups", myGroups.stream().map(g -> {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getId());
            gm.put("groupName", g.getGroupName());
            gm.put("courseName", g.getCourse() != null
                ? g.getCourse().getCourseName() : null);
            gm.put("studentCount",
                studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()));
            gm.put("scheduleDays",
                groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(g.getId())
                    .stream()
                    .map(d -> {
                        Map<String, Object> dm = new LinkedHashMap<>();
                        dm.put("dayOfWeek", d.getDayOfWeek());
                        dm.put("startTime", d.getStartTime() != null ? d.getStartTime() : "");
                        dm.put("endTime", d.getEndTime() != null ? d.getEndTime() : "");
                        return dm;
                    })
                    .collect(Collectors.toList()));
            return gm;
        }).collect(Collectors.toList()));

        String todayDay = java.time.LocalDate.now().getDayOfWeek().toString();
        List<Map<String, Object>> todayLessons = new ArrayList<>();
        for (Group g : myGroups) {
            for (GroupScheduleDay d : groupScheduleDayRepository
                    .findByGroup_IdOrderByDayOfWeekAsc(g.getId())) {
                if (d.getDayOfWeek() == null
                        || !d.getDayOfWeek().equalsIgnoreCase(todayDay)) {
                    continue;
                }
                Map<String, Object> lesson = new LinkedHashMap<>();
                lesson.put("groupId", g.getId());
                lesson.put("groupName", g.getGroupName());
                lesson.put("startTime", d.getStartTime() != null ? d.getStartTime() : "");
                lesson.put("endTime", d.getEndTime() != null ? d.getEndTime() : "");
                lesson.put("roomNumber", d.getRoom() != null ? d.getRoom().getRoomNumber() : "");
                lesson.put("studentCount",
                    studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()));
                todayLessons.add(lesson);
            }
        }
        todayLessons.sort((a, b) -> String.valueOf(a.get("startTime"))
            .compareTo(String.valueOf(b.get("startTime"))));
        dashboard.put("todayLessons", todayLessons);

        long totalStudents = myGroups.stream()
            .mapToLong(g -> studentGroupRepository.countByGroup_IdAndIsActiveTrue(g.getId()))
            .sum();
        dashboard.put("totalStudents", totalStudents);

        java.time.LocalDate now = java.time.LocalDate.now();
        payrollRepository.findByTeacherIdAndMonthAndYear(
                teacher.getId(), now.getMonthValue(), now.getYear())
            .ifPresent(p -> {
                dashboard.put("salary", p.getNetSalary());
                dashboard.put("salaryStatus", p.getStatus());
            });

        return dashboard;
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpi(Long teacherId, LocalDate from, LocalDate to) {
        return getKpi(teacherId, from, to, "monthly");
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpi(Long teacherId, LocalDate from, LocalDate to, String period) {
        Teacher teacher = findById(teacherId);
        String normalizedPeriod = TeacherKpiService.normalizePeriod(period);

        TeacherKpiDto dto = new TeacherKpiDto();
        dto.setTeacherId(teacherId);
        dto.setTeacherName(teacher.getFirstName() + " " + teacher.getLastName());
        dto.setPeriod(normalizedPeriod);

        fillFinancialKpi(dto, teacherId, from, to);

        List<Group> groups = groupRepository.findByTeacherId(teacherId);
        List<TeacherKpiGroupDto> groupDtos = groups.stream().map(g -> {
            TeacherKpiGroupDto gd = new TeacherKpiGroupDto();
            gd.setGroupId(g.getId());
            gd.setGroupName(g.getGroupName());
            long count = studentGroupRepository.countByGroupIdAndIsActiveTrue(g.getId());
            gd.setStudentCount(count);
            fillGroupRoomInfo(g, gd, count);
            return gd;
        }).collect(Collectors.toList());
        dto.setGroups(groupDtos);

        List<Long> groupIds = groups.stream().map(Group::getId).collect(Collectors.toList());

        TeacherKpiConversionDto conv = new TeacherKpiConversionDto();
        if (!groupIds.isEmpty()) {
            long newStudents = studentGroupRepository
                .countByGroupIdsAndJoinDateBetween(groupIds, from, to);
            long paidStudents = studentGroupRepository
                .countPaidByGroupIdsAndJoinDateBetween(groupIds, from, to);
            conv.setNewStudents(newStudents);
            conv.setPaidStudents(paidStudents);
            conv.setConversionRate(newStudents > 0
                ? round1(paidStudents * 100.0 / newStudents) : 0.0);
        }
        dto.setConversion(conv);

        TeacherKpiAttendanceDto att = new TeacherKpiAttendanceDto();
        if (!groupIds.isEmpty()) {
            long planned = countPlannedLessons(groups, from, to);
            long conducted = attendanceRepository
                .countDistinctSessionsByGroupIdsAndDateBetween(groupIds, from, to);
            att.setPlannedLessons(planned);
            att.setConductedLessons(conducted);
            att.setMissedLessons(Math.max(0, planned - conducted));
        }
        att.setPenaltyAmount(BigDecimal.ZERO);
        dto.setTeacherAttendance(att);

        TeacherKpiStudentAttendanceDto sAtt = new TeacherKpiStudentAttendanceDto();
        if (!groupIds.isEmpty()) {
            long present = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.PRESENT, from, to)
                + attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                    groupIds, AttendanceStatus.LATE, from, to);
            long absent = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.ABSENT, from, to);
            long excused = attendanceRepository.countByGroupIdsAndStatusAndDateBetween(
                groupIds, AttendanceStatus.EXCUSED, from, to);
            long total = present + absent + excused;
            if (total > 0) {
                sAtt.setPresentRate(round1(present * 100.0 / total));
                sAtt.setAbsentUnexcusedRate(round1(absent * 100.0 / total));
                sAtt.setAbsentExcusedRate(round1(excused * 100.0 / total));
            }
        }
        dto.setStudentAttendance(sAtt);

        dto.setSatisfaction(new TeacherKpiSatisfactionDto());
        dto.setStudentProgress(0.0);

        // 4 mezonli skor + trend (mavjud KPI ustiga)
        dto.setCurrent(teacherKpiService.computeScores(teacherId, from, to));
        dto.setTrend(teacherKpiService.buildTrend(teacherId, normalizedPeriod, from, to));

        return dto;
    }

    @Transactional(readOnly = true)
    public TeacherKpiRankingResponse getKpiRanking(String period, LocalDate from, LocalDate to) {
        return teacherKpiService.getRanking(period, from, to);
    }

    private void fillFinancialKpi(TeacherKpiDto dto, Long teacherId, LocalDate from, LocalDate to) {
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal bonus = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;

        for (Payroll p : payrollRepository.findByTeacherId(teacherId)) {
            if (!payrollOverlapsRange(p, from, to)) {
                continue;
            }
            if (p.getNetSalary() != null) {
                balance = balance.add(p.getNetSalary());
            }
            if (p.getAllowances() != null) {
                bonus = bonus.add(p.getAllowances());
            }
            if (p.getDeductions() != null) {
                penalty = penalty.add(p.getDeductions());
            }
        }

        dto.setBalance(balance);
        dto.setBonus(bonus);
        dto.setAdvance(BigDecimal.ZERO);
        dto.setPenalty(penalty);
    }

    private static boolean payrollOverlapsRange(Payroll p, LocalDate from, LocalDate to) {
        if (p.getMonth() == null || p.getYear() == null) {
            return false;
        }
        LocalDate periodStart = LocalDate.of(p.getYear(), p.getMonth(), 1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return !periodEnd.isBefore(from) && !periodStart.isAfter(to);
    }

    private void fillGroupRoomInfo(Group g, TeacherKpiGroupDto gd, long count) {
        Classroom classroom = groupScheduleDayRepository
            .findByGroup_IdOrderByDayOfWeekAsc(g.getId()).stream()
            .map(GroupScheduleDay::getRoom)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (classroom != null) {
            String roomName = classroom.getRoomNumber() != null
                ? classroom.getRoomNumber() : classroom.getRoomName();
            gd.setRoomName(roomName);
            int cap = classroom.getCapacity() != null ? classroom.getCapacity() : 0;
            gd.setCapacity(cap);
            gd.setFreeSeats(Math.max(0, cap - (int) count));
        } else if (g.getRoom() != null && !g.getRoom().isBlank()) {
            gd.setRoomName(g.getRoom());
            int cap = g.getMaxStudents() != null ? g.getMaxStudents() : 0;
            gd.setCapacity(cap);
            gd.setFreeSeats(Math.max(0, cap - (int) count));
        }
    }

    private long countPlannedLessons(List<Group> groups, LocalDate from, LocalDate to) {
        long planned = 0;
        for (Group g : groups) {
            for (GroupScheduleDay d : groupScheduleDayRepository
                    .findByGroup_IdOrderByDayOfWeekAsc(g.getId())) {
                if (d.getDayOfWeek() == null || d.getDayOfWeek().isBlank()) {
                    continue;
                }
                DayOfWeek dow;
                try {
                    dow = DayOfWeek.valueOf(d.getDayOfWeek().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                LocalDate cur = from;
                while (!cur.isAfter(to)) {
                    if (cur.getDayOfWeek() == dow) {
                        planned++;
                    }
                    cur = cur.plusDays(1);
                }
            }
        }
        return planned;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public Teacher findById(Long id) {
        return teacherRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", id));
    }

    public Teacher findTeacherByUserId(Long userId) {
        return teacherRepository.findByUser_Id(userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Sizning profilingiz o'qituvchi sifatida topilmadi"));
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpiForUser(Long userId, LocalDate from, LocalDate to) {
        return getKpiForUser(userId, from, to, "monthly");
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpiForUser(Long userId, LocalDate from, LocalDate to, String period) {
        Teacher teacher = findTeacherByUserId(userId);
        return getKpi(teacher.getId(), from, to, period);
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpiForUsername(String username, LocalDate from, LocalDate to) {
        return getKpiForUsername(username, from, to, "monthly");
    }

    @Transactional(readOnly = true)
    public TeacherKpiDto getKpiForUsername(String username, LocalDate from, LocalDate to, String period) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(
                "User not found with username: " + username));
        return getKpiForUser(user.getId(), from, to, period);
    }

    /** /me/... yo'llari uchun: tizimga kirgan userning Teacher profili ID si. */
    @Transactional(readOnly = true)
    public Long getTeacherIdForUsername(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(
                "User not found with username: " + username));
        return findTeacherByUserId(user.getId()).getId();
    }

    /**
     * GET /kpi/trend. Oraliq: from/to berilsa ular ustun, aks holda oxirgi
     * {@code months} oy (birinchi oyning 1-kunidan bugungacha).
     * Hisoblash — faqat {@link TeacherKpiService#buildTrend}.
     */
    @Transactional(readOnly = true)
    public List<TeacherKpiTrendPointDto> getKpiTrend(
            Long teacherId, Integer months, String period, LocalDate from, LocalDate to) {
        findById(teacherId);
        int monthCount = months != null ? months : DEFAULT_TREND_MONTHS_PARAM;
        if (monthCount < 1 || monthCount > TeacherKpiService.MAX_TREND_MONTHS) {
            throw new BadRequestException(
                "months 1 dan " + TeacherKpiService.MAX_TREND_MONTHS + " gacha bo'lishi kerak");
        }
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null
            ? from
            : YearMonth.from(end).minusMonths(monthCount - 1L).atDay(1);
        String normalizedPeriod = TeacherKpiService.normalizePeriod(period);
        if ("daily".equals(normalizedPeriod)) {
            validateDailyRange(start, end);
        } else if (start.isAfter(end)) {
            throw new BadRequestException("from sanasi to dan keyin bo'lishi mumkin emas");
        }
        return teacherKpiService.buildTrend(teacherId, normalizedPeriod, start, end);
    }

    /**
     * GET /kpi/daily. Oraliq: from + to, yoki year + month (butun oy).
     * Ikkalasi kelsa from/to ustun (trend bilan bir xil qoida); hech biri
     * kelmasa — joriy oy boshidan bugungacha.
     */
    @Transactional(readOnly = true)
    public List<TeacherKpiTrendPointDto> getKpiDaily(
            Long teacherId, Integer year, Integer month, LocalDate from, LocalDate to) {
        findById(teacherId);
        LocalDate start;
        LocalDate end;
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw new BadRequestException("from va to birga yuborilishi kerak");
            }
            start = from;
            end = to;
        } else if (year != null || month != null) {
            if (year == null || month == null) {
                throw new BadRequestException("year va month birga yuborilishi kerak");
            }
            if (month < 1 || month > 12) {
                throw new BadRequestException("month 1 dan 12 gacha bo'lishi kerak");
            }
            YearMonth ym = YearMonth.of(year, month);
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
        } else {
            end = LocalDate.now();
            start = end.withDayOfMonth(1);
        }
        validateDailyRange(start, end);
        return teacherKpiService.buildTrend(teacherId, "daily", start, end);
    }

    /** Kunlik rejimda har bir kun alohida hisoblanadi — oraliq cheklanadi. */
    private static void validateDailyRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BadRequestException("from sanasi to dan keyin bo'lishi mumkin emas");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAILY_RANGE_DAYS) {
            throw new BadRequestException(
                "Kunlik KPI oralig'i " + MAX_DAILY_RANGE_DAYS + " kundan oshmasligi kerak");
        }
    }

    @Transactional
    public Map<String, Object> linkTeacherUsers() {
        int linked = 0;
        int skipped = 0;
        for (Teacher teacher : teacherRepository.findByUserIsNull()) {
            Optional<User> match = resolveUserForTeacher(teacher);
            if (match.isEmpty()) {
                skipped++;
                continue;
            }
            User user = match.get();
            if (teacherRepository.findByUser_Id(user.getId()).isPresent()) {
                skipped++;
                continue;
            }
            teacher.setUser(user);
            teacherRepository.save(teacher);
            linked++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkedCount", linked);
        result.put("skippedCount", skipped);
        result.put("remainingUnlinked", teacherRepository.findByUserIsNull().size());
        return result;
    }

    // ------------------------------------------------------------------
    // User <-> Teacher profil sinxronizatsiyasi
    // ------------------------------------------------------------------

    /** {@link #ensureTeacherProfile(User)} bitta user uchun nima qilganini bildiradi. */
    public enum ProfileOutcome {
        /** Roli TEACHER emas — tegilmadi. */
        SKIPPED,
        /** Yangi Teacher yozuvi yaratildi. */
        CREATED,
        /** Telefon bo'yicha topilgan egasiz profil shu userga biriktirildi. */
        LINKED,
        /** Mavjud profilning ism/telefoni Userdan yangilandi. */
        UPDATED,
        /** Profil bor edi va allaqachon mos edi. */
        UNCHANGED
    }

    /**
     * User saqlangandan keyingi YAGONA kirish nuqtasi — UserService shu metodni
     * chaqiradi, endpointlar qoidani o'zida takrorlamaydi.
     *
     * <p>User faolligi yagona manba: Teacher profili unga ergashadi.
     * Rol TEACHER bo'lmasa profil O'CHIRILMAYDI, faqat nofaol qilinadi.
     */
    @Transactional
    public void syncTeacherProfile(User user) {
        if (user == null) {
            return;
        }
        if (user.getRole() != UserRole.TEACHER) {
            deactivateTeacherProfile(user);
            return;
        }
        ensureTeacherProfile(user);
    }

    /** {@code isActive} NULL bo'lsa faol deb qaraladi (entity defaulti ham TRUE). */
    private static boolean isUserActive(User user) {
        return !Boolean.FALSE.equals(user.getIsActive());
    }

    /**
     * TEACHER rolidagi user uchun Teacher profili borligini kafolatlaydi.
     *
     * <p>Idempotent — necha marta chaqirilsa ham bitta profil hosil bo'ladi:
     * <ol>
     *   <li>rol TEACHER emas &rarr; {@link ProfileOutcome#SKIPPED};</li>
     *   <li>user_id bo'yicha profil bor &rarr; faqat ism/familiya/telefon sinxronlanadi;</li>
     *   <li>profil yo'q, lekin shu telefonli va user_id si NULL profil bor &rarr; o'shanga
     *       biriktiriladi (yangi yaratilmaydi, chunki guruhlar va ish haqi tarixi
     *       eski yozuvga bog'langan bo'lishi mumkin);</li>
     *   <li>aks holda yangi profil yaratiladi.</li>
     * </ol>
     */
    @Transactional
    public ProfileOutcome ensureTeacherProfile(User user) {
        if (user == null || user.getRole() != UserRole.TEACHER) {
            return ProfileOutcome.SKIPPED;
        }

        Optional<Teacher> linked = teacherRepository.findByUser_Id(user.getId());
        if (linked.isPresent()) {
            Teacher teacher = linked.get();
            if (!syncFromUser(teacher, user)) {
                return ProfileOutcome.UNCHANGED;
            }
            teacherRepository.save(teacher);
            return ProfileOutcome.UPDATED;
        }

        String phone = normalize(user.getPhone());
        if (phone != null) {
            Optional<Teacher> orphan = teacherRepository.findFirstByPhoneAndUserIsNull(phone);
            if (orphan.isPresent()) {
                Teacher teacher = orphan.get();
                teacher.setUser(user);
                syncFromUser(teacher, user);
                teacherRepository.save(teacher);
                log.info("Teacher profili userga biriktirildi: teacherId={}, userId={}",
                    teacher.getId(), user.getId());
                return ProfileOutcome.LINKED;
            }
        }

        Teacher teacher = new Teacher();
        teacher.setUser(user);
        teacher.setFirstName(user.getFirstName());
        teacher.setLastName(user.getLastName());
        // Teacher.phone NOT NULL, User.phone esa ixtiyoriy — telefonsiz user
        // tufayli profil yaratilmay qolmasligi uchun bo'sh satr yoziladi.
        teacher.setPhone(phone != null ? phone : "");
        teacher.setEmail(normalize(user.getEmail()));
        teacher.setTeacherCode(generateTeacherCode());
        // Bloklangan user uchun bloklangan profil: sync-from-users eski nofaol
        // o'qituvchilarni ro'yxatga qaytarib yubormasligi kerak.
        teacher.setStatus(isUserActive(user) ? Teacher.STATUS_ACTIVE : Teacher.STATUS_INACTIVE);
        teacher = teacherRepository.save(teacher);
        log.info("Teacher profili yaratildi: teacherId={}, userId={}, code={}",
            teacher.getId(), user.getId(), teacher.getTeacherCode());
        return ProfileOutcome.CREATED;
    }

    /**
     * Rol TEACHER dan boshqasiga o'zgarganda chaqiriladi.
     *
     * <p>Yozuv O'CHIRILMAYDI: unga guruhlar, davomat va ish haqi tarixi bog'langan.
     * Faqat status INACTIVE ga o'tadi, shuning uchun rol qaytarilsa
     * {@link #ensureTeacherProfile(User)} o'sha profilni qayta topadi.
     */
    @Transactional
    public void deactivateTeacherProfile(User user) {
        if (user == null) {
            return;
        }
        teacherRepository.findByUser_Id(user.getId()).ifPresent(teacher -> {
            if (Teacher.STATUS_INACTIVE.equals(teacher.getStatus())
                    && Boolean.FALSE.equals(teacher.getIsActive())) {
                return;
            }
            teacher.setStatus(Teacher.STATUS_INACTIVE);
            teacherRepository.save(teacher);
            log.info("Teacher profili nofaol qilindi (rol o'zgardi): teacherId={}, userId={}, newRole={}",
                teacher.getId(), user.getId(), user.getRole());
        });
    }

    /**
     * Ism/familiya/telefon va FAOLLIKni Userdan ko'chiradi; o'zgargan bo'lsa true.
     *
     * <p>Status faqat faollik bayrog'i o'zgarganda yoziladi: aks holda userning
     * har bir tahriri qo'lda qo'yilgan ON_LEAVE ni jimgina ACTIVE ga qaytarardi.
     */
    private boolean syncFromUser(Teacher teacher, User user) {
        boolean changed = false;
        if (user.getFirstName() != null && !user.getFirstName().equals(teacher.getFirstName())) {
            teacher.setFirstName(user.getFirstName());
            changed = true;
        }
        if (user.getLastName() != null && !user.getLastName().equals(teacher.getLastName())) {
            teacher.setLastName(user.getLastName());
            changed = true;
        }
        String phone = normalize(user.getPhone());
        if (phone != null && !phone.equals(teacher.getPhone())) {
            teacher.setPhone(phone);
            changed = true;
        }
        boolean active = isUserActive(user);
        if (!Boolean.valueOf(active).equals(teacher.getIsActive())) {
            // Teacher.setIsActive statusni ham moslaydi: false -> INACTIVE,
            // true -> INACTIVE dan ACTIVE ga (ON_LEAVE saqlanadi).
            teacher.setIsActive(active);
            changed = true;
        }
        return changed;
    }

    /**
     * Keyingi bo'sh TCH kodi. Format createTeacher dagi bilan bir xil (TCH-001),
     * lekin band kod ustiga yozilmaydi: yolg'iz count() o'chirilgan yoki qo'lda
     * kiritilgan yozuvlarda takrorlanuvchi kod berardi.
     */
    private String generateTeacherCode() {
        long next = teacherRepository.count() + 1;
        for (int attempt = 0; attempt < MAX_TEACHER_CODE_ATTEMPTS; attempt++) {
            String candidate = "TCH-" + String.format("%03d", next + attempt);
            if (!teacherRepository.existsByTeacherCode(candidate)) {
                return candidate;
            }
        }
        return "TCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Optional<User> resolveUserForTeacher(Teacher teacher) {
        List<User> byName = userRepository.findByFirstNameAndLastNameAndRole(
            teacher.getFirstName(), teacher.getLastName(), UserRole.TEACHER);
        if (byName.size() == 1) {
            return Optional.of(byName.get(0));
        }
        if (teacher.getPhone() != null && !teacher.getPhone().isBlank()) {
            Optional<User> byPhone = userRepository.findByPhoneAndRole(
                teacher.getPhone(), UserRole.TEACHER);
            if (byPhone.isPresent()) {
                return byPhone;
            }
        }
        if (teacher.getEmail() != null && !teacher.getEmail().isBlank()) {
            Optional<User> byEmail = userRepository.findByEmail(teacher.getEmail());
            if (byEmail.isPresent() && byEmail.get().getRole() == UserRole.TEACHER) {
                return byEmail;
            }
        }
        return Optional.empty();
    }

    private void assignGroupsToTeacher(Teacher teacher, List<Long> groupIds) {
        if (groupIds == null) {
            return;
        }
        for (Long groupId : groupIds) {
            groupRepository.findById(groupId).ifPresent(group -> {
                group.setTeacher(teacher);
                groupRepository.save(group);
            });
        }
    }

    private Teacher buildFromRequest(Teacher t, TeacherRequest req) {
        t.setFirstName(req.getFirstName());
        t.setLastName(req.getLastName());
        t.setPhone(req.getPhone());
        t.setEmail(req.getEmail());
        t.setSubjectSpecialization(req.getSubjectSpecialization());
        t.setMonthlySalary(req.getMonthlySalary());
        t.setHireDate(req.getHireDate());
        t.setNotes(req.getNotes());
        t.setGender(req.getGender());
        t.setDateOfBirth(req.getDateOfBirth());
        t.setFatherName(req.getFatherName());
        t.setMotherName(req.getMotherName());
        t.setAddress(req.getAddress());
        t.setPermanentAddress(req.getPermanentAddress());
        t.setPassportInfo(req.getPassportInfo());
        t.setQualification(req.getQualification());
        t.setWorkExperience(req.getWorkExperience());
        t.setJoiningDate(req.getJoiningDate());
        t.setBasicSalary(req.getBasicSalary());
        if (req.getMedicalLeaves() != null) {
            t.setMedicalLeaves(req.getMedicalLeaves());
        }
        if (req.getCasualLeaves() != null) {
            t.setCasualLeaves(req.getCasualLeaves());
        }
        if (req.getMaternityLeaves() != null) {
            t.setMaternityLeaves(req.getMaternityLeaves());
        }
        if (req.getSickLeaves() != null) {
            t.setSickLeaves(req.getSickLeaves());
        }
        if (req.getPhotoUrl() != null) {
            t.setPhotoUrl(req.getPhotoUrl());
        }
        return t;
    }

    private TeacherResponse toResponse(Teacher t) {
        long activeGroups = t.getGroups().stream()
            .filter(g -> g.getStatus() == GroupStatus.ACTIVE)
            .count();
        List<TeacherResponse.GroupSummary> groupSummaries = t.getGroups().stream()
            .map(g -> TeacherResponse.GroupSummary.builder()
                .id(g.getId())
                .groupName(g.getGroupName())
                .courseName(g.getCourse() != null ? g.getCourse().getCourseName() : null)
                .build())
            .collect(Collectors.toList());
        return TeacherResponse.builder()
            .id(t.getId())
            .userId(t.getUser() != null ? t.getUser().getId() : null)
            .uuid(t.getUuid())
            .firstName(t.getFirstName()).lastName(t.getLastName())
            .phone(t.getPhone()).email(t.getEmail())
            .subjectSpecialization(t.getSubjectSpecialization())
            .monthlySalary(t.getMonthlySalary()).hireDate(t.getHireDate())
            .isActive(t.getIsActive()).notes(t.getNotes())
            .activeGroupsCount((int) activeGroups)
            .teacherCode(t.getTeacherCode()).gender(t.getGender())
            .dateOfBirth(t.getDateOfBirth())
            .fatherName(t.getFatherName()).motherName(t.getMotherName())
            .address(t.getAddress()).permanentAddress(t.getPermanentAddress())
            .passportInfo(t.getPassportInfo()).qualification(t.getQualification())
            .workExperience(t.getWorkExperience()).joiningDate(t.getJoiningDate())
            .status(t.getStatus()).basicSalary(t.getBasicSalary())
            .medicalLeaves(t.getMedicalLeaves()).casualLeaves(t.getCasualLeaves())
            .maternityLeaves(t.getMaternityLeaves()).sickLeaves(t.getSickLeaves())
            .photoUrl(t.getPhotoUrl())
            .groups(groupSummaries)
            .createdAt(t.getCreatedAt())
            .build();
    }

    private String esc(String val) {
        if (val == null) {
            return "";
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
