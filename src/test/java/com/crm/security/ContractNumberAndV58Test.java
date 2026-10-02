package com.crm.security;

import com.crm.entity.ContractTemplate;
import com.crm.entity.User;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ContractTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-01 (P0) + Q8: shartnoma raqami sequence'dan, {@code CTR-YYYY-NNNNN}; V58 idempotentligi.
 *
 * <p>Avval raqam {@code count() + 1} edi: o'rtadagi shartnoma o'chirilgach keyingi raqam
 * mavjudiga to'g'ri kelib, har {@code generate} UNIQUE buzilishi bilan 500 qaytarardi.
 */
class ContractNumberAndV58Test extends Phase5ItBase {

    @Autowired
    ContractTemplateRepository templateRepository;
    @Autowired
    ObjectMapper objectMapper;

    private String generate(User admin, Long student) throws Exception {
        String body = mvc.perform(post("/api/contracts/generate").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":" + student + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("id").asLong() + "|" + data.get("contractNumber").asText();
    }

    @Test
    void numbers_comeFromSequence_survivesDeleteAndLegacyNumbers() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        inTx(() -> {
            ContractTemplate t = new ContractTemplate();
            t.setTitle("Standart");
            t.setType(ContractType.OFFLINE);
            t.setContent("Shartnoma {{contractNumber}}: {{studentName}}");
            t.setDefault(true);
            return templateRepository.save(t);
        });
        Long student = fixtures.student();
        String pattern = "CTR-" + LocalDate.now().getYear() + "-\\d{5}";

        String[] first = generate(admin, student).split("\\|");
        String[] second = generate(admin, student).split("\\|");
        String third = generate(admin, student).split("\\|")[1];
        assertThat(first[1]).matches(pattern);
        assertThat(second[1]).matches(pattern).isNotEqualTo(first[1]);

        // Eski formatdagi raqam bor va o'rtadagi shartnoma o'chirildi — count()+1 shu yerda 500 berardi
        Long templateId = jdbc.queryForObject("SELECT id FROM contract_templates WHERE is_default = TRUE",
            Long.class);
        jdbc.update("INSERT INTO contracts (uuid, contract_number, student_id, template_id, type, status,"
                + " contract_date) VALUES (?, 'CTR-0003', ?, ?, 'OFFLINE', 'DRAFT', ?)",
            java.util.UUID.randomUUID().toString(), student, templateId, LocalDate.now());
        mvc.perform(delete("/api/contracts/" + second[0]).with(as(admin))).andExpect(status().isOk());

        Set<String> numbers = new HashSet<>(Set.of(first[1], second[1], third, "CTR-0003"));
        for (int i = 0; i < 3; i++) {
            String next = generate(admin, student).split("\\|")[1];
            assertThat(next).matches(pattern);
            assertThat(numbers.add(next)).as("takrorlanmas raqam: " + next).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT contract_number FROM contracts WHERE contract_number = 'CTR-0003'",
            String.class)).isEqualTo("CTR-0003");
    }

    private void runScript(String path) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource(path)),
                false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX, ScriptUtils.EOF_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER, ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            return null;
        });
    }

    @Test
    void v58_isIdempotent_andCreatesWhatTheCodeNeeds() {
        Assumptions.assumeTrue(isPostgres(), "V58 — PostgreSQL skripti (pgtest)");
        Long student = fixtures.student();
        jdbc.update("INSERT INTO contract_templates (uuid, title, type, is_default) VALUES (?, 'T', 'OFFLINE', FALSE)",
            java.util.UUID.randomUUID().toString());
        Long templateId = jdbc.queryForObject("SELECT MAX(id) FROM contract_templates", Long.class);
        jdbc.update("INSERT INTO contracts (uuid, contract_number, student_id, template_id, type, status,"
                + " contract_date) VALUES (?, 'CTR-2026-00041', ?, ?, 'OFFLINE', 'DRAFT', CURRENT_DATE)",
            java.util.UUID.randomUUID().toString(), student, templateId);

        runScript("db/migration/V58__phase5_security.sql");
        runScript("db/migration/V58__phase5_security.sql");

        // Sequence mavjud raqamdan keyin davom etadi
        assertThat(jdbc.queryForObject("SELECT nextval('contract_number_seq')", Long.class)).isEqualTo(42L);
        // token_version: NOT NULL DEFAULT 0
        assertThat(jdbc.queryForObject("""
            SELECT is_nullable || ':' || column_default FROM information_schema.columns
            WHERE table_name = 'users' AND column_name = 'token_version'
            """, String.class)).isEqualTo("NO:0");
        // exam_registrations (exam_id, student_id) bo'yicha bitta UNIQUE indeks
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE tablename = 'exam_registrations' AND indexdef LIKE 'CREATE UNIQUE INDEX%(exam_id, student_id)%'
            """, Integer.class)).isEqualTo(1);
    }
}
