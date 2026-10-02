package com.crm.lec;

import com.crm.entity.ContractTemplate;
import com.crm.entity.Course;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ContractTemplateRepository;
import com.crm.repository.CourseRepository;
import com.crm.repository.StudentRepository;
import com.crm.util.ContractHtml;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 1-bosqich: sozlamalar (§5) va shartnoma narx snapshot'i, belgilar, holatlar (§6.1–6.2). */
class SettingsAndContractTest extends LecItBase {

    @Autowired ContractTemplateRepository templateRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired StudentRepository studentRepository;

    private User sa;
    private User admin;

    @BeforeEach
    void setUp() {
        seedCenter();
        sa = newUser(UserRole.SUPER_ADMIN);
        admin = newUser(UserRole.ADMIN);
    }

    private Long template(String content, ContractType type) {
        return inTx(() -> {
            templateRepository.findByIsDefaultTrue().ifPresent(t -> {
                t.setDefault(false);
                templateRepository.save(t);
            });
            ContractTemplate t = new ContractTemplate();
            t.setTitle("T");
            t.setType(type);
            t.setContent(content);
            t.setDefault(true);
            return templateRepository.save(t).getId();
        });
    }

    private JsonNode generate(String body) throws Exception {
        return data(mvc.perform(post("/api/contracts/generate").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn());
    }

    // ── §5 Sozlamalar ────────────────────────────────────────────────────

    @Test
    void centerSettings_readByStaff_writeBySuperAdminOnly_validated_audited() throws Exception {
        mvc.perform(get("/api/settings/center").with(as(newUser(UserRole.TEACHER))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.inn").value("311626069"))
            .andExpect(jsonPath("$.data.bankAccount").value("20208000007147330001"))
            .andExpect(jsonPath("$.data.supportPhone").value("+998 77 337 32 33"))
            .andExpect(jsonPath("$.data.missing").isEmpty());

        mvc.perform(put("/api/settings/center").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"inn\":\"123456789\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/center").with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"inn\":\"12345\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("settings.center.inn.invalid"));
        mvc.perform(put("/api/settings/center").with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"bankMfo\":\"974\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("settings.center.bankMfo.invalid"));
        mvc.perform(put("/api/settings/center").with(as(sa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"schoolName\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("settings.center.field.unknown"));

        // Qisman: faqat berilgan maydon; bo'sh joylar olib tashlanadi; "" — tozalash
        mvc.perform(put("/api/settings/center").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankAccount\":\"2020 8000 0071 4733 0002\",\"directorName\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bankAccount").value("20208000007147330002"))
            .andExpect(jsonPath("$.data.inn").value("311626069"))
            .andExpect(jsonPath("$.data.missing", hasItem("directorName")));
        mvc.perform(get("/api/settings/center").with(as(admin)))
            .andExpect(jsonPath("$.data.directorName").value(""));

        assertThat(awaitAudits("UPDATE", "Settings")).isPositive();
        String details = jdbc.queryForObject(
            "SELECT details_json FROM audit_logs WHERE entity_type = 'Settings' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(details).contains("center.bankAccount").contains("20208000007147330002");
    }

    // ── §6.1 Narx snapshot'i ─────────────────────────────────────────────

    @Test
    void snapshot_monthlyDiscount_frozenAfterPriceChange() throws Exception {
        template("{{coursePrice}}|{{discountPercent}}|{{discountAmount}}|{{finalAmount}}|{{monthlyFee}}|{{paymentType}}|{{startDate}}",
            ContractType.OFFLINE);
        Long courseId = fixtures.course(700_000);
        Long student = fixtures.student();
        Long sg = fixtures.enrollment(student, fixtures.group(courseId)).discount("10").save();

        JsonNode c = generate("{\"studentId\":" + student + "}");
        assertThat(c.get("studentGroupId").asLong()).isEqualTo(sg);
        assertThat(c.get("paymentType").asText()).isEqualTo("MONTHLY");
        assertThat(c.get("listPrice").decimalValue()).isEqualByComparingTo("700000");
        assertThat(c.get("discountPercent").decimalValue()).isEqualByComparingTo("10");
        assertThat(c.get("discountAmount").decimalValue()).isEqualByComparingTo("70000");
        assertThat(c.get("finalAmount").decimalValue()).isEqualByComparingTo("630000");
        assertThat(c.get("renderedContent").asText()).isEqualTo("700 000|10|70 000|630 000|630 000|oylik|15.09.2026");

        // Kurs narxi o'zgardi — shartnoma o'zgarmaydi
        inTx(() -> {
            Course course = courseRepository.findById(courseId).orElseThrow();
            course.setMonthlyPrice(BigDecimal.valueOf(900_000));
            courseRepository.save(course);
        });
        mvc.perform(get("/api/contracts/" + c.get("id").asLong()).with(as(admin)))
            .andExpect(jsonPath("$.data.finalAmount").value(630000))
            .andExpect(jsonPath("$.data.listPrice").value(700000));
    }

    @Test
    void snapshot_overrideZeroFallsBackToCoursePrice_perLesson_individual() throws Exception {
        template("{{coursePrice}}/{{finalAmount}}/{{paymentType}}", ContractType.OFFLINE);
        Long s1 = fixtures.student();
        fixtures.enrollment(s1, fixtures.group(fixtures.course(700_000))).override(0).save();
        assertThat(generate("{\"studentId\":" + s1 + "}").get("renderedContent").asText())
            .isEqualTo("700 000/700 000/oylik");

        Long s2 = fixtures.student();
        fixtures.enrollment(s2, fixtures.group(fixtures.course(700_000))).override(500_000).discount("20").save();
        assertThat(generate("{\"studentId\":" + s2 + "}").get("renderedContent").asText())
            .isEqualTo("500 000/400 000/oylik");

        Long s3 = fixtures.student();
        fixtures.enrollment(s3, fixtures.group(fixtures.course(700_000, 50_000L))).perLesson(60_000).discount("10").save();
        JsonNode c = generate("{\"studentId\":" + s3 + "}");
        assertThat(c.get("paymentType").asText()).isEqualTo("PER_LESSON");
        assertThat(c.get("renderedContent").asText()).isEqualTo("60 000/54 000/darsbay");
    }

    @Test
    void severalEnrollments_requireStudentGroupId() throws Exception {
        template("{{groupName}}", ContractType.OFFLINE);
        Long student = fixtures.student();
        fixtures.enrollment(student, fixtures.group(fixtures.course(500_000))).save();
        Long second = fixtures.enrollment(student, fixtures.group(fixtures.course(600_000))).save();
        Long foreign = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(600_000))).save();

        mvc.perform(post("/api/contracts/generate").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + student + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.studentGroupRequired"))
            .andExpect(jsonPath("$.data.studentGroups.length()").value(2));
        mvc.perform(post("/api/contracts/generate").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":" + student + ",\"studentGroupId\":" + foreign + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.studentGroup.mismatch"));
        JsonNode c = generate("{\"studentId\":" + student + ",\"studentGroupId\":" + second + "}");
        assertThat(c.get("finalAmount").decimalValue()).isEqualByComparingTo("600000");
    }

    // ── Belgilar: escape, tozalash, noma'lum belgi, rekvizit yo'q ───────

    @Test
    void placeholders_escaped_templateSanitized_unknownRejected() throws Exception {
        Long student = fixtures.student();
        inTx(() -> {
            Student s = studentRepository.findById(student).orElseThrow();
            s.setFirstName("<script>alert(1)</script>");
            studentRepository.save(s);
        });

        String created = mvc.perform(post("/api/contract-templates").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "title", "Asosiy", "type", "OFFLINE", "isDefault", true,
                    "content", "<p onclick=\"steal()\" style=\"x\">Buyurtmachi: <b>{{studentName}}</b></p>"
                        + "<script>evil()</script><img src=x onerror=alert(1)>Raqam {{contractNumber}}"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String stored = objectMapper.readTree(created).get("data").get("content").asText();
        assertThat(stored).isEqualTo("<p>Buyurtmachi: <b>{{studentName}}</b></p>Raqam {{contractNumber}}");

        JsonNode c = generate("{\"studentId\":" + student + "}");
        String html = c.get("renderedContent").asText();
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>");
        assertThat(html).startsWith("<p>Buyurtmachi: <b>").contains("CTR-2026-");

        mvc.perform(post("/api/contract-templates").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"X\",\"content\":\"{{studentName}} {{studentFatherName}} {{center.inn}} {{foo}}\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.template.unknownPlaceholder"))
            .andExpect(jsonPath("$.message", containsString("foo")))
            .andExpect(jsonPath("$.data.unknown", hasItem("studentFatherName")))
            .andExpect(jsonPath("$.data.unknown", not(hasItem("center.inn"))));

        mvc.perform(get("/api/contract-templates/placeholders").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].key", hasItem("finalAmount")))
            .andExpect(jsonPath("$.data[*].key", hasItem("center.bankAccount")));
    }

    @Test
    void sanitizer_isIdempotent_plainTextKeepsLines() {
        String plain = "1. Taraflar\n2. Narx: 5 < 6 & \"ok\"";
        String once = ContractHtml.sanitize(plain);
        assertThat(once).isEqualTo("1. Taraflar<br/>\n2. Narx: 5 &lt; 6 &amp; &quot;ok&quot;");
        assertThat(ContractHtml.sanitize(once)).isEqualTo(once);
        String markup = "<h2>Shartlar<p>matn <u>bir</h2><table><tr><td>a</td></tr></table></p></i>";
        String clean = ContractHtml.sanitize(markup);
        assertThat(clean).isEqualTo("<h2>Shartlar<p>matn <u>bir</u></p></h2><table><tr><td>a</td></tr></table>");
        assertThat(ContractHtml.sanitize(clean)).isEqualTo(clean);
    }

    @Test
    void missingRequisite_renderedAsBlank_andListed() throws Exception {
        template("H/r: {{center.bankAccount}}; MFO: {{center.bankMfo}}", ContractType.OFFLINE);
        mvc.perform(put("/api/settings/center").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankAccount\":\"\"}"))
            .andExpect(status().isOk());
        JsonNode c = generate("{\"studentId\":" + fixtures.student() + "}");
        assertThat(c.get("renderedContent").asText()).isEqualTo("H/r: ________; MFO: 00974");
        assertThat(c.get("missingRequisites").toString()).contains("bankAccount");
        assertThat(c.get("listPrice").isNull()).isTrue();  // faol yozilmasiz o'quvchi — narxsiz
    }

    // ── §6.2 Holatlar ────────────────────────────────────────────────────

    @Test
    void stateMachine_signCancelDelete() throws Exception {
        template("{{studentName}}", ContractType.OFFLINE);
        long id = generate("{\"studentId\":" + fixtures.student() + "}").get("id").asLong();

        mvc.perform(post("/api/contracts/" + id + "/sign").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("SIGNED"))
            .andExpect(jsonPath("$.data.signedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.signedByName").isNotEmpty());
        mvc.perform(post("/api/contracts/" + id + "/sign").with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("contract.notDraft"));
        mvc.perform(delete("/api/contracts/" + id).with(as(admin)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("contract.signed"));
        mvc.perform(post("/api/contracts/" + id + "/cancel").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.cancel.reasonRequired"));
        mvc.perform(post("/api/contracts/" + id + "/cancel").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Xato tuzilgan\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.cancelReason").value("Xato tuzilgan"));
        mvc.perform(post("/api/contracts/" + id + "/cancel").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Yana bir bor\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("contract.alreadyCancelled"));
        // Imzolangan bo'lgani uchun bekor qilingandan keyin ham o'chirilmaydi
        mvc.perform(delete("/api/contracts/" + id).with(as(admin)))
            .andExpect(status().isConflict());

        long draft = generate("{\"studentId\":" + fixtures.student() + "}").get("id").asLong();
        mvc.perform(delete("/api/contracts/" + draft).with(as(admin))).andExpect(status().isOk());

        template("{{studentName}}", ContractType.OFFER);
        long offer = generate("{\"studentId\":" + fixtures.student() + "}").get("id").asLong();
        mvc.perform(post("/api/contracts/" + offer + "/sign").with(as(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("contract.sign.offer"));
        mvc.perform(post("/api/contracts/" + offer + "/accept-offer").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.hasPdf").value(true));
    }
}
