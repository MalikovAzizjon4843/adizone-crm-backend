package com.crm.calls;

import com.crm.entity.CallRecording;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.CallRecordingRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiruvchi qo'ng'iroq kartasi (ruxsatlar, qarz summasi yashirin) va yozuvlar (sha256, takror, Range, imzolangan havola,
 * retention) — docs/design/calls.md §4.3–§4.4, §7.3, §10.
 */
class CallLookupRecordingTest extends CallsItBase {

    @Autowired private CallRecordingRepository recordingRepository;
    @Autowired private CallRecordingService recordingService;

    // ── Lookup ───────────────────────────────────────────────────────────

    @Test
    void lookup_student_debtAmountOnlyForAdmins() throws Exception {
        String studentPhone = phone();
        Long studentId = student("Nodira", studentPhone);
        Long group = fixtures.group(fixtures.course(700_000));
        Long sg = fixtures.enrollment(studentId, group).start(clock.today().minusDays(40)).save();
        jdbc.update("UPDATE student_groups SET balance = ?, debt_since = ? WHERE id = ?", -300_000,
            clock.today().minusDays(12), sg);

        String smToken = register(staff(UserRole.SALES_MANAGER));
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", studentPhone.substring(4)), smToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("STUDENT"))
            .andExpect(jsonPath("$.data.name").value("Nodira Karimov"))
            .andExpect(jsonPath("$.data.student.id").value(studentId))
            .andExpect(jsonPath("$.data.student.groups.length()").value(1))
            .andExpect(jsonPath("$.data.student.debtor").value(true))
            .andExpect(jsonPath("$.data.student.debtDays").value(12))
            .andExpect(jsonPath("$.data.student.debtAmount").doesNotExist())
            .andExpect(jsonPath("$.data.crmPath").value("/students/" + studentId));

        String adminToken = register(staff(UserRole.ADMIN));
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", studentPhone), adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.student.debtAmount").value(300000));
    }

    @Test
    void lookup_lead_restrictedForOtherManagers_andLastCallNote() throws Exception {
        User owner = staff(UserRole.SALES_MANAGER);
        User otherSm = staff(UserRole.SALES_MANAGER);
        String leadPhone = phone();
        Long leadId = lead("Bobur Lid", leadPhone, owner);

        String ownerToken = register(owner);
        String body = syncOk(ownerToken, item("x1", "OUT", leadPhone, 15, 90));
        jdbc.update("UPDATE calls SET note = ?, result_code = ?, result_at = ? WHERE id = ?", "Ertaga keladi",
            "THINKING", java.time.LocalDateTime.now(clock), callId(body, 0));

        mvc.perform(device(get("/api/calls/app/lookup").param("phone", leadPhone), ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("LEAD"))
            .andExpect(jsonPath("$.data.restricted").value(false))
            .andExpect(jsonPath("$.data.lead.id").value(leadId))
            .andExpect(jsonPath("$.data.lead.stageCode").value("NEW"))
            .andExpect(jsonPath("$.data.lastCall.note").value("Ertaga keladi"))
            .andExpect(jsonPath("$.data.crmPath").value("/leads?lead=" + leadId));

        mvc.perform(device(get("/api/calls/app/lookup").param("phone", leadPhone), register(otherSm)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("LEAD"))
            .andExpect(jsonPath("$.data.restricted").value(true))
            .andExpect(jsonPath("$.data.name").value("Bobur Lid"))
            .andExpect(jsonPath("$.data.lead.id").doesNotExist())
            .andExpect(jsonPath("$.data.lead.owner.id").value(owner.getId()))
            .andExpect(jsonPath("$.data.lastCall").doesNotExist());

        mvc.perform(device(get("/api/calls/app/lookup").param("phone", leadPhone), register(staff(UserRole.SALES_HEAD))))
            .andExpect(jsonPath("$.data.restricted").value(false))
            .andExpect(jsonPath("$.data.lead.id").value(leadId));
    }

    @Test
    void lookup_parentAndUnknown() throws Exception {
        String parentPhone = phone();
        Long child = inTx(() -> studentRepository.save(com.crm.entity.Student.builder()
            .firstName("Laylo").lastName("Ergasheva").phone(phone()).parentPhone(parentPhone)
            .status(com.crm.entity.enums.StudentStatus.ACTIVE).build()).getId());
        String token = register(staff(UserRole.SALES_HEAD));
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", parentPhone), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("PARENT"))
            .andExpect(jsonPath("$.data.children[0].id").value(child));
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", phone()), token))
            .andExpect(jsonPath("$.data.type").value("UNKNOWN"));
        mvc.perform(device(get("/api/calls/app/lookup").param("phone", "x"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.phone.invalid"));
    }

    // ── Yozuvlar ─────────────────────────────────────────────────────────

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, Long callId, byte[] data,
                                                                      String sha) throws Exception {
        return mvc.perform(device(multipart("/api/calls/app/calls/" + callId + "/recording")
                .file(new MockMultipartFile("file", "rec.m4a", "audio/mp4", data))
                .param("sha256", sha)
                .param("durationSec", "42"), token));
    }

    @Test
    void recording_sha256_idempotent_conflict_rangeAndPermissions() throws Exception {
        User sm = staff(UserRole.SALES_MANAGER);
        String token = register(sm);
        Long callId = callId(syncOk(token, item("r1", "OUT", phone(), 20, 42)), 0);
        byte[] audio = "FAKE-M4A-AUDIO-0123456789".getBytes(StandardCharsets.UTF_8);

        upload(token, callId, audio, sha(new byte[]{1, 2, 3}))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("calls.recording.sha256Mismatch"));
        assertThat(count("call_recordings")).isZero();

        upload(token, callId, audio, sha(audio).toUpperCase())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.duplicate").value(false))
            .andExpect(jsonPath("$.data.sizeBytes").value(audio.length))
            .andExpect(jsonPath("$.data.mime").value("audio/mp4"));
        CallRecording rec = inTx(() -> recordingRepository.findByCallId(callId).orElseThrow());
        Path file = Paths.get(callsProperties.getRecordingDir()).toAbsolutePath().resolve(rec.getPath());
        assertThat(Files.readAllBytes(file)).isEqualTo(audio);

        // Takror — o'sha hash: 200, duplicate; boshqa fayl — 409
        upload(token, callId, audio, sha(audio)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.duplicate").value(true));
        byte[] other = "OTHER".getBytes(StandardCharsets.UTF_8);
        upload(token, callId, other, sha(other)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("calls.recording.exists"));
        assertThat(count("call_recordings")).isEqualTo(1);

        // Begona qo'ng'iroq — 404; audio bo'lmagan fayl — 400
        upload(register(staff(UserRole.SALES_MANAGER)), callId, audio, sha(audio)).andExpect(status().isNotFound());
        mvc.perform(device(multipart("/api/calls/app/calls/" + callId + "/recording")
                .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", audio))
                .param("sha256", sha(audio)), token))
            .andExpect(status().isBadRequest());

        // CRM: Range → 206
        mvc.perform(get("/api/calls/" + callId + "/recording").with(as(sm)).header(HttpHeaders.RANGE, "bytes=0-3"))
            .andExpect(status().isPartialContent())
            .andExpect(content().bytes("FAKE".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/calls/" + callId + "/recording").with(as(staff(UserRole.ADMIN))))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(content().bytes(audio));
        mvc.perform(get("/api/calls/" + callId + "/recording").with(as(staff(UserRole.SALES_MANAGER))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/calls/" + callId + "/recording").with(as(staff(UserRole.TEACHER))))
            .andExpect(status().isForbidden());

        // Imzolangan havola — tokensiz
        String link = JsonPath.read(mvc.perform(get("/api/calls/" + callId + "/recording/link").with(as(sm)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.url");
        mvc.perform(get(link)).andExpect(status().isOk()).andExpect(content().bytes(audio));
        mvc.perform(get(link.replaceAll("sig=[^&]+", "sig=forged"))).andExpect(status().isForbidden());
    }

    @Test
    void retention_deletesFile_keepsMetadata() throws Exception {
        String token = register(staff(UserRole.SALES_HEAD));
        Long callId = callId(syncOk(token, item("old1", "IN", phone(), 60, 30)), 0);
        byte[] audio = "RETENTION".getBytes(StandardCharsets.UTF_8);
        upload(token, callId, audio, sha(audio)).andExpect(status().isOk());
        CallRecording rec = inTx(() -> recordingRepository.findByCallId(callId).orElseThrow());
        Path file = Paths.get(callsProperties.getRecordingDir()).toAbsolutePath().resolve(rec.getPath());
        assertThat(file).exists();

        clock.setDate(LocalDate.of(2026, 9, 15).plusDays(179));
        assertThat(recordingService.purgeExpired()).isZero();
        clock.setDate(LocalDate.of(2026, 9, 15).plusDays(181));
        assertThat(recordingService.purgeExpired()).isEqualTo(1);

        assertThat(file).doesNotExist();
        CallRecording after = inTx(() -> recordingRepository.findByCallId(callId).orElseThrow());
        assertThat(after.getPath()).isNull();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getSha256()).isEqualTo(rec.getSha256());
        mvc.perform(get("/api/calls/" + callId + "/recording").with(as(staff(UserRole.SUPER_ADMIN))))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("calls.recording.deleted"));
        // Muddati o'tgan qo'ng'iroqqa yangi yozuv qabul qilinmaydi (takror hash — baribir duplicate)
        upload(token, callId, audio, sha(audio)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.duplicate").value(true));
    }
}
