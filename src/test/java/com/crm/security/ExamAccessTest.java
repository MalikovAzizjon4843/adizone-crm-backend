package com.crm.security;

import com.crm.entity.Attendance;
import com.crm.entity.Exam;
import com.crm.entity.ExamResult;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.ExamRepository;
import com.crm.repository.ExamResultRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E-01, E-02, E-03 (P0): imtihonga kirish sharti, o'qituvchi egaligi, natija tahriri.
 */
class ExamAccessTest extends Phase5ItBase {

    @Autowired
    TeacherRepository teacherRepository;
    @Autowired
    GroupRepository groupRepository;
    @Autowired
    StudentRepository studentRepository;
    @Autowired
    AttendanceRepository attendanceRepository;
    @Autowired
    ExamRepository examRepository;
    @Autowired
    ExamResultRepository examResultRepository;

    private User teacherUser;
    private Long groupId;
    private Long examId;

    /** O'qituvchi (login bilan), uning guruhi va shu guruhdagi imtihon. */
    private void setUpTeacherGroupExam() {
        teacherUser = newUser(UserRole.TEACHER);
        Long teacherId = linkedTeacher(teacherUser);
        groupId = fixtures.group(fixtures.course(700_000), GroupStatus.ACTIVE, teacherId);
        examId = inTx(() -> {
            Teacher teacher = teacherRepository.findById(teacherId).orElseThrow();
            return examRepository.save(Exam.builder()
                .examName("Oraliq imtihon")
                .group(groupRepository.findById(groupId).orElseThrow())
                .teacher(teacher)
                .examDate(LocalDate.of(2026, 9, 20))
                .totalMarks(BigDecimal.valueOf(100))
                .passMarks(BigDecimal.valueOf(60))
                .isActive(true)
                .build()).getId();
        });
    }

    private Long linkedTeacher(User user) {
        return inTx(() -> {
            Teacher t = teacherRepository.findById(fixtures.teacher()).orElseThrow();
            t.setUser(userRepository.findById(user.getId()).orElseThrow());
            return teacherRepository.save(t).getId();
        });
    }

    /** Guruhga yozilgan, {@code presentDays} marta kelgan o'quvchi; qaytaradi — [studentId, sgId]. */
    private Long[] studentIn(Long group, int presentDays) {
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, group).start(LocalDate.of(2026, 9, 1)).save();
        inTx(() -> {
            for (int i = 0; i < presentDays; i++) {
                attendanceRepository.save(Attendance.builder()
                    .student(studentRepository.findById(student).orElseThrow())
                    .group(groupRepository.findById(group).orElseThrow())
                    .attendanceDate(LocalDate.of(2026, 9, 1).plusDays(i))
                    .status(AttendanceStatus.PRESENT)
                    .build());
            }
        });
        return new Long[]{student, sg};
    }

    private void makeOverdue(Long sgId) {
        // Billing-v2 ta'rifi: balance < 0 va debt_since grace (3 kun) dan oldin — OVERDUE
        jdbc.update("UPDATE student_groups SET balance = -700000, debt_since = ? WHERE id = ?",
            LocalDate.of(2026, 8, 1), sgId);
    }

    // ── E-01 ───────────────────────────────────────────────────────────

    @Test
    void eligibility_usesBillingV2Status_notStringComparison() throws Exception {
        setUpTeacherGroupExam();
        User admin = newUser(UserRole.ADMIN);
        Long paid = studentIn(groupId, 8)[0];
        Long[] overdue = studentIn(groupId, 8);
        makeOverdue(overdue[1]);
        Long fewLessons = studentIn(groupId, 7)[0];
        Long otherGroupStudent = studentIn(fixtures.group(fixtures.course(500_000)), 8)[0];

        mvc.perform(get("/api/exams/" + examId + "/eligible-students").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[*].id", hasItem(paid.intValue())))
            .andExpect(jsonPath("$.data[*].id", not(hasItem(overdue[0].intValue()))))
            .andExpect(jsonPath("$.data[*].id", not(hasItem(fewLessons.intValue()))))
            .andExpect(jsonPath("$.data[*].id", not(hasItem(otherGroupStudent.intValue()))));

        // Avval to'lagan o'quvchi ham doim 400 olardi
        mvc.perform(post("/api/exams/" + examId + "/register-student").param("studentId", paid.toString())
                .with(as(admin)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.studentId").value(paid.intValue()));
        mvc.perform(post("/api/exams/" + examId + "/register-student").param("studentId", overdue[0].toString())
                .with(as(admin)))
            .andExpect(status().isBadRequest());
    }

    // ── E-02 ───────────────────────────────────────────────────────────

    @Test
    void teacher_onlyOwnExamsAndStudents_limitedPersonalData() throws Exception {
        setUpTeacherGroupExam();
        Long own = studentIn(groupId, 8)[0];
        User otherTeacher = newUser(UserRole.TEACHER);
        Long otherGroup = fixtures.group(fixtures.course(500_000), GroupStatus.ACTIVE, linkedTeacher(otherTeacher));
        Long foreign = studentIn(otherGroup, 8)[0];

        // Begona o'qituvchi — imtihonga umuman kira olmaydi
        mvc.perform(get("/api/exams/" + examId + "/eligible-students").with(as(otherTeacher)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/" + examId + "/register-student").param("studentId", own.toString())
                .with(as(otherTeacher)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/" + examId + "/calculate-payment").param("studentId", own.toString())
                .with(as(otherTeacher)))
            .andExpect(status().isForbidden());

        // Imtihon egasi — lekin begona o'quvchi bilan emas
        mvc.perform(post("/api/exams/" + examId + "/calculate-payment").param("studentId", foreign.toString())
                .with(as(teacherUser)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/" + examId + "/register-student").param("studentId", foreign.toString())
                .with(as(teacherUser)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/" + examId + "/results").with(as(teacherUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + foreign + ",\"marksObtained\":90}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/exams/" + examId + "/calculate-payment").param("studentId", own.toString())
                .with(as(teacherUser)))
            .andExpect(status().isOk());

        // O'qituvchiga shaxsiy ma'lumotsiz, ma'muriyatga to'liq
        String phone = studentRepository.findById(own).orElseThrow().getPhone();
        mvc.perform(get("/api/exams/" + examId + "/eligible-students").with(as(teacherUser)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(own.intValue()))
            .andExpect(jsonPath("$.data[0].firstName").isNotEmpty())
            .andExpect(jsonPath("$.data[0].phone").doesNotExist())
            .andExpect(jsonPath("$.data[0].parentPhone").doesNotExist())
            .andExpect(jsonPath("$.data[0].address").doesNotExist());
        mvc.perform(get("/api/exams/" + examId + "/eligible-students").with(as(newUser(UserRole.ADMIN))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].phone").value(phone));
    }

    // ── E-03 ───────────────────────────────────────────────────────────

    @Test
    void resultEdit_acceptsEditNoteAndLegacyChangeReason() throws Exception {
        setUpTeacherGroupExam();
        User admin = newUser(UserRole.ADMIN);
        Long student = studentIn(groupId, 8)[0];
        Long resultId = inTx(() -> examResultRepository.save(ExamResult.builder()
            .exam(examRepository.findById(examId).orElseThrow())
            .student(studentRepository.findById(student).orElseThrow())
            .marksObtained(BigDecimal.valueOf(50))
            .build()).getId());
        String url = "/api/exams/" + examId + "/results/" + resultId;

        mvc.perform(put(url).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marksObtained\":75,\"changeReason\":\"qayta tekshirildi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.editNote", containsString("qayta tekshirildi")))
            .andExpect(jsonPath("$.data.isPassed").value(true));
        mvc.perform(put(url).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marksObtained\":55,\"editNote\":\"apellyatsiya\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.editNote", containsString("apellyatsiya")));
        mvc.perform(put(url).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marksObtained\":60}"))
            .andExpect(status().isBadRequest());
    }

    // ── X-02: yetishmagan query parametri ─────────────────────────────

    @Test
    void missingRequestParam_is400_not500() throws Exception {
        setUpTeacherGroupExam();
        mvc.perform(post("/api/exams/" + examId + "/register-student").with(as(newUser(UserRole.ADMIN))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("error.param.missing"));
    }
}
