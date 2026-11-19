package com.crm.security;

import com.crm.entity.Timetable;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.GroupRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.TimetableRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dars jadvali: standart holda faqat ACTIVE/FORMING guruhlar va {@code end_date} si o'tmaganlari
 * (grid/by-room: {@code ?date} bo'yicha, aks holda bugun). {@code ?includeClosed=true} — hammasi,
 * faqat SUPER_ADMIN/ADMIN. Test soati: 15.09.2026 (seshanba).
 */
class TimetableClosedGroupsTest extends Phase5ItBase {

    @Autowired TimetableRepository timetableRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired TeacherRepository teacherRepository;

    private User admin;
    private User teacherUser;
    private Long teacherId;

    private Long active;
    private Long forming;
    private Long completed;
    private Long cancelled;
    private Long endedBeforeToday;
    private Long endsNextWeekend;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
        teacherUser = newUser(UserRole.TEACHER);
        teacherId = fixtures.teacher();
        Long course = fixtures.course(500_000);

        active = groupWithLesson(course, GroupStatus.ACTIVE, null, 9);
        forming = groupWithLesson(course, GroupStatus.FORMING, null, 10);
        completed = groupWithLesson(course, GroupStatus.COMPLETED, null, 11);
        cancelled = groupWithLesson(course, GroupStatus.CANCELLED, null, 12);
        endedBeforeToday = groupWithLesson(course, GroupStatus.ACTIVE, LocalDate.of(2026, 9, 10), 13);
        endsNextWeekend = groupWithLesson(course, GroupStatus.ACTIVE, LocalDate.of(2026, 9, 18), 14);
    }

    /** Guruh + seshanba kungi dars (har biri o'z soatida — konflikt yo'q). */
    private Long groupWithLesson(Long courseId, GroupStatus status, LocalDate endDate, int hour) {
        Long groupId = fixtures.group(courseId, status, teacherId);
        inTx(() -> {
            var g = groupRepository.findById(groupId).orElseThrow();
            g.setEndDate(endDate);
            groupRepository.save(g);
            timetableRepository.save(Timetable.builder()
                .group(g)
                .teacher(teacherRepository.findById(teacherId).orElseThrow())
                .dayOfWeek("TUESDAY")
                .startTime(LocalTime.of(hour, 0))
                .endTime(LocalTime.of(hour, 50))
                .build());
        });
        return groupId;
    }

    private Set<Long> groupIds(User actor, String url, String path) throws Exception {
        String body = mvc.perform(get(url).with(as(actor)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, path);
        return ids.stream().map(Number::longValue).collect(Collectors.toSet());
    }

    @Test
    void grid_defaultHidesClosedAndEndedGroups() throws Exception {
        assertThat(groupIds(admin, "/api/timetable/grid?dayOfWeek=TUESDAY", "$.data.entries[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, endsNextWeekend);
    }

    @Test
    void grid_includeClosed_showsEverything_forAdmin() throws Exception {
        assertThat(groupIds(admin, "/api/timetable/grid?dayOfWeek=TUESDAY&includeClosed=true",
                "$.data.entries[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, completed, cancelled, endedBeforeToday, endsNextWeekend);
    }

    @Test
    void grid_byDate_hidesLessonsAfterGroupEndDate() throws Exception {
        // 22.09.2026 — seshanba: 18.09 da tugagan guruh endi chiqmaydi
        assertThat(groupIds(admin, "/api/timetable/grid?date=2026-09-22", "$.data.entries[*].groupId"))
            .containsExactlyInAnyOrder(active, forming);
        assertThat(groupIds(admin, "/api/timetable/grid?date=2026-09-22&dayOfWeek=tuesday",
                "$.data.entries[*].groupId"))
            .containsExactlyInAnyOrder(active, forming);
        // O'tgan sana: 08.09 da 10.09 da tugagan guruh hali ochiq edi
        assertThat(groupIds(admin, "/api/timetable/grid?date=2026-09-08", "$.data.entries[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, endedBeforeToday, endsNextWeekend);
        // Sana va hafta kuni mos emas
        mvc.perform(get("/api/timetable/grid?date=2026-09-22&dayOfWeek=MONDAY").with(as(admin)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void includeClosed_forbiddenForTeacher() throws Exception {
        mvc.perform(get("/api/timetable/grid?dayOfWeek=TUESDAY&includeClosed=true").with(as(teacherUser)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/timetable/by-room?dayOfWeek=TUESDAY&includeClosed=true").with(as(teacherUser)))
            .andExpect(status().isForbidden());
    }

    @Test
    void byRoom_sameRule() throws Exception {
        assertThat(groupIds(admin, "/api/timetable/by-room?dayOfWeek=TUESDAY", "$.lessons[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, endsNextWeekend);
        assertThat(groupIds(admin, "/api/timetable/by-room?date=2026-09-22&includeClosed=true",
                "$.lessons[*].groupId")).hasSize(6);
    }

    @Test
    void listEndpoints_sameRule() throws Exception {
        assertThat(groupIds(admin, "/api/timetable?size=50", "$.data.content[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, endsNextWeekend);
        assertThat(groupIds(admin, "/api/timetable?size=50&includeClosed=true", "$.data.content[*].groupId"))
            .hasSize(6);

        assertThat(groupIds(admin, "/api/timetable/group/" + completed, "$.data[*].groupId")).isEmpty();
        assertThat(groupIds(admin, "/api/timetable/group/" + completed + "?includeClosed=true", "$.data[*].groupId"))
            .containsExactly(completed);

        assertThat(groupIds(admin, "/api/timetable/teacher/" + teacherId, "$.data[*].groupId"))
            .containsExactlyInAnyOrder(active, forming, endsNextWeekend);
        assertThat(groupIds(admin, "/api/timetable/teacher/" + teacherId + "?includeClosed=true", "$.data[*].groupId"))
            .hasSize(6);
    }
}
