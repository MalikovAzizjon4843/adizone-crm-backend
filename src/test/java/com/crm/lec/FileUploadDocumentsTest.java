package com.crm.lec;

import com.crm.entity.User;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.UserRole;
import com.crm.service.FileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/files/upload}: rasmdan tashqari pdf, doc, docx, xlsx (≤ 4 MB) — uy vazifasi va shartnoma fayllari.
 * Kengaytma, MIME va mazmun sarlavhasi (magic bytes) mos bo'lishi shart.
 */
class FileUploadDocumentsTest extends LecItBase {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] PDF = bytes("%PDF-1.4\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n");
    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0, 6, 0, 8, 0};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};

    @Autowired FileStorageService fileStorageService;

    private User teacherUser;
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        teacherUser = newUser(UserRole.ADMIN);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String name : created) {
            Path p = fileStorageService.resolveSafePath(name);
            if (p != null) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private ResultActions upload(String name, String type, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", name, type, content))
            .with(as(teacherUser)));
    }

    private JsonNode uploadOk(String name, String type, byte[] content, String ext) throws Exception {
        JsonNode d = data(upload(name, type, content)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.originalName").value(name))
            .andReturn());
        assertThat(d.get("url").asText()).startsWith(FileStorageService.URL_PREFIX).endsWith("." + ext);
        created.add(d.get("filename").asText());
        return d;
    }

    @Test
    void documents_pdfDocDocxXlsx_accepted_andServed() throws Exception {
        JsonNode pdf = uploadOk("Mashq 1.pdf", "application/pdf", PDF, "pdf");
        uploadOk("topshiriq.docx", DOCX, ZIP, "docx");
        uploadOk("jadval.xlsx", XLSX, ZIP, "xlsx");
        uploadOk("eski.doc", "application/msword", OLE, "doc");
        uploadOk("rasm.png", "image/png", new byte[]{1, 2, 3}, "png");      // rasm avvalgidek

        mvc.perform(get(pdf.get("url").asText()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void wrongTypes_mismatchedContent_andSizeLimit_rejected() throws Exception {
        upload("virus.exe", "application/octet-stream", new byte[]{'M', 'Z'}).andExpect(status().isBadRequest());
        upload("eslatma.txt", "text/plain", bytes("salom")).andExpect(status().isBadRequest());
        upload("eski.xls", "application/vnd.ms-excel", OLE).andExpect(status().isBadRequest());
        upload("sahifa.html", "text/html", bytes("<script>")).andExpect(status().isBadRequest());
        // Kengaytma va MIME mos emas
        upload("hujjat.pdf", DOCX, PDF).andExpect(status().isBadRequest());
        // Mazmun kengaytmaga mos emas (PNG ni .pdf deb yuborish)
        upload("soxta.pdf", "application/pdf", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10})
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Fayl mazmuni .pdf formatiga mos emas"));
        upload("soxta.docx", DOCX, PDF).andExpect(status().isBadRequest());
        // 4 MB dan katta
        byte[] big = Arrays.copyOf(PDF, (int) FileStorageService.MAX_BYTES + 1);
        upload("katta.pdf", "application/pdf", big).andExpect(status().isBadRequest());
        upload("bosh.pdf", "application/pdf", new byte[0]).andExpect(status().isBadRequest());
    }

    @Test
    void homework_attachesUploadedPdf() throws Exception {
        TeacherUser t = newTeacher();
        teacherUser = t.user();
        Long group = fixtures.group(fixtures.course(600_000), GroupStatus.ACTIVE, t.teacherId());
        JsonNode file = uploadOk("1-dars.pdf", "application/pdf", PDF, "pdf");
        mvc.perform(post("/api/homework").with(as(t.user())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"1-dars\",\"groupId\":" + group + ",\"dueDate\":\"2026-09-20\","
                    + "\"attachmentUrl\":\"" + file.get("url").asText() + "\",\"attachmentName\":\""
                    + file.get("originalName").asText() + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.attachmentUrl").value(file.get("url").asText()))
            .andExpect(jsonPath("$.data.attachmentName").value("1-dars.pdf"));
    }
}
