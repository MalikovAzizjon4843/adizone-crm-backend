package com.crm.lec;

import com.crm.entity.Attendance;
import com.crm.entity.Exam;
import com.crm.entity.ExamResult;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.ExamRepository;
import com.crm.repository.ExamResultRepository;
import com.crm.repository.StudentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 3-bosqich: imtihon to'lovi (fee), kassa INCOME/REVERSAL, Idempotency-Key, OVERDUE (§4). */
class ExamFeeTest extends LecItBase {

    @Autowired ExamRepository examRepository;
    @Autowired ExamResultRepository examResultRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired CashRegisterRepository cashRegisterRepository;

    private TeacherUser teacher;
    private Long groupId;
    private Long register;
    private User accountant;
    private User admin;
    private User superAdmin;

    @BeforeEach
    void setUp() {
        teacher = newTeacher();
        groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacher.teacherId());
        register = fixtures.cashRegister(false);
        accountant = newUser(UserRole.ACCOUNTANT);
        admin = newUser(UserRole.ADMIN);
        superAdmin = newUser(UserRole.SUPER_ADMIN);
    }

    private Long exam(long fee, LocalDate date) {
        return inTx(() -> examRepository.save(Exam.builder()
            .examName("Oraliq imtihon")
            .group(groupRepository.findById(groupId).orElseThrow())
            .teacher(teacherRepository.findById(teacher.teacherId()).orElseThrow())
            .examDate(date)
            .fee(BigDecimal.valueOf(fee))
            .totalMarks(BigDecimal.valueOf(100))
            .passMarks(BigDecimal.valueOf(60))
            .isActive(true)
            .build()).getId());
    }

    /** Guruhdagi, {@code presentDays} marta kelgan o'quvchi: [studentId, sgId]. */
    private Long[] student(int presentDays) {
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, groupId).start(LocalDate.of(2026, 9, 1)).save();
        inTx(() -> {
            for (int i = 0; i < presentDays; i++) {
                attendanceRepository.save(Attendance.builder()
                    .student(studentRepository.findById(student).orElseThrow())
                    .group(groupRepository.findById(groupId).orElseThrow())
                    .attendanceDate(LocalDate.of(2026, 9, 1).plusDays(i))
                    .status(AttendanceStatus.PRESENT)
                    .build());
            }
        });
        return new Long[]{student, sg};
    }

    private ResultActions registerAs(User u, Long examId, String body, String key) throws Exception {
        var req = post("/api/exams/" + examId + "/registrations").with(as(u))
            .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private String paidBody(Long student) {
        return "{\"studentId\":" + student + ",\"cashRegisterId\":" + register + ",\"paymentMethod\":\"CASH\"}";
    }

    private BigDecimal cashBalance() {
        return inTx(() -> cashRegisterRepository.findById(register).orElseThrow().getCashBalance());
    }

    @Test
    void paidExam_registration_writesCashIncome_notStudentLedger() throws Exception {
        Long examId = exam(150_000, LocalDate.of(2026, 9, 20));
        Long[] s = student(8);

        JsonNode reg = data(registerAs(accountant, examId, paidBody(s[0]), null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("REGISTERED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
            .andExpect(jsonPath("$.data.amountDue").value(150000))
            .andExpect(jsonPath("$.data.amountPaid").value(150000))
            .andReturn());
        assertThat(reg.get("receiptNumber").asText()).startsWith("RCP-");
        long txId = reg.get("cashTransactionId").asLong();
        assertThat(jdbc.queryForObject("SELECT exam_registration_id FROM cash_transactions WHERE id = ?", Long.class, txId))
            .isEqualTo(reg.get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT type FROM cash_transactions WHERE id = ?", String.class, txId))
            .isEqualTo("INCOME");
        assertThat(cashBalance()).isEqualByComparingTo("150000");
        // Billing-v2 ledger va yozilma balansi o'zgarmaydi — imtihon to'lovi qarzni yopmaydi
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM balance_transactions WHERE student_id = ?", Integer.class, s[0]))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM student_groups WHERE id = ?", BigDecimal.class, s[1]))
            .isEqualByComparingTo("0");

        // Moliya hisoboti: alohida qator
        mvc.perform(get("/api/finance/report").param("from", "2026-09-01").param("to", "2026-09-30").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.examFees").value(150000));

        // Dublikat; ro'yxat
        registerAs(accountant, examId, paidBody(s[0]), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.alreadyRegistered"));
        mvc.perform(get("/api/exams/" + examId + "/registrations").with(as(accountant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].receiptNumber").value(reg.get("receiptNumber").asText()));
    }

    @Test
    void freeExam_noCash_teacherAllowed_paidRequiresCashAndStaffRole() throws Exception {
        Long free = exam(0, LocalDate.of(2026, 9, 20));
        Long paid = exam(100_000, LocalDate.of(2026, 9, 21));
        Long[] s = student(8);

        registerAs(teacher.user(), free, "{\"studentId\":" + s[0] + "}", null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.paymentStatus").value("FREE"))
            .andExpect(jsonPath("$.data.cashTransactionId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions", Integer.class)).isZero();

        registerAs(teacher.user(), paid, paidBody(s[0]), null)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("exam.paymentRole"));
        registerAs(admin, paid, "{\"studentId\":" + s[0] + "}", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.paymentRequired"));
        // Eski endpoint pullik imtihonda ishlamaydi
        mvc.perform(post("/api/exams/" + paid + "/register-student").param("studentId", s[0].toString()).with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.paymentRequired"));
        // Eski preview — endi imtihon narxi
        mvc.perform(post("/api/exams/" + paid + "/calculate-payment").param("studentId", s[0].toString()).with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.amountDue").value(100000));
    }

    @Test
    void eligibility_reasons_overdueAttendance_closedExam() throws Exception {
        Long examId = exam(50_000, LocalDate.of(2026, 9, 20));
        Long[] overdue = student(8);
        jdbc.update("UPDATE student_groups SET balance = -700000, debt_since = ? WHERE id = ?",
            LocalDate.of(2026, 8, 1), overdue[1]);
        Long[] few = student(7);

        registerAs(accountant, examId, paidBody(overdue[0]), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.notEligible"))
            .andExpect(jsonPath("$.data.reason").value("OVERDUE"));
        registerAs(accountant, examId, paidBody(few[0]), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data.reason").value("ATTENDANCE"));
        assertThat(cashBalance()).isEqualByComparingTo("0");

        Long past = exam(50_000, LocalDate.of(2026, 9, 10));
        registerAs(accountant, past, paidBody(student(8)[0]), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.closed"));
    }

    @Test
    void idempotencyKey_secondClickReplays_noSecondIncome() throws Exception {
        Long examId = exam(120_000, LocalDate.of(2026, 9, 20));
        Long[] s = student(8);
        Long[] other = student(8);

        long id = data(registerAs(accountant, examId, paidBody(s[0]), "dlg-1").andExpect(status().isCreated()).andReturn())
            .get("id").asLong();
        registerAs(accountant, examId, paidBody(s[0]), "dlg-1")
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_transactions WHERE exam_registration_id = ?",
            Integer.class, id)).isEqualTo(1);
        assertThat(cashBalance()).isEqualByComparingTo("120000");

        registerAs(accountant, examId, paidBody(other[0]), "dlg-1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.idempotency.mismatch"));
    }

    @Test
    void cancel_paid_writesReversal_refunded_thenReRegister() throws Exception {
        Long examId = exam(200_000, LocalDate.of(2026, 9, 20));
        Long[] s = student(8);
        long regId = data(registerAs(accountant, examId, paidBody(s[0]), null).andReturn()).get("id").asLong();
        String cancelUrl = "/api/exams/" + examId + "/registrations/" + regId + "/cancel";

        mvc.perform(post(cancelUrl).with(as(teacher.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Kasal\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(cancelUrl).with(as(superAdmin)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("exam.registration.reasonRequired"));
        // 2026-10-05: to'langan yozilishni bekor qilish = to'lovni bekor qilish — faqat SA
        for (User notSa : new User[]{accountant, admin}) {
            mvc.perform(post(cancelUrl).with(as(notSa)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Imtihonga kela olmaydi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("payment.edit.superAdminOnly"));
        }

        // Kassada pul qolmagan — REVERSAL baribir yoziladi, ogohlantirish bilan
        jdbc.update("UPDATE cash_registers SET cash_balance = 0, balance = 0 WHERE id = ?", register);
        JsonNode cancelled = data(mvc.perform(post(cancelUrl).with(as(superAdmin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Imtihonga kela olmaydi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"))
            .andExpect(jsonPath("$.data.negativeCashBalance").value(true))
            .andReturn());
        long refund = cancelled.get("refundCashTransactionId").asLong();
        assertThat(jdbc.queryForObject("SELECT type FROM cash_transactions WHERE id = ?", String.class, refund))
            .isEqualTo("REVERSAL");
        assertThat(jdbc.queryForObject("SELECT related_tx_id FROM cash_transactions WHERE id = ?", Long.class, refund))
            .isEqualTo(cancelled.get("cashTransactionId").asLong());
        assertThat(jdbc.queryForObject("SELECT exam_registration_id FROM cash_transactions WHERE id = ?", Long.class, refund))
            .isEqualTo(regId);
        assertThat(cashBalance()).isEqualByComparingTo("-200000");

        mvc.perform(post(cancelUrl).with(as(accountant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Yana bir bor\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.registration.notActive"));

        // CANCELLED dan keyin qayta yozilish — yangi yozuv
        long again = data(registerAs(accountant, examId, paidBody(s[0]), null).andExpect(status().isCreated()).andReturn())
            .get("id").asLong();
        assertThat(again).isNotEqualTo(regId);

        // Natija qo'yilgan — bekor qilinmaydi
        inTx(() -> examResultRepository.save(ExamResult.builder()
            .exam(examRepository.findById(examId).orElseThrow())
            .student(studentRepository.findById(s[0]).orElseThrow())
            .marksObtained(BigDecimal.valueOf(70)).build()));
        mvc.perform(post("/api/exams/" + examId + "/registrations/" + again + "/cancel").with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Xato\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.registration.hasResult"));
    }

    @Test
    void feeLocked_andDeleteBlockedWhilePaidRegistrations() throws Exception {
        Long examId = exam(90_000, LocalDate.of(2026, 9, 20));
        long regId = data(registerAs(accountant, examId, paidBody(student(8)[0]), null).andReturn()).get("id").asLong();

        mvc.perform(put("/api/exams/" + examId).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"examName\":\"Oraliq imtihon\",\"groupId\":" + groupId + ",\"examDate\":\"2026-09-20\",\"fee\":95000}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.feeLocked"));
        mvc.perform(put("/api/exams/" + examId).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"examName\":\"Yangi nom\",\"groupId\":" + groupId + ",\"examDate\":\"2026-09-20\",\"fee\":90000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fee").value(90000));
        mvc.perform(delete("/api/exams/" + examId).with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("exam.hasRegistrations"));

        mvc.perform(post("/api/exams/" + examId + "/registrations/" + regId + "/cancel").with(as(superAdmin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Imtihon ko'chdi\"}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/exams/" + examId).with(as(admin))).andExpect(status().isOk());

        // Yangi pullik guruh imtihoni — avtomatik (to'lovsiz) yozilish yo'q
        student(8);
        String created = mvc.perform(post("/api/exams").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"examName\":\"Final\",\"groupId\":" + groupId + ",\"examDate\":\"2026-09-25\",\"fee\":50000}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.fee").value(50000))
            .andReturn().getResponse().getContentAsString();
        long finalId = objectMapper.readTree(created).get("data").get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_registrations WHERE exam_id = ?", Integer.class, finalId))
            .isZero();
    }
}
