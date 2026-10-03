package com.crm.miniapp;

import com.crm.entity.Attendance;
import com.crm.entity.AttendanceUnlockRequest;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.LessonSubstitution;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.SubstitutionStatus;
import com.crm.entity.enums.UnlockRequestStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.AttendanceUnlockRequestRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.LessonSubstitutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O'qituvchi rejimi (docs/design/telegram-platform.md §11.4). Davomat {@code AttendanceService} orqali — u
 * "bugun"ni tizim soatidan oladi, shuning uchun test soati ham haqiqiy bugungi sanaga qo'yiladi; guruhda har kuni dars.
 */
class MiniAppTeacherTest extends MiniAppItBase {

    private static final long TEACHER_TG = 1401;

    @Autowired private GroupScheduleDayRepository scheduleDayRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private AttendanceUnlockRequestRepository unlockRepository;
    @Autowired private LessonSubstitutionRepository substitutionRepository;

    private LocalDate today;
    private User teacherUser;
    private Long teacherId;
    private Long groupId;
    private Long studentA;
    private Long studentB;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(ZoneId.of("Asia/Tashkent"));
        clock.setDate(today);
        String phone = phone();
        teacherUser = staff(UserRole.TEACHER, phone);
        teacherId = teacherProfile(teacherUser);
        groupId = dailyGroup(teacherId);
        studentA = student("Ali", "Karimov", phone(), null);
        studentB = student("Vali", "Karimov", phone(), null);
        fixtures.enrollment(studentA, groupId).start(today.minusDays(30)).save();
        fixtures.enrollment(studentB, groupId).start(today.minusDays(30)).save();
        shareContact(TEACHER_TG, phone);
        token = appToken(TEACHER_TG);
    }

    private Long dailyGroup(Long teacher) {
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher);
        inTx(() -> {
            for (String day : new String[]{"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"}) {
                scheduleDayRepository.save(GroupScheduleDay.builder()
                    .group(groupRepository.findById(g).orElseThrow())
                    .dayOfWeek(day).startTime("09:00").endTime("10:30").build());
            }
        });
        return g;
    }

    private ResultActions save(Long group, LocalDate date, String items) throws Exception {
        return mvc.perform(put("/api/app/teacher/attendance/{id}", group).param("date", date.toString())
            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":" + items + "}"));
    }

    @Test
    void profile_hasTeacherRole_andToday() throws Exception {
        mvc.perform(get("/api/app/me").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.roles[0]").value("TEACHER"))
            .andExpect(jsonPath("$.data.teacher.teacherId").value(teacherId));
        mvc.perform(get("/api/app/teacher/today").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lessons", hasSize(1)))
            .andExpect(jsonPath("$.data.lessons[0].groupId").value(groupId))
            .andExpect(jsonPath("$.data.lessons[0].role").value("ORIGINAL"))
            .andExpect(jsonPath("$.data.lessons[0].canMark").value(true))
            .andExpect(jsonPath("$.data.lessons[0].total").value(2))
            .andExpect(jsonPath("$.data.lessons[0].marked").value(0));
        // O'quvchi ekrani — o'quvchisi yo'q
        mvc.perform(get("/api/app/home").header("Authorization", bearer(token)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.student.forbidden"));
    }

    @Test
    void markToday_viaAttendanceService() throws Exception {
        mvc.perform(get("/api/app/teacher/attendance/{id}", groupId).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.editable").value(true))
            .andExpect(jsonPath("$.data.students", hasSize(2)));

        save(groupId, today, "[{\"studentId\":" + studentA + ",\"status\":\"PRESENT\"},"
                + "{\"studentId\":" + studentB + ",\"status\":\"ABSENT\",\"notes\":\"Kelmadi\"}]")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.students[0].status").value("PRESENT"))
            .andExpect(jsonPath("$.data.students[1].status").value("ABSENT"));

        List<Attendance> rows = inTx(() -> attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, today));
        assertThat(rows).hasSize(2);
        assertThat(inTx(() -> rows.stream().map(a -> attendanceRepository.findById(a.getId()).orElseThrow()
            .getMarkedBy().getId()).toList())).containsOnly(teacherUser.getId());
        // Mavjud qoida: ABSENT sababsiz — 400
        save(groupId, today, "[{\"studentId\":" + studentB + ",\"status\":\"ABSENT\"}]").andExpect(status().isBadRequest());
        save(groupId, today, "[{\"studentId\":" + studentB + ",\"status\":\"MAYBE\"}]")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.attendance.statusInvalid"));
    }

    @Test
    void pastDate_locked_untilUnlockApproved() throws Exception {
        LocalDate yesterday = today.minusDays(1);
        mvc.perform(get("/api/app/teacher/attendance/{id}", groupId).param("date", yesterday.toString())
                .header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.editable").value(false))
            .andExpect(jsonPath("$.data.lockReason").value("PAST_DATE"));
        save(groupId, yesterday, "[{\"studentId\":" + studentA + ",\"status\":\"PRESENT\"}]")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("attendance.locked"))
            .andExpect(jsonPath("$.data.reason").value("PAST_DATE"));
        save(groupId, today.plusDays(1), "[{\"studentId\":" + studentA + ",\"status\":\"PRESENT\"}]")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("app.attendance.future"));

        // Ochish so'rovi — mavjud servis orqali
        mvc.perform(post("/api/app/teacher/unlock-requests").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":" + groupId + ",\"date\":\"" + yesterday + "\",\"note\":\"Unutibman\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(get("/api/app/teacher/unlock-requests").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data", hasSize(1)));

        inTx(() -> {
            AttendanceUnlockRequest r = unlockRepository.findAll().get(0);
            r.setStatus(UnlockRequestStatus.APPROVED);
            r.setReviewedAt(LocalDateTime.now());
        });
        mvc.perform(get("/api/app/teacher/attendance/{id}", groupId).param("date", yesterday.toString())
                .header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.editable").value(true))
            .andExpect(jsonPath("$.data.unlockRequest.status").value("APPROVED"));
        save(groupId, yesterday, "[{\"studentId\":" + studentA + ",\"status\":\"PRESENT\"}]").andExpect(status().isOk());
    }

    @Test
    void idor_foreignGroupAndStudent_403() throws Exception {
        Long foreignGroup = dailyGroup(fixtures.teacher());
        mvc.perform(get("/api/app/teacher/attendance/{id}", foreignGroup).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        Long foreignStudent = student("Begona", "Bola", phone(), null);
        fixtures.enrollment(foreignStudent, foreignGroup).start(today.minusDays(30)).save();
        save(foreignGroup, today, "[{\"studentId\":" + foreignStudent + ",\"status\":\"PRESENT\"}]")
            .andExpect(status().isForbidden());
        // O'z guruhi, begona o'quvchi
        save(groupId, today, "[{\"studentId\":" + foreignStudent + ",\"status\":\"PRESENT\"}]")
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.student.forbidden"));
        assertThat(inTx(() -> attendanceRepository.findByGroup_IdAndAttendanceDate(foreignGroup, today))).isEmpty();
    }

    @Test
    void substitution_roles() throws Exception {
        // Mening guruhimni bugun boshqa o'qituvchi o'tadi — belgilay olmayman
        Long other = fixtures.teacher();
        inTx(() -> substitutionRepository.save(LessonSubstitution.builder()
            .group(groupRepository.findById(groupId).orElseThrow()).lessonDate(today)
            .originalTeacher(teacherRepository.findById(teacherId).orElseThrow())
            .substituteTeacher(teacherRepository.findById(other).orElseThrow())
            .status(SubstitutionStatus.PLANNED).createdAt(LocalDateTime.now()).build()));
        // Men boshqa guruhda o'rinbosarman
        Long foreignGroup = dailyGroup(other);
        Long s = student("Sami", "Aliyev", phone(), null);
        fixtures.enrollment(s, foreignGroup).start(today.minusDays(30)).save();
        inTx(() -> substitutionRepository.save(LessonSubstitution.builder()
            .group(groupRepository.findById(foreignGroup).orElseThrow()).lessonDate(today)
            .originalTeacher(teacherRepository.findById(other).orElseThrow())
            .substituteTeacher(teacherRepository.findById(teacherId).orElseThrow())
            .status(SubstitutionStatus.PLANNED).createdAt(LocalDateTime.now()).build()));

        mvc.perform(get("/api/app/teacher/today").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.data.lessons", hasSize(2)))
            .andExpect(jsonPath("$.data.lessons[?(@.groupId == " + groupId + ")].canMark").value(false))
            .andExpect(jsonPath("$.data.lessons[?(@.groupId == " + groupId + ")].substituteTeacherName").isNotEmpty())
            .andExpect(jsonPath("$.data.lessons[?(@.groupId == " + foreignGroup + ")].role").value("SUBSTITUTE"))
            .andExpect(jsonPath("$.data.lessons[?(@.groupId == " + foreignGroup + ")].canMark").value(true));

        save(groupId, today, "[{\"studentId\":" + studentA + ",\"status\":\"PRESENT\"}]").andExpect(status().isForbidden());
        save(foreignGroup, today, "[{\"studentId\":" + s + ",\"status\":\"PRESENT\"}]").andExpect(status().isOk());
    }

    @Test
    void nonTeacherIdentity_andAdminPhone_noTeacherMode() throws Exception {
        String parentPhone = phone();
        student("Ali", "Valiyev", phone(), parentPhone);
        shareContact(1402, parentPhone);
        mvc.perform(get("/api/app/teacher/today").header("Authorization", bearer(appToken(1402))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("app.forbidden"));

        // ADMIN telefoni — o'qituvchi rejimi berilmaydi (D9), hech narsaga mos emas
        String adminPhone = phone();
        staff(UserRole.ADMIN, adminPhone);
        shareContact(1403, adminPhone);
        assertThat(identityRepository.findByTelegramUserId(1403L)).isEmpty();

        // O'qituvchi bloklansa — rejim darhol yo'qoladi
        inTx(() -> userRepository.findById(teacherUser.getId()).orElseThrow().setIsActive(false));
        mvc.perform(get("/api/app/teacher/today").header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
    }
}
