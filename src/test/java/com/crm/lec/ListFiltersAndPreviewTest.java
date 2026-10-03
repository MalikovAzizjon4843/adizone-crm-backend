package com.crm.lec;

import com.crm.entity.Contract;
import com.crm.entity.ContractTemplate;
import com.crm.entity.Exam;
import com.crm.entity.Holiday;
import com.crm.entity.SalaryRule;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ContractRepository;
import com.crm.repository.ContractTemplateRepository;
import com.crm.repository.ExamRepository;
import com.crm.repository.HolidayRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.SalaryCalculationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ro'yxat filtrlari va ta'til ayirmasi preview'i: {@code GET /api/substitutions/my} (role),
 * {@code GET /api/exams} (ACCOUNTANT, groupId/from/to/status), {@code GET /api/contracts} (q/from/to),
 * {@code GET /api/leaves/{id}/deduction-preview}. Test soati — 2026-09-15.
 */
class ListFiltersAndPreviewTest extends LecItBase {

    @Autowired ExamRepository examRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired ContractTemplateRepository templateRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired SalaryRuleRepository ruleRepository;
    @Autowired SalaryCalculationService calculator;

    // ── GET /api/substitutions/my ────────────────────────────────────────

    private ResultActions assign(User as, Long groupId, String date, Long substitute) throws Exception {
        return mvc.perform(post("/api/substitutions").with(as(as)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"groupId\":" + groupId + ",\"lessonDate\":\"" + date + "\",\"substituteTeacherId\":" + substitute + "}"));
    }

    @Test
    void mySubstitutions_bothRoles_rangeFilter_cancelledHidden_validated() throws Exception {
        TeacherUser main = newTeacher();
        TeacherUser sub = newTeacher();
        User admin = newUser(UserRole.ADMIN);
        Long mainGroup = fixtures.group(fixtures.course(600_000), GroupStatus.ACTIVE, main.teacherId());
        Long subGroup = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, sub.teacherId());
        schedule(mainGroup, "14:00", "15:30", "MONDAY", "WEDNESDAY");
        schedule(subGroup, "16:00", "17:00", "MONDAY");

        assign(admin, subGroup, "2026-09-21", main.teacherId()).andExpect(status().isCreated());   // main — o'rinbosar
        assign(admin, mainGroup, "2026-09-23", sub.teacherId()).andExpect(status().isCreated());   // main — asosiy
        long cancelled = data(assign(admin, mainGroup, "2026-09-28", sub.teacherId())
            .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(post("/api/substitutions/" + cancelled + "/cancel").with(as(admin)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/substitutions/my").param("from", "2026-09-01").param("to", "2026-09-30")
                .with(as(main.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].lessonDate").value("2026-09-21"))
            .andExpect(jsonPath("$.data[0].groupId").value(subGroup.intValue()))
            .andExpect(jsonPath("$.data[0].role").value("SUBSTITUTE"))
            .andExpect(jsonPath("$.data[1].lessonDate").value("2026-09-23"))
            .andExpect(jsonPath("$.data[1].role").value("ORIGINAL"))
            .andExpect(jsonPath("$.data[1].substituteTeacherId").value(sub.teacherId().intValue()));
        // Standart oraliq — bugundan (2026-09-15) 1 yil
        mvc.perform(get("/api/substitutions/my").with(as(sub.user())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].role").value("ORIGINAL"))
            .andExpect(jsonPath("$.data[1].role").value("SUBSTITUTE"));
        mvc.perform(get("/api/substitutions/my").param("from", "2026-09-22").with(as(main.user())))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].role").value("ORIGINAL"));
        mvc.perform(get("/api/substitutions/my").param("from", "2026-09-22").param("to", "2026-09-22")
                .with(as(main.user())))
            .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/substitutions/my").param("from", "2026-09-30").param("to", "2026-09-01")
                .with(as(main.user())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.range.invalid"));
        mvc.perform(get("/api/substitutions/my").param("from", "2026-01-01").param("to", "2027-01-02")
                .with(as(main.user())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("substitution.range.tooLong"));
        mvc.perform(get("/api/substitutions/my").with(as(admin))).andExpect(status().isForbidden());
        // Boshqa ro'yxatlarda role yo'q
        mvc.perform(get("/api/substitutions").with(as(admin)))
            .andExpect(jsonPath("$.data.content[0].role").doesNotExist());
    }

    // ── GET /api/exams ───────────────────────────────────────────────────

    private Long exam(Long groupId, Long teacherId, LocalDate date, boolean active) {
        return inTx(() -> examRepository.save(Exam.builder()
            .examName("Imtihon " + date)
            .group(groupId != null ? groupRepository.findById(groupId).orElseThrow() : null)
            .teacher(teacherId != null ? teacherRepository.findById(teacherId).orElseThrow() : null)
            .examDate(date)
            .totalMarks(BigDecimal.valueOf(100))
            .passMarks(BigDecimal.valueOf(60))
            .isActive(active)
            .build()).getId());
    }

    private ResultActions exams(User as, String... params) throws Exception {
        var req = get("/api/exams").with(as(as));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void exams_accountantReadOnly_filtersGroupDatesStatus_teacherScope() throws Exception {
        TeacherUser t1 = newTeacher();
        TeacherUser t2 = newTeacher();
        User acc = newUser(UserRole.ACCOUNTANT);
        Long groupA = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t1.teacherId());
        Long groupB = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, t2.teacherId());
        Long past = exam(groupA, null, LocalDate.of(2026, 9, 10), true);
        exam(groupA, null, LocalDate.of(2026, 9, 15), true);            // bugun — UPCOMING
        exam(groupB, null, LocalDate.of(2026, 10, 5), true);
        exam(groupA, null, null, true);                                  // sanasiz — UPCOMING
        Long deleted = exam(groupA, null, LocalDate.of(2026, 9, 20), false);
        exam(null, t1.teacherId(), LocalDate.of(2026, 9, 25), true);     // guruhsiz, o'qituvchiga biriktirilgan

        exams(acc).andExpect(jsonPath("$.data.totalElements").value(5));
        exams(acc, "groupId", groupA.toString()).andExpect(jsonPath("$.data.totalElements").value(3));
        exams(acc, "status", "upcoming").andExpect(jsonPath("$.data.totalElements").value(4));
        exams(acc, "status", "PAST")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(past.intValue()));
        exams(acc, "status", "INACTIVE")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(deleted.intValue()));
        exams(acc, "from", "2026-09-12", "to", "2026-09-30").andExpect(jsonPath("$.data.totalElements").value(2));
        exams(acc, "groupId", groupA.toString(), "from", "2026-09-12", "status", "UPCOMING")
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/exams").param("status", "OPEN").with(as(acc)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.status.invalid"));
        mvc.perform(get("/api/exams").param("from", "2026-10-01").param("to", "2026-09-01").with(as(acc)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.dates.invalid"));

        // ACCOUNTANT — faqat o'qish
        mvc.perform(get("/api/exams/" + past).with(as(acc)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.groupId").value(groupA.intValue()));
        mvc.perform(get("/api/exams/" + past + "/registrations").with(as(acc))).andExpect(status().isOk());
        mvc.perform(get("/api/exams/" + past + "/eligible-students").with(as(acc)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
        mvc.perform(post("/api/exams").with(as(acc)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"examName\":\"X\",\"groupId\":" + groupA + "}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/exams/" + past).with(as(acc)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"examName\":\"X\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/exams/" + past).with(as(acc))).andExpect(status().isForbidden());
        mvc.perform(get("/api/exams/" + past + "/results").with(as(acc))).andExpect(status().isForbidden());

        // TEACHER — o'z guruhi orqali va o'ziga biriktirilgan (guruhsiz) imtihonlar
        exams(t1.user()).andExpect(jsonPath("$.data.totalElements").value(4));
        exams(t1.user(), "groupId", groupB.toString()).andExpect(jsonPath("$.data.totalElements").value(0));
        exams(t2.user()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // ── GET /api/contracts ───────────────────────────────────────────────

    private Long contract(Long templateId, Long studentId, String number, LocalDate date) {
        return inTx(() -> {
            Contract c = new Contract();
            c.setContractNumber(number);
            c.setStudent(studentRepository.findById(studentId).orElseThrow());
            c.setTemplate(templateRepository.findById(templateId).orElseThrow());
            c.setType(ContractType.OFFLINE);
            c.setStatus(ContractStatus.DRAFT);
            c.setContractDate(date);
            c.setRenderedContent("<p>Shartnoma</p>");
            return contractRepository.save(c).getId();
        });
    }

    private ResultActions contracts(User as, String... params) throws Exception {
        var req = get("/api/contracts").with(as(as));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andExpect(status().isOk());
    }

    @Test
    void contracts_searchByNamePhoneNumber_likeCharsLiteral_dateRange() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        Long template = inTx(() -> {
            ContractTemplate t = new ContractTemplate();
            t.setTitle("T");
            t.setType(ContractType.OFFLINE);
            t.setContent("<p>{{student.fullName}}</p>");
            return templateRepository.save(t).getId();
        });
        Long nodira = inTx(() -> studentRepository.save(Student.builder()
            .firstName("Nodira").lastName("Karimova")
            .phone("+998901112233").parentPhone("+998935554433")
            .status(StudentStatus.ACTIVE)
            .build()).getId());
        Long ali = fixtures.student();                                     // "Ali<n> Valiyev"
        Long c1 = contract(template, nodira, "CTR-2026-00101", LocalDate.of(2026, 9, 1));
        Long c2 = contract(template, ali, "CTR-2026-00102", LocalDate.of(2026, 9, 10));
        Long c3 = contract(template, ali, "OLD_5", LocalDate.of(2026, 8, 20));

        contracts(admin).andExpect(jsonPath("$.data.totalElements").value(3));
        contracts(admin, "q", "nodira")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(c1.intValue()));
        contracts(admin, "q", "  Nodira Karimova ").andExpect(jsonPath("$.data.totalElements").value(1));
        contracts(admin, "q", "1112233").andExpect(jsonPath("$.data.content[0].id").value(c1.intValue()));
        contracts(admin, "q", "5554433").andExpect(jsonPath("$.data.content[0].id").value(c1.intValue()));
        contracts(admin, "q", "00102")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(c2.intValue()));
        contracts(admin, "q", "valiyev").andExpect(jsonPath("$.data.totalElements").value(2));
        // % va _ — oddiy belgi
        contracts(admin, "q", "_")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(c3.intValue()));
        contracts(admin, "q", "%").andExpect(jsonPath("$.data.totalElements").value(0));
        contracts(admin, "q", "CTR_2026").andExpect(jsonPath("$.data.totalElements").value(0));

        contracts(admin, "from", "2026-09-01", "to", "2026-09-10").andExpect(jsonPath("$.data.totalElements").value(2));
        contracts(admin, "from", "2026-09-05").andExpect(jsonPath("$.data.content[0].id").value(c2.intValue()));
        contracts(admin, "to", "2026-08-31")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(c3.intValue()));
        contracts(admin, "q", "valiyev", "from", "2026-09-01", "status", "DRAFT")
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(c2.intValue()));
        mvc.perform(get("/api/contracts").param("from", "2026-09-10").param("to", "2026-09-01").with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.dates.invalid"));
    }

    // ── GET /api/leaves/{id}/deduction-preview ───────────────────────────

    private void fixedRule(User u, long fixed) {
        inTx(() -> ruleRepository.save(SalaryRule.builder()
            .role(u.getRole())
            .user(userRepository.findById(u.getId()).orElseThrow())
            .fixedSalary(BigDecimal.valueOf(fixed))
            .perPayingStudent(BigDecimal.ZERO)
            .perNewStudent(BigDecimal.ZERO)
            .kpiBonus(BigDecimal.ZERO)
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .isActive(true)
            .build()));
    }

    private long submit(User as, String body) throws Exception {
        return data(mvc.perform(post("/api/leaves").with(as(as)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    @Test
    void deductionPreview_pendingLeave_splitByMonth_holidayExcluded_matchesPayroll() throws Exception {
        User sales = newUser(UserRole.SALES_MANAGER);
        User sa = newUser(UserRole.SUPER_ADMIN);
        User admin = newUser(UserRole.ADMIN);
        fixedRule(sales, 2_700_000);
        inTx(() -> holidayRepository.save(Holiday.builder().holidayDate(LocalDate.of(2026, 9, 29))
            .name("Bayram").createdAt(LocalDateTime.now()).build()));
        // 28.09–05.10: sentyabr — 28, 30 (29 bayram) → 2 / 25; oktyabr — 1, 2, 3, 5 (4 yakshanba) → 4 / 27
        long leave = submit(sales, "{\"leaveType\":\"FAMILY\",\"fromDate\":\"2026-09-28\",\"toDate\":\"2026-10-05\"}");

        JsonNode rows = data(mvc.perform(get("/api/leaves/" + leave + "/deduction-preview").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].month").value(9))
            .andExpect(jsonPath("$.data[0].year").value(2026))
            .andExpect(jsonPath("$.data[0].workDays").value(25))
            .andExpect(jsonPath("$.data[0].unpaidDays").value(2))
            .andExpect(jsonPath("$.data[1].month").value(10))
            .andExpect(jsonPath("$.data[1].workDays").value(27))
            .andExpect(jsonPath("$.data[1].unpaidDays").value(4))
            .andReturn());
        assertThat(rows.get(0).get("fixedSalary").decimalValue()).isEqualByComparingTo("2700000");
        assertThat(rows.get(0).get("amount").decimalValue()).isEqualByComparingTo("216000");   // 2 700 000 × 2 / 25
        assertThat(rows.get(1).get("amount").decimalValue()).isEqualByComparingTo("400000");   // 2 700 000 × 4 / 27
        assertThat(jdbc.queryForObject("SELECT status FROM leave_requests WHERE id = ?", String.class, leave))
            .isEqualTo("PENDING");

        // Haqsiz tasdiqlangandan keyin payroll aynan shu summani ayiradi
        mvc.perform(post("/api/leaves/" + leave + "/approve").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paid\":false}"))
            .andExpect(status().isOk());
        assertThat(calculator.calculateForUser(sales.getId(), 9, 2026).getLeaveDeduction())
            .isEqualByComparingTo(rows.get(0).get("amount").decimalValue());
        assertThat(calculator.calculateForUser(sales.getId(), 10, 2026).getLeaveDeduction())
            .isEqualByComparingTo(rows.get(1).get("amount").decimalValue());

        // Ruxsat: faqat SA/A
        mvc.perform(get("/api/leaves/" + leave + "/deduction-preview").with(as(sa))).andExpect(status().isOk());
        mvc.perform(get("/api/leaves/" + leave + "/deduction-preview").with(as(sales))).andExpect(status().isForbidden());
        mvc.perform(get("/api/leaves/" + leave + "/deduction-preview").with(as(newUser(UserRole.ACCOUNTANT))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/leaves/999999/deduction-preview").with(as(sa))).andExpect(status().isNotFound());
    }

    @Test
    void deductionPreview_noSalaryRule_amountNull_wholeMonthCappedAtFixed() throws Exception {
        User acc = newUser(UserRole.ACCOUNTANT);   // oylik hisoblanmaydigan rol
        User sales = newUser(UserRole.SALES_MANAGER);
        User sa = newUser(UserRole.SUPER_ADMIN);
        fixedRule(sales, 2_600_000);

        long accLeave = submit(acc, "{\"leaveType\":\"SICK\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-04\"}");
        mvc.perform(get("/api/leaves/" + accLeave + "/deduction-preview").with(as(sa)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].unpaidDays").value(3))
            .andExpect(jsonPath("$.data[0].fixedSalary").doesNotExist())
            .andExpect(jsonPath("$.data[0].amount").doesNotExist());

        // Butun fevral (24 ish kuni) — aynan fixed
        long whole = submit(sales, "{\"leaveType\":\"OTHER\",\"fromDate\":\"2027-02-01\",\"toDate\":\"2027-02-28\"}");
        JsonNode rows = data(mvc.perform(get("/api/leaves/" + whole + "/deduction-preview").with(as(sa)))
            .andExpect(jsonPath("$.data[0].workDays").value(24))
            .andExpect(jsonPath("$.data[0].unpaidDays").value(24))
            .andReturn());
        assertThat(rows.get(0).get("amount").decimalValue()).isEqualByComparingTo("2600000");
    }
}
