package com.crm.dashboard;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.entity.DirectorDailyStat;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.LessonException;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.DirectorDailyStatRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.GroupScheduleService;
import com.crm.service.LeadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Direktor dashboardi, 4-bosqich: API, rollar (§7 #15), kesh, snapshot manbai, kalendar. */
class DirectorDashboardApiTest extends AbstractBillingIT {

    @Autowired LeadService leadService;
    @Autowired DirectorDashboardService dashboard;
    @Autowired DirectorDailyStatRepository statRepo;
    @Autowired UserRepository userRepo;
    @Autowired TeacherRepository teacherRepo;
    @Autowired GroupRepository groupRepo;
    @Autowired GroupScheduleDayRepository scheduleRepo;
    @Autowired LessonExceptionRepository exceptionRepo;
    @Autowired GroupScheduleService scheduleService;
    @Autowired JdbcTemplate jdbc;

    private void leadAt(LocalDateTime at) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        clock.setDateTime(at);
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName("Lid");
        r.setPhone("+998900000001");
        Long id = leadService.createLeadByStaff(r).getId();
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", at, id);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void superAdmin_seesAllSections_withHeadline() throws Exception {
        dashboard.invalidate();
        leadAt(d("01.10.2026").atTime(10, 0));
        clock.setDateTime(d("01.10.2026").atTime(20, 0));

        mvc.perform(get("/api/dashboard/director").param("date", "2026-10-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.meta.period").value("DAY"))
            .andExpect(jsonPath("$.data.meta.timezone").value("Asia/Tashkent"))
            .andExpect(jsonPath("$.data.meta.hiddenSections").isEmpty())
            .andExpect(jsonPath("$.data.funnel.activity.leadsCreated").value(1))
            .andExpect(jsonPath("$.data.collections.due.count").value(0))
            .andExpect(jsonPath("$.data.debtors.count").value(0))
            .andExpect(jsonPath("$.data.attendance.planned").value(0))
            .andExpect(jsonPath("$.data.trials.cohort").value(0))
            .andExpect(jsonPath("$.data.operators").exists())
            .andExpect(jsonPath("$.data.headline", hasItem("1 lid → 0 tashrif → 0 birinchi to'lov")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_operatorSections_only_financeHidden() throws Exception {
        dashboard.invalidate();
        mvc.perform(get("/api/dashboard/director"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.funnel").exists())
            .andExpect(jsonPath("$.data.collections").doesNotExist())
            .andExpect(jsonPath("$.data.debtors").doesNotExist())
            .andExpect(jsonPath("$.data.meta.hiddenSections", contains("collections", "debtors")));
        mvc.perform(get("/api/dashboard/director/collections/periods"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("dashboard.section.forbidden"));
        mvc.perform(get("/api/dashboard/director/funnel/leads").param("step", "CREATED"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void accountant_financeOnly() throws Exception {
        dashboard.invalidate();
        mvc.perform(get("/api/dashboard/director"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.collections").exists())
            .andExpect(jsonPath("$.data.debtors").exists())
            .andExpect(jsonPath("$.data.funnel").doesNotExist())
            .andExpect(jsonPath("$.data.operators").doesNotExist())
            .andExpect(jsonPath("$.data.meta.hiddenSections", contains("funnel", "attendance", "trials", "operators")));
        mvc.perform(get("/api/dashboard/director/operators")).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/director/debtors")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "TEACHER")
    void teacher_forbidden() throws Exception {
        mvc.perform(get("/api/dashboard/director")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void pastDay_readsFinalSnapshot_andInvalidPeriod400() throws Exception {
        dashboard.invalidate();
        clock.setDate(d("10.10.2026"));
        inTx(() -> statRepo.save(DirectorDailyStat.builder().statDate(d("05.10.2026")).section("collections")
            .payload("{\"due\":{\"count\":28,\"amount\":19600000},\"collected\":{\"count\":21,\"amount\":14700000},"
                + "\"onTime\":{\"count\":18,\"amount\":0},\"late\":{\"count\":3,\"amount\":0},"
                + "\"pending\":{\"count\":5,\"amount\":0},\"unpaid\":{\"count\":2,\"amount\":0},"
                + "\"collectionRate\":75.0,\"collectedOnDay\":{\"count\":4,\"amount\":0},\"cashIn\":0,\"estimated\":false}")
            .computedAt(d("05.10.2026").atTime(23, 55)).finalized(true).version(1).build()));

        mvc.perform(get("/api/dashboard/director").param("date", "2026-10-05"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.collections.due.count").value(28))
            .andExpect(jsonPath("$.data.collections.collected.count").value(21))
            .andExpect(jsonPath("$.data.meta.source").value("MIXED"))
            .andExpect(jsonPath("$.data.headline", hasItem(
                "28 muddati kelgan → 21 undirildi (o'z vaqtida 18, kechikib 3), 5 kutilmoqda")));
        mvc.perform(get("/api/dashboard/director/trend").param("metric", "collections.due.count")
                .param("from", "2026-10-01").param("to", "2026-10-10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].value").value(28))
            .andExpect(jsonPath("$.data[0].finalized").value(true));
        mvc.perform(get("/api/dashboard/director").param("period", "CUSTOM")
                .param("from", "2026-10-10").param("to", "2026-10-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("dashboard.period.invalid"));
    }

    @Test
    void summary_isCachedFor60s_untilInvalidated() {
        dashboard.invalidate();
        leadAt(d("01.10.2026").atTime(10, 0));
        clock.setDateTime(d("01.10.2026").atTime(20, 0));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        DirectorDashboardService.Params params = new DirectorDashboardService.Params("DAY", d("01.10.2026"),
            null, null, null, null, false);
        assertThat(dashboard.summary(params).funnel().activity().leadsCreated()).isEqualTo(1);

        leadAt(d("01.10.2026").atTime(11, 0));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        clock.setDateTime(d("01.10.2026").atTime(20, 0));
        assertThat(dashboard.summary(params).funnel().activity().leadsCreated()).as("keshdan").isEqualTo(1);
        dashboard.invalidate();
        assertThat(dashboard.summary(params).funnel().activity().leadsCreated()).isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void drillDown_paginated() throws Exception {
        for (int h = 9; h < 12; h++) {
            leadAt(d("01.10.2026").atTime(h, 0));
        }
        clock.setDateTime(d("02.10.2026").atTime(9, 0));
        mvc.perform(get("/api/dashboard/director/funnel/leads").param("step", "CREATED")
                .param("date", "2026-10-01").param("size", "2").param("page", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.page").value(1));
        mvc.perform(get("/api/dashboard/director/funnel/leads").param("step", "NOPE"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("dashboard.param.invalid"));
    }

    // ── Kalendar CRUD ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void holidays_crud_andDuplicate409() throws Exception {
        String body = "{\"date\":\"2026-10-05\",\"name\":\"Ustozlar kuni\"}";
        mvc.perform(post("/api/holidays").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/holidays").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("holiday.exists"));
        mvc.perform(get("/api/holidays").param("from", "2026-10-01").param("to", "2026-10-31"))
            .andExpect(jsonPath("$.data[0].name").value("Ustozlar kuni"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/holidays/2026-10-05"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "TEACHER")
    void teacher_cannotManageHolidays() throws Exception {
        mvc.perform(post("/api/holidays").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-05\",\"name\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void lessonExceptions_teacherOwnGroupOnly_andHasLessonOn() {
        Long course = fixtures.course(700_000);
        Long teacherId = fixtures.teacher();
        Long own = fixtures.group(course, com.crm.entity.enums.GroupStatus.ACTIVE, teacherId);
        Long other = fixtures.group(course);
        inTx(() -> scheduleRepo.save(GroupScheduleDay.builder().group(groupRepo.findById(own).orElseThrow())
            .dayOfWeek("MONDAY").startTime("10:00").endTime("12:00").build()));
        fixtures.loginAs(UserRole.TEACHER);
        User teacherUser = userRepo.findByUsername("test-teacher").orElseThrow();
        inTx(() -> {
            Teacher t = teacherRepo.findById(teacherId).orElseThrow();
            t.setUser(userRepo.findById(teacherUser.getId()).orElseThrow());
            teacherRepo.save(t);
        });
        LessonCalendarService calendar = applicationContext.getBean(LessonCalendarService.class);

        calendar.addException(own, new LessonCalendarService.ExceptionRequest(d("05.10.2026"),
            LessonException.Kind.CANCELLED, null, "Kasal"));
        assertThat(scheduleService.hasLessonOn(own, d("05.10.2026"))).isFalse();
        assertThat(scheduleService.hasLessonOn(own, d("12.10.2026"))).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> calendar.addException(other,
                new LessonCalendarService.ExceptionRequest(d("05.10.2026"), LessonException.Kind.CANCELLED, null, null)))
            .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> calendar.addException(own,
                new LessonCalendarService.ExceptionRequest(d("06.10.2026"), LessonException.Kind.MOVED, null, null)))
            .isInstanceOf(com.crm.exception.CodedException.class)
            .extracting(e -> ((com.crm.exception.CodedException) e).getCode())
            .isEqualTo("lessonException.movedToRequired");
        calendar.addException(own, new LessonCalendarService.ExceptionRequest(d("12.10.2026"),
            LessonException.Kind.MOVED, d("13.10.2026"), null));
        assertThat(scheduleService.hasLessonOn(own, d("12.10.2026"))).isFalse();
        assertThat(scheduleService.hasLessonOn(own, d("13.10.2026"))).isTrue();
        assertThat(inTx(() -> exceptionRepo.findByGroupIdOrderByLessonDateDesc(own))).hasSize(2);
    }

    @Autowired
    org.springframework.context.ApplicationContext applicationContext;
}
