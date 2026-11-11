package com.crm.dashboard;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dashboard.DirectorDtos.LessonRow;
import com.crm.entity.Attendance;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Davomat intizomi" dars qatori: belgilangan/kutilgan bilan birga kelgan / kelmagan / sababli. */
class LessonRowCountsTest extends AbstractBillingIT {

    @Autowired AttendanceMetricsService attendance;
    @Autowired AttendanceRepository attendanceRepo;
    @Autowired StudentRepository studentRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;

    private void mark(Long student, Long group, String date, AttendanceStatus st) {
        inTx(() -> attendanceRepo.save(Attendance.builder()
            .student(studentRepo.findById(student).orElseThrow()).group(groupRepo.findById(group).orElseThrow())
            .attendanceDate(d(date)).status(st).build()));
    }

    @Test
    void lessonRow_presentAbsentExcused() throws Exception {
        Long teacher = fixtures.teacher();
        Long g = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher);
        inTx(() -> scheduleRepo.save(GroupScheduleDay.builder().group(groupRepo.findById(g).orElseThrow())
            .dayOfWeek("MONDAY").startTime("10:00").endTime("12:00").build()));
        AttendanceStatus[] marks = {AttendanceStatus.PRESENT, AttendanceStatus.LATE, AttendanceStatus.PRESENT,
            AttendanceStatus.ABSENT, AttendanceStatus.EXCUSED};
        for (AttendanceStatus st : marks) {
            Long s = fixtures.student();
            fixtures.enrollment(s, g).start(d("01.09.2026")).save();
            mark(s, g, "05.10.2026", st);
        }
        fixtures.enrollment(fixtures.student(), g).start(d("01.09.2026")).save();   // belgilanmagan
        clock.setDateTime(LocalDateTime.of(2026, 10, 6, 9, 0));

        List<LessonRow> rows = attendance.rows(DashboardPeriod.of("CUSTOM", null, d("05.10.2026"), d("05.10.2026"),
            LocalDateTime.now(clock)), teacher, "PLANNED");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.activeStudents()).isEqualTo(6);
            assertThat(r.markedCount()).isEqualTo(5);
            assertThat(r.present()).isEqualTo(3);           // PRESENT + LATE
            assertThat(r.absent()).isEqualTo(1);
            assertThat(r.excused()).isEqualTo(1);
        });

        mvc.perform(get("/api/dashboard/director/attendance/lessons")
                .param("period", "CUSTOM").param("from", "2026-10-05").param("to", "2026-10-05")
                .param("teacherId", String.valueOf(teacher))
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].markedCount").value(5))
            .andExpect(jsonPath("$.data.items[0].present").value(3))
            .andExpect(jsonPath("$.data.items[0].absent").value(1))
            .andExpect(jsonPath("$.data.items[0].excused").value(1));
    }
}
