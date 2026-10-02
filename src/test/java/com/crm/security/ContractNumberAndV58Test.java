package com.crm.security;

import com.crm.dto.request.ContractCreateDto;
import com.crm.entity.ContractTemplate;
import com.crm.entity.User;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ContractTemplateRepository;
import com.crm.service.ContractNumberService;
import com.crm.service.ContractService;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-01 (P0) + Q8: shartnoma raqami {@code CTR-YYYY-NNNNN}, har yil 00001 dan (V59); V58/V59 idempotentligi.
 *
 * <p>Avval raqam {@code count() + 1} edi: o'rtadagi shartnoma o'chirilgach keyingi raqam
 * mavjudiga to'g'ri kelib, har {@code generate} UNIQUE buzilishi bilan 500 qaytarardi.
 */
class ContractNumberAndV58Test extends Phase5ItBase {

    @Autowired
    ContractTemplateRepository templateRepository;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ContractService contractService;
    @Autowired
    ContractNumberService contractNumberService;

    private String generate(User admin, Long student) throws Exception {
        String body = mvc.perform(post("/api/contracts/generate").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":" + student + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return data.get("id").asLong() + "|" + data.get("contractNumber").asText();
    }

    private Long defaultTemplate() {
        return inTx(() -> {
            ContractTemplate t = new ContractTemplate();
            t.setTitle("Standart");
            t.setType(ContractType.OFFLINE);
            t.setContent("Shartnoma {{contractNumber}}: {{studentName}}");
            t.setDefault(true);
            return templateRepository.save(t).getId();
        });
    }

    private void insertContract(String number, Long student, Long templateId) {
        jdbc.update("INSERT INTO contracts (uuid, contract_number, student_id, template_id, type, status,"
                + " contract_date) VALUES (?, ?, ?, ?, 'OFFLINE', 'DRAFT', ?)",
            UUID.randomUUID().toString(), number, student, templateId, LocalDate.of(2026, 9, 15));
    }

    @Test
    void numbers_survivesDeleteAndLegacyNumbers() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        defaultTemplate();
        Long student = fixtures.student();
        String pattern = "CTR-2026-\\d{5}";  // shartnoma sanasi — test soati (15.09.2026)

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

    // ── Har yil noldan (buyurtmachi qarori, V59) ─────────────────────────

    @Test
    void yearRollover_restartsFrom00001() throws Exception {
        User admin = newUser(UserRole.ADMIN);
        defaultTemplate();
        Long student = fixtures.student();

        clock.setDate(LocalDate.of(2026, 12, 31));
        assertThat(generate(admin, student).split("\\|")[1]).isEqualTo("CTR-2026-00001");
        assertThat(generate(admin, student).split("\\|")[1]).isEqualTo("CTR-2026-00002");

        clock.setDate(LocalDate.of(2027, 1, 1));
        assertThat(generate(admin, student).split("\\|")[1]).isEqualTo("CTR-2027-00001");
        assertThat(generate(admin, student).split("\\|")[1]).isEqualTo("CTR-2027-00002");
        assertThat(jdbc.queryForObject("SELECT contract_date FROM contracts WHERE contract_number = 'CTR-2027-00001'",
            LocalDate.class)).isEqualTo(LocalDate.of(2027, 1, 1));

        // Eski yil hisoblagichi o'z o'rnida davom etadi
        assertThat(contractNumberService.next(2026)).isEqualTo("CTR-2026-00003");
    }

    @Test
    void newYearCounter_continuesAfterExistingV58Numbers() {
        Long templateId = defaultTemplate();
        Long student = fixtures.student();
        insertContract("CTR-2026-00041", student, templateId);  // V58 (yagona sequence) davri
        insertContract("CTR-0007", student, templateId);         // eski format — hisobga olinmaydi

        assertThat(contractNumberService.next(2026)).isEqualTo("CTR-2026-00042");
        assertThat(contractNumberService.next(2027)).isEqualTo("CTR-2027-00001");
    }

    @Test
    void parallelGenerate_givesDistinctConsecutiveNumbers() throws Exception {
        defaultTemplate();
        Long student = fixtures.student();
        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> numbers = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        ContractCreateDto dto = new ContractCreateDto();
                        dto.setStudentId(student);
                        numbers.add(contractService.generateForStudent(dto).getContractNumber());
                    }
                    return numbers;
                }));
            }
            start.countDown();
            Set<String> all = new HashSet<>();
            for (Future<List<String>> f : futures) {
                all.addAll(f.get(60, TimeUnit.SECONDS));
            }

            Set<String> expected = new HashSet<>();
            for (int i = 1; i <= threads * perThread; i++) {
                expected.add(String.format("CTR-2026-%05d", i));
            }
            assertThat(all).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts", Integer.class))
                .isEqualTo(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void v59_isIdempotent_seedsFromExistingNumbers_neverDecreases() {
        Assumptions.assumeTrue(isPostgres(), "V59 — PostgreSQL skripti (pgtest)");
        Long templateId = defaultTemplate();
        Long student = fixtures.student();
        insertContract("CTR-2026-00041", student, templateId);
        insertContract("CTR-2027-00007", student, templateId);
        insertContract("CTR-0003", student, templateId);

        runScript("db/migration/V59__contract_number_per_year.sql");
        runScript("db/migration/V59__contract_number_per_year.sql");
        assertThat(counter(2026)).isEqualTo(41L);
        assertThat(counter(2027)).isEqualTo(7L);

        // Ilova oldinga ketgan — qayta bajarilgan V59 hisoblagichni kamaytirmaydi
        jdbc.update("UPDATE contract_number_counters SET last_value = 50 WHERE contract_year = 2026");
        runScript("db/migration/V59__contract_number_per_year.sql");
        assertThat(counter(2026)).isEqualTo(50L);
        assertThat(contractNumberService.next(2026)).isEqualTo("CTR-2026-00051");
    }

    private Long counter(int year) {
        return jdbc.queryForObject("SELECT last_value FROM contract_number_counters WHERE contract_year = ?",
            Long.class, year);
    }
}
