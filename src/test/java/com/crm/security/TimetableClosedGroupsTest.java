package com.crm.security;

import com.crm.entity.Timetable;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.entity.Classroom;
import com.crm.repository.ClassroomRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired ClassroomRepository classroomRepository;

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
    // ── To'qnashuv tekshiruvi (assertNoConflicts) — jadval bilan bir xil qoida ──

    private org.springframework.test.web.servlet.ResultActions createLesson(Long groupId, Long teacher, Long roomId,
                                                                            int hour) throws Exception {
        return mvc.perform(post("/api/timetable").with(as(admin))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"groupId\":" + groupId + ",\"teacherId\":" + teacher
                + (roomId != null ? ",\"classroomId\":" + roomId : "")
                + ",\"dayOfWeek\":\"TUESDAY\",\"startTime\":\"" + String.format("%02d:00", hour)
                + "\",\"endTime\":\"" + String.format("%02d:50", hour) + "\"}"));
    }

    @Test
    void conflicts_teacher_ignoreClosedAndEndedGroups() throws Exception {
        Long fresh = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, teacherId);
        // 11:00 — COMPLETED, 12:00 — CANCELLED, 13:00 — 10.09 da tugagan: o'qituvchi band emas
        createLesson(fresh, teacherId, null, 11).andExpect(status().isCreated());
        createLesson(fresh, teacherId, null, 12).andExpect(status().isCreated());
        createLesson(fresh, teacherId, null, 13).andExpect(status().isCreated());
        // 09:00 — ACTIVE, 10:00 — FORMING, 14:00 — 18.09 da tugaydi (hali ochiq): band
        for (int hour : new int[]{9, 10, 14}) {
            createLesson(fresh, teacherId, null, hour)
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                    .value(org.hamcrest.Matchers.containsString("o'qituvchi band")));
        }
    }

    @Test
    void conflicts_room_ignoreClosedGroups() throws Exception {
        Long roomId = inTx(() -> classroomRepository.save(Classroom.builder()
            .roomName("Xona " + System.nanoTime()).roomNumber("R" + (System.nanoTime() % 100_000)).build()).getId());
        Long otherTeacher = fixtures.teacher();
        Long anotherTeacher = fixtures.teacher();
        // Yopiq guruh shu xonada 16:00 da, ochiq guruh 17:00 da
        Long closedGroup = fixtures.group(fixtures.course(500_000), GroupStatus.COMPLETED, otherTeacher);
        createLessonRaw(closedGroup, otherTeacher, roomId, 16);
        createLesson(active, teacherId, roomId, 15).andExpect(status().isCreated());

        Long fresh = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, anotherTeacher);
        createLesson(fresh, anotherTeacher, roomId, 16).andExpect(status().isCreated());
        createLesson(fresh, anotherTeacher, roomId, 15)
            .andExpect(status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("band")));
    }

    /** Tekshiruvsiz (yopiq guruhga dars — tarixiy holat). */
    private void createLessonRaw(Long groupId, Long teacher, Long roomId, int hour) {
        inTx(() -> {
            timetableRepository.save(Timetable.builder()
                .group(groupRepository.findById(groupId).orElseThrow())
                .teacher(teacherRepository.findById(teacher).orElseThrow())
                .classroom(classroomRepository.findById(roomId).orElseThrow())
                .dayOfWeek("TUESDAY").startTime(LocalTime.of(hour, 0)).endTime(LocalTime.of(hour, 50))
                .build());
        });
    }
}
