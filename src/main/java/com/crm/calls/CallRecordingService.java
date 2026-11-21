package com.crm.calls;

import com.crm.entity.CallRecording;
import com.crm.entity.PhoneCall;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.CallRecordingRepository;
import com.crm.repository.PhoneCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Qo'ng'iroq yozuvlari (docs/design/calls.md §4.3, §7.3, §10).
 *
 * <ul>
 *   <li>Yuklash: multipart, ≤ {@code app.calls.recording-max-bytes}, faqat audio. Fayl oqim bilan diskka yoziladi va
 *       yo'l-yo'lakay SHA-256 hisoblanadi — ilova bergan {@code sha256} bilan mos kelmasa fayl o'chiriladi (400).
 *       Takror yuklash (o'sha hash) — 200, {@code duplicate = true}, diskka yozilmaydi; boshqa hash — 409.</li>
 *   <li>Joy: {@code app.calls.recording-dir/yyyy/MM/<callId>-<uuid>.<ext>} — ochiq {@code GET /api/files/**} pastki
 *       katalogni bermaydi.</li>
 *   <li>Tinglash: {@code GET /api/calls/{id}/recording} (JWT, Range — Spring {@code ResourceRegion}) yoki
 *       {@code <audio src>} uchun imzolangan qisqa havola ({@code /recording/link} → {@code /api/calls/recordings/stream}).</li>
 *   <li>Retention: qo'ng'iroqdan {@code recording-retention-days} o'tgach fayl o'chadi, metama'lumot qoladi.</li>
 * </ul>
 */
@Service
@Slf4j
public class CallRecordingService {

    static final String STREAM_PATH = "/api/calls/recordings/stream";

    private static final Map<String, String> EXT_MIME = Map.ofEntries(
        Map.entry("m4a", "audio/mp4"), Map.entry("mp4", "audio/mp4"), Map.entry("aac", "audio/aac"),
        Map.entry("mp3", "audio/mpeg"), Map.entry("amr", "audio/amr"), Map.entry("awb", "audio/amr-wb"),
        Map.entry("3gp", "audio/3gpp"), Map.entry("3gpp", "audio/3gpp"), Map.entry("ogg", "audio/ogg"),
        Map.entry("oga", "audio/ogg"), Map.entry("opus", "audio/opus"), Map.entry("wav", "audio/wav"),
        Map.entry("webm", "audio/webm"));
    private static final Map<String, String> MIME_EXT = Map.ofEntries(
        Map.entry("audio/mp4", "m4a"), Map.entry("audio/m4a", "m4a"), Map.entry("audio/x-m4a", "m4a"),
        Map.entry("audio/aac", "aac"), Map.entry("audio/mpeg", "mp3"), Map.entry("audio/mp3", "mp3"),
        Map.entry("audio/amr", "amr"), Map.entry("audio/amr-wb", "awb"), Map.entry("audio/3gpp", "3gp"),
        Map.entry("audio/ogg", "ogg"), Map.entry("audio/opus", "opus"), Map.entry("audio/wav", "wav"),
        Map.entry("audio/x-wav", "wav"), Map.entry("audio/webm", "webm"));

    private final PhoneCallRepository callRepository;
    private final CallRecordingRepository recordingRepository;
    private final CallQueryService queryService;
    private final CallsProperties properties;
    private final TransactionTemplate tx;
    private final Clock billingClock;
    private final byte[] linkKey;

    public CallRecordingService(PhoneCallRepository callRepository, CallRecordingRepository recordingRepository,
                                CallQueryService queryService, CallsProperties properties, TransactionTemplate tx,
                                Clock billingClock, @Value(value = "${jwt.secret}") String jwtSecret) {
        this.callRepository = callRepository;
        this.recordingRepository = recordingRepository;
        this.queryService = queryService;
        this.properties = properties;
        this.tx = tx;
        this.billingClock = billingClock;
        // Admin JWT kaliti bilan aralashmasin — alohida maqsadli kalit
        this.linkKey = sha256Bytes("calls-recording-link:" + jwtSecret);
    }

    // ── Yuklash (ilova) ──────────────────────────────────────────────────

    public CallDtos.RecordingInfo upload(CallDevicePrincipal principal, Long callId, MultipartFile file,
                                         String sha256, Integer durationSec) {
        PhoneCall call = callRepository.findById(callId)
            .filter(c -> c.getUserId().equals(principal.userId()))
            .orElseThrow(() -> CodedException.notFound("calls.notFound"));
        String expected = sha256 == null ? null : sha256.trim().toLowerCase(Locale.ROOT);
        if (expected == null || !expected.matches("[0-9a-f]{64}")) {
            throw CodedException.badRequest("calls.recording.sha256Invalid");
        }
        if (file == null || file.isEmpty()) {
            throw CodedException.badRequest("calls.recording.empty");
        }
        long max = properties.getRecordingMaxBytes();
        if (file.getSize() > max) {
            throw CodedException.badRequest("calls.recording.tooLarge", max / (1024 * 1024));
        }
        String mime = resolveMime(file.getContentType(), file.getOriginalFilename());
        if (mime == null) {
            throw CodedException.badRequest("calls.recording.type");
        }
        if (durationSec != null && (durationSec < 0 || durationSec > 86_400)) {
            throw CodedException.badRequest("calls.recording.duration");
        }

        CallRecording existing = recordingRepository.findByCallId(callId).orElse(null);
        if (existing != null) {
            return sameOrConflict(existing, expected);
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        int retention = properties.getRecordingRetentionDays();
        if (retention > 0 && call.getStartedAt().isBefore(now.minusDays(retention))) {
            throw CodedException.badRequest("calls.recording.tooOld");
        }

        Path root = root();
        String relative = String.format(Locale.ROOT, "%04d/%02d/%d-%s.%s", now.getYear(), now.getMonthValue(),
            callId, UUID.randomUUID().toString().substring(0, 8), MIME_EXT.getOrDefault(mime, "bin"));
        Path target = root.resolve(relative).normalize();
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        long size;
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            size = copyHashing(file, temp, digest, max);
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expected)) {
                Files.deleteIfExists(temp);
                throw CodedException.badRequest("calls.recording.sha256Mismatch");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | NoSuchAlgorithmException e) {
            deleteQuietly(temp);
            log.error("Qo'ng'iroq #{} yozuvi saqlanmadi: {}", callId, e.getMessage());
            throw new CodedException(HttpStatus.INTERNAL_SERVER_ERROR, "calls.recording.storageError");
        }

        try {
            CallRecording saved = tx.execute(s -> recordingRepository.saveAndFlush(CallRecording.builder()
                .callId(callId)
                .path(relative.replace('\\', '/'))
                .mime(mime)
                .sizeBytes(size)
                .durationSec(durationSec)
                .sha256(expected)
                .uploadedAt(now)
                .build()));
            return info(saved, false);
        } catch (DataIntegrityViolationException e) {
            // Parallel yuklash yutdi — bizning faylimiz ortiqcha
            deleteQuietly(target);
            CallRecording other = recordingRepository.findByCallId(callId).orElseThrow(() -> e);
            return sameOrConflict(other, expected);
        }
    }

    private CallDtos.RecordingInfo sameOrConflict(CallRecording existing, String sha256) {
        if (existing.getSha256().equals(sha256)) {
            return info(existing, true);
        }
        throw new ConflictException(existing.isAvailable() ? "calls.recording.exists" : "calls.recording.deleted");
    }

    private static long copyHashing(MultipartFile file, Path temp, MessageDigest digest, long max) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw CodedException.badRequest("calls.recording.tooLarge", max / (1024 * 1024));
                }
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (CodedException e) {
            deleteQuietly(temp);
            throw e;
        }
        return total;
    }

    // ── Tinglash (CRM) ───────────────────────────────────────────────────

    public record Playable(Resource resource, String mime, long size, String filename) {
    }

    /** JWT bilan: ruxsat — {@link CallQueryService#requireVisible}. */
    public Playable playable(Long callId) {
        PhoneCall call = queryService.requireVisible(callId);
        return playableOf(call.getId());
    }

    public CallDtos.RecordingLink link(Long callId) {
        PhoneCall call = queryService.requireVisible(callId);
        playableOf(call.getId());
        LocalDateTime expiresAt = LocalDateTime.now(billingClock).withNano(0)
            .plusSeconds(properties.getRecordingLinkTtlSeconds());
        long exp = expiresAt.atZone(billingClock.getZone()).toEpochSecond();
        String url = STREAM_PATH + "?callId=" + call.getId() + "&exp=" + exp + "&sig=" + sign(call.getId(), exp);
        return new CallDtos.RecordingLink(url, expiresAt);
    }

    /** Imzolangan havola — tokensiz ({@code <audio src>}); muddat va HMAC tekshiriladi. */
    public Playable playableSigned(Long callId, long exp, String sig) {
        long nowEpoch = billingClock.instant().getEpochSecond();
        if (callId == null || sig == null || exp < nowEpoch
                || !MessageDigest.isEqual(sign(callId, exp).getBytes(StandardCharsets.US_ASCII),
                    sig.getBytes(StandardCharsets.US_ASCII))) {
            throw CodedException.forbidden("calls.recording.linkInvalid");
        }
        return playableOf(callId);
    }

    private Playable playableOf(Long callId) {
        CallRecording r = recordingRepository.findByCallId(callId)
            .orElseThrow(() -> CodedException.notFound("calls.recording.notFound"));
        if (!r.isAvailable()) {
            throw new CodedException(HttpStatus.GONE, "calls.recording.deleted");
        }
        Path root = root();
        Path file = root.resolve(r.getPath()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            log.warn("Qo'ng'iroq #{} yozuvi diskda yo'q: {}", callId, r.getPath());
            throw CodedException.notFound("calls.recording.notFound");
        }
        return new Playable(new FileSystemResource(file), r.getMime(), r.getSizeBytes(),
            "call-" + callId + "." + MIME_EXT.getOrDefault(r.getMime(), "bin"));
    }

    // ── Retention ────────────────────────────────────────────────────────

    /** Muddati o'tgan yozuv fayllarini o'chiradi; qaytaradi — nechta. */
    public int purgeExpired() {
        int days = properties.getRecordingRetentionDays();
        if (days <= 0) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDateTime before = now.minusDays(days);
        Path root = root();
        int total = 0;
        while (true) {
            List<CallRecording> batch = recordingRepository.findExpired(before, PageRequest.of(0, 200));
            if (batch.isEmpty()) {
                return total;
            }
            for (CallRecording r : batch) {
                if (r.getPath() != null) {
                    Path file = root.resolve(r.getPath()).normalize();
                    if (file.startsWith(root)) {
                        deleteQuietly(file);
                    }
                }
                tx.executeWithoutResult(s -> recordingRepository.findById(r.getId()).ifPresent(x -> {
                    x.setPath(null);
                    x.setDeletedAt(now);
                    recordingRepository.save(x);
                }));
                total++;
            }
        }
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private Path root() {
        return Paths.get(properties.getRecordingDir()).toAbsolutePath().normalize();
    }

    static String resolveMime(String contentType, String filename) {
        String ct = contentType == null ? null : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (ct != null && MIME_EXT.containsKey(ct)) {
            return "audio/x-m4a".equals(ct) || "audio/m4a".equals(ct) ? "audio/mp4"
                : "audio/mp3".equals(ct) ? "audio/mpeg"
                : "audio/x-wav".equals(ct) ? "audio/wav" : ct;
        }
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (ct == null || ct.equals("application/octet-stream") || ct.startsWith("audio/") || ct.equals("video/3gpp")
                    || ct.equals("video/mp4")) {
                return EXT_MIME.get(ext);
            }
        }
        return null;
    }

    private String sign(Long callId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(linkKey, "HmacSHA256"));
            byte[] h = mac.doFinal((callId + ":" + exp).getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CallDtos.RecordingInfo info(CallRecording r, boolean duplicate) {
        return new CallDtos.RecordingInfo(r.getCallId(), r.getMime(), r.getSizeBytes(), r.getDurationSec(),
            r.getSha256(), r.getUploadedAt(), duplicate);
    }

    private static byte[] sha256Bytes(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Fayl o'chirilmadi: {} — {}", p, e.getMessage());
        }
    }
}
