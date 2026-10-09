package com.crm.security;

import com.crm.entity.Lead;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.LeadRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O'quvchi manbasi (students.source, V79): lid konvertatsiyasida ko'chiriladi, qo'lda — ixtiyoriy va
 * tekshiriladi, ro'yxat filtri; {@code GET /api/analytics/sources} va {@code /students-by-source}.
 */
class StudentSourceAnalyticsTest extends Phase5ItBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired LeadRepository leadRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired StudentGroupRepository studentGroupRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ObjectMapper objectMapper;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = newUser(UserRole.ADMIN);
    }

    @Test
    void convert_copiesLeadSource_evenOutsideCatalog() throws Exception {
        Long website = lead("WEBSITE", "2026-09-10T10:00", null);
        convert(website).andExpect(status().isCreated());
        Student s = studentByLead(website);
        assertThat(s.getSource()).isEqualTo("WEBSITE");
        assertThat(s.getMarketingSource()).isEqualTo(MarketingSource.OTHER);   // eski enum — o'zgarmagan xulq

        Long instagram = lead("instagram", "2026-09-10T11:00", null);
        convert(instagram).andExpect(status().isCreated());
        assertThat(studentByLead(instagram).getSource()).isEqualTo("INSTAGRAM");

        Long none = lead(null, "2026-09-10T12:00", null);
        convert(none).andExpect(status().isCreated());
        assertThat(studentByLead(none).getSource()).isNull();
    }

    @Test
    void manualStudent_sourceOptionalAndValidated_listFilter() throws Exception {
        createStudent("\"source\":\"bogus\",").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("student.source.invalid"));

        createStudent("\"source\":\"referral\",\"sourceNote\":\"Aziz tavsiya qildi\",")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.source").value("REFERRAL"))
            .andExpect(jsonPath("$.data.sourceNote").value("Aziz tavsiya qildi"));
        createStudent("\"source\":\"WALK_IN\",").andExpect(status().isCreated());
        createStudent("").andExpect(status().isCreated()).andExpect(jsonPath("$.data.source").isEmpty());

        mvc.perform(get("/api/students").param("source", "referral").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].source").value("REFERRAL"));
        mvc.perform(get("/api/students").param("source", "REFERRAL,WALK_IN").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/students").param("source", "UNKNOWN").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/students").param("source", "UNKNOWN").param("search", "yo'q-bunday").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(0));
        // Filtrsiz — avvalgidek hammasi
        mvc.perform(get("/api/students").with(as(admin)))
            .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void sourcesReport_bySource_andMetaForm() throws Exception {
        // Davr ichida: INSTAGRAM ×2 (biri tashrif + o'quvchi + 2 to'lov), WEBSITE ×1, manbasiz ×1
        Long paidLead = lead("INSTAGRAM", "2026-09-05T09:00", "form-1");
        inTx(() -> {
            Lead l = leadRepository.findById(paidLead).orElseThrow();
            l.setVisitedAt(LocalDateTime.parse("2026-09-06T10:00"));
            leadRepository.save(l);
        });
        Long student = studentFromLead(paidLead, "INSTAGRAM");
        payment(student, 300_000, "2026-09-07");
        payment(student, 200_000, "2026-09-12");
        payment(student, 999_000, "2026-09-30");   // bugundan (soat 15.09) keyin — hisobga kirmaydi
        lead("INSTAGRAM", "2026-09-08T09:00", "form-1");
        lead("WEBSITE", "2026-09-09T09:00", null);
        lead(null, "2026-09-10T09:00", null);
        // Davrdan tashqarida va import — default chiqariladi
        lead("INSTAGRAM", "2026-08-31T23:59", null);
        Long imported = lead("TELEGRAM", "2026-09-11T09:00", null);
        inTx(() -> {
            Lead l = leadRepository.findById(imported).orElseThrow();
            l.setImportBatch("batch-1");
            leadRepository.save(l);
        });

        JsonNode report = data(mvc.perform(get("/api/analytics/sources")
                .param("from", "2026-09-01").param("to", "2026-09-30").with(as(admin)))
            .andExpect(status().isOk()));
        assertThat(report.get("groupBy").asText()).isEqualTo("SOURCE");
        JsonNode total = report.get("total");
        assertThat(total.get("leads").asLong()).isEqualTo(4);
        assertThat(total.get("firstPayments").asLong()).isEqualTo(1);
        JsonNode rows = report.get("rows");
        assertThat(rows.get(0).get("key").asText()).isEqualTo("INSTAGRAM");
        assertThat(rows.get(0).get("label").asText()).isEqualTo("Instagram");
        assertThat(rows.get(0).get("leads").asLong()).isEqualTo(2);
        assertThat(rows.get(0).get("visited").asLong()).isEqualTo(1);
        assertThat(rows.get(0).get("converted").asLong()).isEqualTo(1);
        assertThat(rows.get(0).get("firstPayments").asLong()).isEqualTo(1);
        assertThat(rows.get(0).get("revenue").decimalValue()).isEqualByComparingTo("500000");
        assertThat(rows.get(0).get("conversionPercent").decimalValue()).isEqualByComparingTo("50.0");
        JsonNode last = rows.get(rows.size() - 1);
        assertThat(last.get("key").asText()).isEqualTo("UNKNOWN");
        assertThat(last.get("label").asText()).isEqualTo("Noma'lum");
        assertThat(last.get("leads").asLong()).isEqualTo(1);

        JsonNode withImported = data(mvc.perform(get("/api/analytics/sources")
                .param("from", "2026-09-01").param("to", "2026-09-30").param("includeImported", "true")
                .with(as(admin))).andExpect(status().isOk()));
        assertThat(withImported.get("total").get("leads").asLong()).isEqualTo(5);

        JsonNode byForm = data(mvc.perform(get("/api/analytics/sources")
                .param("from", "2026-09-01").param("to", "2026-09-30").param("groupBy", "meta_form")
                .with(as(admin))).andExpect(status().isOk()));
        assertThat(byForm.get("rows").get(0).get("key").asText()).isEqualTo("form-1");
        assertThat(byForm.get("rows").get(0).get("leads").asLong()).isEqualTo(2);
        JsonNode noForm = byForm.get("rows").get(byForm.get("rows").size() - 1);
        assertThat(noForm.get("key").asText()).isEqualTo("UNKNOWN");
        assertThat(noForm.get("leads").asLong()).isEqualTo(2);

        mvc.perform(get("/api/analytics/sources").param("groupBy", "COURSE").with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("analytics.sources.groupBy.invalid"));
        mvc.perform(get("/api/analytics/sources").with(as(newUser(UserRole.SALES_MANAGER))))
            .andExpect(status().isForbidden());
    }

    @Test
    void studentsBySource_countsCurrentlyActiveOnly() throws Exception {
        Long course = fixtures.course(500_000);
        Long g1 = fixtures.group(course);
        Long g2 = fixtures.group(course);
        Long s1 = student("INSTAGRAM");
        Long s2 = student(null);
        Long s3 = student("WEBSITE");
        Long s4 = student("INSTAGRAM");
        fixtures.enrollment(s1, g1).save();
        fixtures.enrollment(s2, g1).save();
        Long left = fixtures.enrollment(s3, g1).save();
        inTx(() -> {
            StudentGroup sg = studentGroupRepository.findById(left).orElseThrow();
            sg.setIsActive(false);
            sg.setLeaveDate(LocalDate.of(2026, 9, 10));
            studentGroupRepository.save(sg);
        });
        fixtures.enrollment(s4, g1).save();
        fixtures.enrollment(s4, g2).save();

        JsonNode data = data(mvc.perform(get("/api/analytics/students-by-source").with(as(admin)))
            .andExpect(status().isOk()));
        assertThat(data.get("total").asLong()).isEqualTo(3);
        JsonNode rows = data.get("rows");
        assertThat(rows.get(0).get("source").asText()).isEqualTo("INSTAGRAM");
        assertThat(rows.get(0).get("students").asLong()).isEqualTo(2);
        assertThat(rows.get(0).get("percent").decimalValue()).isEqualByComparingTo("66.7");
        JsonNode last = rows.get(rows.size() - 1);
        assertThat(last.get("source").asText()).isEqualTo("UNKNOWN");
        assertThat(last.get("students").asLong()).isEqualTo(1);
        for (JsonNode r : rows) {
            assertThat(r.get("source").asText()).isNotEqualTo("WEBSITE");
        }
    }

    // ── yordamchilar ───────────────────────────────────────────────────

    private Long lead(String source, String createdAt, String metaFormId) {
        return inTx(() -> {
            Lead l = leadRepository.save(Lead.builder().fullName("Lid " + SEQ.incrementAndGet())
                .phone("+99893" + String.format("%07d", SEQ.incrementAndGet())).status("NEW")
                .converted(false).metaFormId(metaFormId).build());
            // @PrePersist source ni WEBSITE qilib qo'yadi — manbasiz lid uchun qayta null
            l.setSource(source);
            l.setCreatedAt(LocalDateTime.parse(createdAt));
            return leadRepository.save(l).getId();
        });
    }

    private Long studentFromLead(Long leadId, String source) {
        return inTx(() -> {
            Student s = studentRepository.save(Student.builder().firstName("Konv").lastName("O'quvchi")
                .phone("+99894" + String.format("%07d", SEQ.incrementAndGet())).status(StudentStatus.ACTIVE)
                .convertedFromLeadId(leadId).source(source).build());
            Lead l = leadRepository.findById(leadId).orElseThrow();
            l.setStudent(s);
            l.setConverted(true);
            l.setConvertedAt(LocalDateTime.parse("2026-09-06T12:00"));
            leadRepository.save(l);
            return s.getId();
        });
    }

    private Long student(String source) {
        return inTx(() -> studentRepository.save(Student.builder().firstName("S" + SEQ.incrementAndGet())
            .lastName("Test").phone("+99895" + String.format("%07d", SEQ.incrementAndGet()))
            .status(StudentStatus.ACTIVE).source(source).build()).getId());
    }

    private void payment(Long studentId, long cash, String date) {
        inTx(() -> paymentRepository.save(Payment.builder()
            .student(studentRepository.findById(studentId).orElseThrow())
            .amount(BigDecimal.valueOf(cash)).cashAmount(BigDecimal.valueOf(cash))
            .paymentDate(LocalDate.parse(date)).status(PaymentStatus.PAID)
            .receiptNumber("SRC-" + SEQ.incrementAndGet()).build()));
    }

    private Student studentByLead(Long leadId) {
        return studentRepository.findAll().stream()
            .filter(s -> leadId.equals(s.getConvertedFromLeadId())).findFirst().orElseThrow();
    }

    private ResultActions convert(Long leadId) throws Exception {
        return mvc.perform(post("/api/leads/" + leadId + "/convert").with(as(admin))
            .contentType(MediaType.APPLICATION_JSON).content("{\"studyFormat\":\"OFFLINE\"}"));
    }

    private ResultActions createStudent(String sourceFields) throws Exception {
        int n = SEQ.incrementAndGet();
        return mvc.perform(post("/api/students").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("{" + sourceFields + "\"firstName\":\"Qo'lda" + n + "\",\"lastName\":\"Test\",\"phone\":\"+99897"
                + String.format("%07d", n) + "\",\"status\":\"ACTIVE\",\"marketingSource\":\"OTHER\"}"));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
