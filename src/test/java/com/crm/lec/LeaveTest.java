package com.crm.lec;

import com.crm.dto.request.LeaveDecisionRequest;
import com.crm.entity.Holiday;
import com.crm.entity.Payroll;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PayrollStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ConflictException;
import com.crm.repository.HolidayRepository;
import com.crm.repository.PayrollRepository;
import com.crm.service.LeaveService;
import com.crm.service.LeaveStatusJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 4-bosqich: ta'tillar — holatlar, kesishuv, haqli/haqsiz, ruxsatlar, ON_LEAVE job (§1). */
class LeaveTest extends LecItBase {

    @Autowired LeaveService leaveService;
    @Autowired LeaveStatusJob leaveStatusJob;
    @Autowired PayrollRepository payrollRepository;
    @Autowired HolidayRepository holidayRepository;

    private ResultActions submit(User as, String body) throws Exception {
        return mvc.perform(post("/api/leaves").with(as(as)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long submitOk(User as, String body) throws Exception {
        return data(submit(as, body).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private ResultActions decide(User as, long id, String action, String body) throws Exception {
        return mvc.perform(post("/api/leaves/" + id + "/" + action).with(as(as))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void allStaff_submit_approveRequiresPaid_adminCannotApproveSelf_rejectNote() throws Exception {
        User accountant = newUser(UserRole.ACCOUNTANT);
        User admin = newUser(UserRole.ADMIN);
        User sa = newUser(UserRole.SUPER_ADMIN);

        // Buxgalter o'zi uchun (D1: barcha xodimlar); 14–19.09.2026 — 6 Du–Sha kun
        JsonNode created = data(submit(accountant,
                "{\"leaveType\":\"FAMILY\",\"fromDate\":\"2026-09-14\",\"toDate\":\"2026-09-20\",\"reason\":\"To'y\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.userRole").value("ACCOUNTANT"))
            .andExpect(jsonPath("$.data.days").value(7))
            .andExpect(jsonPath("$.data.workdays").value(6))
            .andExpect(jsonPath("$.data.paid").doesNotExist())
            .andReturn());
        long id = created.get("id").asLong();
        assertThat(created.get("userId").asLong()).isEqualTo(accountant.getId());

        decide(admin, id, "approve", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.paidRequired"));
        decide(admin, id, "approve", "{\"paid\":false,\"note\":\"Kelishildi\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"))
            .andExpect(jsonPath("$.data.paid").value(false))
            .andExpect(jsonPath("$.data.decisionNote").value("Kelishildi"))
            .andExpect(jsonPath("$.data.decidedById").value(admin.getId().intValue()));
        decide(admin, id, "reject", "{\"note\":\"Kech\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("leave.notPending"));

        // ADMIN o'zinikini tasdiqlay olmaydi — SA tasdiqlaydi
        long own = submitOk(admin, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-10-05\",\"toDate\":\"2026-10-09\"}");
        decide(admin, own, "approve", "{\"paid\":true}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("leave.selfApprove"));
        decide(sa, own, "approve", "{\"paid\":true}").andExpect(status().isOk());

        // Rad etish: izoh majburiy, reason ga qo'shilmaydi (L-08)
        long third = submitOk(accountant, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-03\",\"reason\":\"Shamollash\"}");
        decide(admin, third, "reject", "{\"note\":\"\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.noteRequired"));
        decide(admin, third, "reject", "{\"note\":\"Hujjat yo'q\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"))
            .andExpect(jsonPath("$.data.reason").value("Shamollash"))
            .andExpect(jsonPath("$.data.decisionNote").value("Hujjat yo'q"));

        // Eski PATCH /status: APPROVED da paid yo'q — 400
        long fourth = submitOk(accountant, "{\"leaveType\":\"OTHER\",\"fromDate\":\"2026-12-01\",\"toDate\":\"2026-12-01\"}");
        mvc.perform(patch("/api/leaves/" + fourth + "/status").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.paidRequired"));
        mvc.perform(patch("/api/leaves/" + fourth + "/status").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"paid\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paid").value(true));
    }

    @Test
    void overlap_dates_andLength_validated() throws Exception {
        User sales = newUser(UserRole.SALES_MANAGER);
        long first = submitOk(sales, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-10-05\",\"toDate\":\"2026-10-10\"}");
        submit(sales, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-10\",\"toDate\":\"2026-10-12\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("leave.overlap"))
            .andExpect(jsonPath("$.data.leaveId").value((int) first));
        submit(sales, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-12\",\"toDate\":\"2026-10-11\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.dates.invalid"));
        submit(sales, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2027-01-01\",\"toDate\":\"2027-03-15\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.tooLong"));
        submit(sales, "{\"leaveType\":\"HOLIDAY\",\"fromDate\":\"2026-11-01\",\"toDate\":\"2026-11-02\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("leave.type.invalid"));

        // Bekor qilingandan keyin o'sha davr bo'sh
        decide(sales, first, "cancel", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        submit(sales, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-10\",\"toDate\":\"2026-10-12\"}")
            .andExpect(status().isCreated());
    }

    @Test
    void parallelApprove_oneWins_otherConflict() throws Exception {
        User teacherUser = newUser(UserRole.SALES_MANAGER);
        User sa1 = newUser(UserRole.SUPER_ADMIN);
        User sa2 = newUser(UserRole.SUPER_ADMIN);
        long id = submitOk(teacherUser, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-10-19\",\"toDate\":\"2026-10-21\"}");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (User u : List.of(sa1, sa2)) {
                results.add(pool.submit(() -> {
                    loginAs(u);
                    start.await();
                    LeaveDecisionRequest r = new LeaveDecisionRequest();
                    r.setPaid(true);
                    try {
                        leaveService.approve(id, r);
                        return "OK";
                    } catch (ConflictException e) {
                        return e.getCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "leave.notPending");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void visibility_teacherSeesOnlyOwn_listIsAdminOnly() throws Exception {
        TeacherUser t1 = newTeacher();
        TeacherUser t2 = newTeacher();
        User admin = newUser(UserRole.ADMIN);
        long mine = submitOk(t1.user(), "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-02\"}");
        long others = submitOk(t2.user(), "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-02\"}");

        mvc.perform(get("/api/leaves/" + mine).with(as(t1.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.teacherId").value(t1.teacherId().intValue()));
        mvc.perform(get("/api/leaves/" + others).with(as(t1.user())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/leaves").with(as(t1.user()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/leaves/my").with(as(t1.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[*].id", hasItem((int) mine)))
            .andExpect(jsonPath("$.data.content[*].id", not(hasItem((int) others))));
        decide(t1.user(), others, "cancel", "{}").andExpect(status().isForbidden());
        decide(t1.user(), mine, "approve", "{\"paid\":true}").andExpect(status().isForbidden());

        // SA/A boshqa xodim nomidan (eski frontend teacherId bilan ham)
        mvc.perform(post("/api/leaves").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherId\":" + t2.teacherId() + ",\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-04\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.userId").value(t2.user().getId().intValue()))
            .andExpect(jsonPath("$.data.requesterId").value(admin.getId().intValue()));
        mvc.perform(get("/api/leaves").param("teacherId", t2.teacherId().toString()).param("status", "PENDING")
                .with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/leaves/pending/count").with(as(admin)))
            .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void cancel_ownerOnlyPending_adminApproved_blockedByApprovedPayroll() throws Exception {
        User sales = newUser(UserRole.SALES_MANAGER);
        User sa = newUser(UserRole.SUPER_ADMIN);
        long id = submitOk(sales, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-09-21\",\"toDate\":\"2026-09-23\"}");
        decide(sa, id, "approve", "{\"paid\":false}").andExpect(status().isOk());
        decide(sales, id, "cancel", "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("leave.notPending"));

        Long payrollId = inTx(() -> payrollRepository.save(Payroll.builder()
            .user(userRepository.findById(sales.getId()).orElseThrow())
            .month(9).year(2026).status(PayrollStatus.APPROVED).build()).getId());
        decide(sa, id, "cancel", "{\"note\":\"Ishga chiqdi\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("leave.payrollLocked"));
        // Haqsiz ta'tilni yopiq oyda tasdiqlash ham 409
        long another = submitOk(sales, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-09-28\",\"toDate\":\"2026-09-29\"}");
        decide(sa, another, "approve", "{\"paid\":false}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("leave.payrollLocked"));

        inTx(() -> {
            Payroll p = payrollRepository.findById(payrollId).orElseThrow();
            p.setStatus(PayrollStatus.CANCELLED);
            payrollRepository.save(p);
        });
        decide(sa, id, "cancel", "{\"note\":\"Ishga chiqdi\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.paid").doesNotExist());
    }

    @Test
    void onLeaveJob_activeToOnLeaveAndBack_inactiveUntouched_affectedLessons() throws Exception {
        TeacherUser t = newTeacher();
        User sa = newUser(UserRole.SUPER_ADMIN);
        Long group = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, t.teacherId());
        schedule(group, "14:00", "15:30", "MONDAY", "WEDNESDAY");

        // Soat: 15.09.2026 (seshanba). Ta'til 14–18.09 — bugunni qamraydi
        long id = submitOk(t.user(), "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-09-14\",\"toDate\":\"2026-09-18\"}");
        JsonNode approved = data(decide(sa, id, "approve", "{\"paid\":true}").andExpect(status().isOk()).andReturn());
        assertThat(approved.get("affectedLessons")).hasSize(2);
        assertThat(approved.get("affectedLessons").get(0).get("lessonDate").asText()).isEqualTo("2026-09-14");
        assertThat(approved.get("affectedLessons").get(1).get("startTime").asText()).isEqualTo("14:00");
        assertThat(teacherStatus(t.teacherId())).isEqualTo(Teacher.STATUS_ON_LEAVE);

        leaveStatusJob.sync(LocalDate.of(2026, 9, 19));
        assertThat(teacherStatus(t.teacherId())).isEqualTo(Teacher.STATUS_ACTIVE);
        leaveStatusJob.sync(LocalDate.of(2026, 9, 16));
        assertThat(teacherStatus(t.teacherId())).isEqualTo(Teacher.STATUS_ON_LEAVE);

        // INACTIVE o'qituvchiga tegilmaydi
        TeacherUser inactive = newTeacher();
        long other = submitOk(inactive.user(), "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-09-21\",\"toDate\":\"2026-09-22\"}");
        decide(sa, other, "approve", "{\"paid\":true}").andExpect(status().isOk());
        inTx(() -> {
            Teacher x = teacherRepository.findById(inactive.teacherId()).orElseThrow();
            x.setStatus(Teacher.STATUS_INACTIVE);
            teacherRepository.save(x);
        });
        leaveStatusJob.sync(LocalDate.of(2026, 9, 21));
        assertThat(teacherStatus(inactive.teacherId())).isEqualTo(Teacher.STATUS_INACTIVE);
        leaveStatusJob.sync(LocalDate.of(2026, 9, 25));
        assertThat(teacherStatus(inactive.teacherId())).isEqualTo(Teacher.STATUS_INACTIVE);
    }

    @Test
    void summary_paidUnpaidWorkdays_holidayNotAWorkday() throws Exception {
        User sales = newUser(UserRole.SALES_MANAGER);
        User sa = newUser(UserRole.SUPER_ADMIN);
        inTx(() -> holidayRepository.save(Holiday.builder().holidayDate(LocalDate.of(2026, 10, 1))
            .name("Bayram").createdAt(LocalDateTime.now()).build()));
        // 28.09–03.10: 6 kalendar kun; Du–Sha 6, bayram 01.10 → 5 ish kuni
        long unpaid = submitOk(sales, "{\"leaveType\":\"FAMILY\",\"fromDate\":\"2026-09-28\",\"toDate\":\"2026-10-03\"}");
        decide(sa, unpaid, "approve", "{\"paid\":false}")
            .andExpect(jsonPath("$.data.workdays").value(5));
        long paid = submitOk(sales, "{\"leaveType\":\"ANNUAL\",\"fromDate\":\"2026-12-28\",\"toDate\":\"2027-01-02\"}");
        decide(sa, paid, "approve", "{\"paid\":true}").andExpect(status().isOk());

        mvc.perform(get("/api/leaves/summary").param("userId", sales.getId().toString()).param("year", "2026")
                .with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unpaidDays").value(6))
            .andExpect(jsonPath("$.data.unpaidWorkdays").value(5))
            .andExpect(jsonPath("$.data.paidDays").value(4))      // 28–31.12 — yil chegarasida kesiladi
            .andExpect(jsonPath("$.data.byType.FAMILY").value(6));
        mvc.perform(get("/api/leaves/summary").param("userId", sa.getId().toString()).param("year", "2026")
                .with(as(sales)))
            .andExpect(status().isForbidden());
    }

    private String teacherStatus(Long id) {
        return inTx(() -> teacherRepository.findById(id).orElseThrow().getStatus());
    }
}
