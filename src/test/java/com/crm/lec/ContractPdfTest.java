package com.crm.lec;

import com.crm.entity.ContractTemplate;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.UserRole;
import com.crm.repository.ContractTemplateRepository;
import com.crm.repository.StudentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 2-bosqich: server PDF (OpenHTMLtoPDF + Noto), muzlatish, chop etish ko'rinishi (§6.3). */
class ContractPdfTest extends LecItBase {

    /** O'zbek lotini (ʻ ʼ), o'zbek/rus kirilli, № « » — – — Noto qamrashi shart ("tofu" yo'q). */
    private static final String GLYPHS = "Oʻzbekiston maʼlumot ўқғҳ ЎҚҒҲ Жавоб № «matn» — – ok";

    @Autowired ContractTemplateRepository templateRepository;
    @Autowired StudentRepository studentRepository;

    @Value("${app.contracts.pdf-dir}")
    String pdfDir;

    private User admin;
    private User sa;

    @BeforeEach
    void setUp() {
        seedCenter();
        admin = newUser(UserRole.ADMIN);
        sa = newUser(UserRole.SUPER_ADMIN);
        inTx(() -> {
            templateRepository.findByIsDefaultTrue().ifPresent(t -> {
                t.setDefault(false);
                templateRepository.save(t);
            });
            ContractTemplate t = new ContractTemplate();
            t.setTitle("PDF");
            t.setType(ContractType.OFFLINE);
            t.setContent("<h2>1. Shartnoma predmeti</h2><p>" + GLYPHS + "</p><p>Buyurtmachi: <b>{{studentName}}</b>, "
                + "{{center.legalName}} ({{center.legalNameRu}}), {{center.licenseInfo}}</p>"
                + "<table><tr><td>Narx</td><td>{{finalAmount}}</td></tr></table>");
            t.setDefault(true);
            templateRepository.save(t);
        });
    }

    private long contract() throws Exception {
        Long student = fixtures.student();
        inTx(() -> {
            Student s = studentRepository.findById(student).orElseThrow();
            s.setFirstName("Gʻulom");
            s.setLastName("Қодиров");
            studentRepository.save(s);
        });
        fixtures.enrollment(student, fixtures.group(fixtures.course(700_000))).discount("10").save();
        MvcResult r = mvc.perform(post("/api/contracts/generate").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":" + student + "}"))
            .andExpect(status().isCreated()).andReturn();
        return data(r).get("id").asLong();
    }

    private byte[] pdf(long id) throws Exception {
        return mvc.perform(get("/api/contracts/" + id + "/pdf").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().getResponse().getContentAsByteArray();
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void draftPdf_embedsNotoFonts_andRendersUzbekCyrillicGlyphs() throws Exception {
        long id = contract();
        mvc.perform(get("/api/contracts/" + id + "/pdf").with(as(admin)))
            .andExpect(header().string("Content-Disposition", containsString("inline")))
            .andExpect(header().string("Content-Disposition", containsString("CTR-2026-")));
        mvc.perform(get("/api/contracts/" + id + "/pdf").param("download", "true").with(as(admin)))
            .andExpect(header().string("Content-Disposition", containsString("attachment")));

        byte[] bytes = pdf(id);
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            for (String g : List.of("ʻ", "ʼ", "ў", "қ", "ғ", "ҳ", "Ж", "№", "«", "—", "Gʻulom", "Қодиров",
                    "ООО «ADIZONE LC»", "630 000", "311626069", "Adizov Oqilbek")) {
                assertThat(text).as("PDF matnida: " + g).contains(g);
            }
            List<String> fonts = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    fonts.add(page.getResources().getFont(name).getName());
                }
            }
            assertThat(fonts).isNotEmpty().allMatch(f -> f.contains("Noto"));
        }

        // DRAFT — har so'rovda yangidan: rekvizit o'zgarsa PDF ham o'zgaradi
        mvc.perform(put("/api/settings/center").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"legalName\":\"\\\"ADIZONE LC 2\\\" MChJ\"}"))
            .andExpect(status().isOk());
        try (PDDocument doc = Loader.loadPDF(pdf(id))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("ADIZONE LC 2");
        }
    }

    @Test
    void signedPdf_isFrozen_settingsChangeDoesNotAffectIt_andNotServedViaFiles() throws Exception {
        long id = contract();
        String uuid = data(mvc.perform(post("/api/contracts/" + id + "/sign").with(as(admin)))
            .andExpect(status().isOk()).andReturn()).get("uuid").asText();

        Path file = Path.of(pdfDir).toAbsolutePath().resolve(uuid + ".pdf");
        assertThat(file).exists();
        String storedHash = jdbc.queryForObject("SELECT pdf_sha256 FROM contracts WHERE id = ?", String.class, id);
        assertThat(sha(Files.readAllBytes(file))).isEqualTo(storedHash);

        byte[] first = pdf(id);
        mvc.perform(put("/api/settings/center").with(as(sa)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"legalName\":\"Boshqa nom MChJ\",\"bankMfo\":\"00444\"}"))
            .andExpect(status().isOk());
        byte[] second = pdf(id);
        assertThat(sha(second)).isEqualTo(sha(first)).isEqualTo(storedHash);
        try (PDDocument doc = Loader.loadPDF(second)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("\"ADIZONE LC\" MChJ").doesNotContain("Boshqa nom");
        }

        // Ochiq /api/files/** orqali muzlatilgan PDF berilmaydi (katalog ham, nom ham)
        mvc.perform(get("/api/files/" + uuid + ".pdf")).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/contracts/" + uuid + ".pdf")).andExpect(status().isNotFound());
        // Shartnoma endpointi — faqat ma'muriyat
        mvc.perform(get("/api/contracts/" + id + "/pdf").with(as(newUser(UserRole.TEACHER))))
            .andExpect(status().isForbidden());
    }

    @Test
    void printView_isSameXhtml_escaped() throws Exception {
        long id = contract();
        inTx(() -> {
            Student s = studentRepository.findAll().stream()
                .filter(x -> "Gʻulom".equals(x.getFirstName())).findFirst().orElseThrow();
            s.setPhone("<b>+998</b>");
            studentRepository.save(s);
        });
        String html = mvc.perform(get("/api/contracts/" + id + "/print").with(as(admin)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("SHARTNOMA №").contains("Gʻulom Қодиров").contains("&lt;b&gt;+998&lt;/b&gt;")
            .contains("Yakuniy summa").doesNotContain("@@");
    }
}
