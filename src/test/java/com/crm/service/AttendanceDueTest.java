package com.crm.service;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.response.MissingAttendanceResponse;
import com.crm.dto.response.TeacherMissingAttendanceResponse;
import com.crm.entity.Attendance;
import com.crm.entity.Group;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Belgilanmagan" davomat (AttendanceDueService): guruh boshlanishi / eng erta qo'shilish, bayram, dars istisnolari,
 * faol o'quvchi yo'q kunlar; /missing, /missing/my va o'qituvchi bosh sahifasi bir xil natija beradi.
 */
class AttendanceDueTest extends AbstractBillingIT {

    @Autowired AttendanceDueService due;
    @Autowired AttendanceService attendance;
    @Autowired TeacherService teachers;
    @Autowired GroupRepository groupRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;
    @Autowired HolidayRepository holidayRepo;
    @Autowired LessonExceptionRepository exceptionRepo;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired TeacherRepository teacherRepo;
    @Autowired UserRepository userRepo;
    @Autowired GroupScheduleService schedule;

    private Long teacher;

    @BeforeEach
    void today() {
        clock.setDate(d("10.10.2026"));      // shanba
        teacher = fixtures.teacher();
    }

    /** Dushanba / chorshanba / juma 14:00, boshlanish sanasi {@code start}. */
    private Long group(String start) {
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher);
        inTx(() -> {
            Group group = groupRepo.findById(g).orElseThrow();
            group.setStartDate(d(start));
            groupRepo.save(group);
            for (String day : List.of("MONDAY", "WEDNESDAY", "FRIDAY")) {
                scheduleRepo.save(GroupScheduleDay.builder().group(group)
                    .dayOfWeek(day).startTime("14:00").endTime("15:30").build());
            }
        });
        return g;
    }

    private Long join(Long group, String date) {
        return fixtures.enrollment(fixtures.student(), group).start(d(date)).save();
    }

    private void mark(Long sg, String date) {
        inTx(() -> {
            StudentGroup e = sgRepo.findById(sg).orElseThrow();
            attendanceRepo.save(Attendance.builder().student(e.getStudent()).group(e.getGroup())
                .attendanceDate(d(date)).status(AttendanceStatus.PRESENT).build());
        });
    }

    private List<LocalDate> missing(Long group) {
        return inTx(() -> due.missing(groupRepo.findById(group).orElseThrow(), null, null)).stream()
            .map(AttendanceDueService.DueLesson::date).toList();
    }

    @Test
    void groupStarted2909_noMissingBeforeStart_holidayCancelledMovedHandled() {
        Long g = group("29.09.2026");
        Long sg = join(g, "29.09.2026");
        mark(sg, "30.09.2026");
        inTx(() -> {
            holidayRepo.save(Holiday.builder().holidayDate(d("05.10.2026")).name("Bayram")
                .createdAt(LocalDateTime.now()).build());
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("07.10.2026"))
                .kind(LessonException.Kind.CANCELLED).createdAt(LocalDateTime.now()).build());
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("09.10.2026"))
                .kind(LessonException.Kind.MOVED).movedTo(d("10.10.2026")).createdAt(LocalDateTime.now()).build());
        });

        // 30 kunlik orqaga qarash 10.09 dan; ilgari 11.09, 14.09 … 28.09 ham chiqardi
        assertThat(missing(g)).containsExactly(
            d("02.10.2026"),     // juma
            d("10.10.2026"));    // 09.10 dan ko'chirilgan dars (shanba)
        // 30.09 belgilangan, 05.10 bayram, 07.10 bekor, 09.10 ko'chirilgan

        // /api/attendance/missing bilan bir xil
        fixtures.loginAs(UserRole.ADMIN);
        MissingAttendanceResponse api = attendance.getMissingAttendance(g, null, null);
        assertThat(api.getMissingCount()).isEqualTo(2);
        assertThat(api.getMissingDates()).extracting(MissingAttendanceResponse.MissingDateItem::getDate)
            .containsExactly(d("02.10.2026"), d("10.10.2026"));
        assertThat(api.getMissingDates().get(1).getStartTime()).isEqualTo("14:00");
    }

    @Test
    void startIsEarliestJoin_whenGroupStartEarlier() {
        Long g = group("01.01.2026");
        join(g, "05.10.2026");
        join(g, "07.10.2026");
        assertThat(missing(g)).containsExactly(d("05.10.2026"), d("07.10.2026"), d("09.10.2026"));

        Long empty = group("01.09.2026");     // hech kim qo'shilmagan
        assertThat(missing(empty)).isEmpty();
    }

    @Test
    void noActiveStudentThatDay_notMissing() {
        Long g = group("28.09.2026");
        Long left = join(g, "28.09.2026");
        inTx(() -> {
            StudentGroup e = sgRepo.findById(left).orElseThrow();
            e.setIsActive(false);
            e.setLeaveDate(d("02.10.2026"));      // 02.10 dan guruhda hech kim yo'q
            sgRepo.save(e);
        });
        Long frozen = join(g, "07.10.2026");
        inTx(() -> {
            StudentGroup e = sgRepo.findById(frozen).orElseThrow();
            e.setIsActive(false);
            e.setFrozenFrom(d("09.10.2026"));
            e.setLeaveDate(d("09.10.2026"));
            sgRepo.save(e);
        });
        // 28.09, 30.09 (birinchisi bor); 02.10, 05.10 — hech kim; 07.10 — ikkinchisi; 09.10 — muzlatilgan
        assertThat(missing(g)).containsExactly(d("28.09.2026"), d("30.09.2026"), d("07.10.2026"));
    }

    @Test
    void teacherHome_missingMy_andTodayLessons_useSameRule() {
        // O'qituvchi profili joriy userga bog'lanadi
        fixtures.loginAs(UserRole.TEACHER);
        inTx(() -> {
            var t = teacherRepo.findById(teacher).orElseThrow();
            t.setUser(userRepo.findByUsername("test-teacher").orElseThrow());
            teacherRepo.save(t);
        });
        clock.setDate(d("09.10.2026"));      // juma
        Long started = group("29.09.2026");
        join(started, "29.09.2026");
        Long notStarted = group("12.10.2026");
        join(notStarted, "12.10.2026");

        TeacherMissingAttendanceResponse my = attendance.getMyMissingAttendance(null, null);
        assertThat(my.getGroups()).extracting(MissingAttendanceResponse::getGroupId).containsExactly(started);
        // 30.09, 02.10, 05.10, 07.10, 09.10
        assertThat(my.getTotalMissing()).isEqualTo(5);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todayLessons = (List<Map<String, Object>>)
            teachers.getTeacherDashboard("test-teacher").get("todayLessons");
        assertThat(todayLessons).extracting(m -> m.get("groupId")).containsExactly(started);
    }

    @Test
    void lessonDayRule_matchesHasLessonOn() {
        Long g = group("01.09.2026");
        join(g, "01.09.2026");
        inTx(() -> {
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("08.10.2026"))
                .kind(LessonException.Kind.EXTRA).createdAt(LocalDateTime.now()).build());
            exceptionRepo.save(LessonException.builder().groupId(g).lessonDate(d("05.10.2026"))
                .kind(LessonException.Kind.CANCELLED).createdAt(LocalDateTime.now()).build());
        });
        // Davomat belgilash tekshiruvi (hasLessonOn) va belgilanmaganlar ro'yxati bir xil qoidadan
        List<LocalDate> list = missing(g);
        for (LocalDate d = d("01.10.2026"); !d.isAfter(d("10.10.2026")); d = d.plusDays(1)) {
            LocalDate day = d;
            assertThat(list.contains(day)).as(day.toString()).isEqualTo(inTx(() -> schedule.hasLessonOn(g, day)));
        }
        assertThat(list).contains(d("08.10.2026")).doesNotContain(d("05.10.2026"), d("06.10.2026"));
    }
}
