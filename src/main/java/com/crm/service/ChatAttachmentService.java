package com.crm.service;

import com.crm.config.Messages;
import com.crm.dto.request.ChatAttachmentRequest;
import com.crm.dto.response.ChatAttachmentResponse;
import com.crm.dto.response.ChatUploadResponse;
import com.crm.entity.Message;
import com.crm.entity.MessageAttachment;
import com.crm.entity.enums.MessageType;
import com.crm.exception.BadRequestException;
import com.crm.repository.MessageAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chat biriktirmalari: yuklash, tekshirish va xabarga bog'lash.
 *
 * <p>Fayl saqlash {@link FileStorageService} da qoladi — bu yerda faqat
 * chatga xos qoidalar: qaysi turlar ruxsat etilgan, rasm o'lchamlari va
 * kelgan {@code fileUrl} haqiqatan shu backend bergan yo'lmi.
 *
 * <p>Yuklash va xabar yaratish ataylab ikki qadam. Foydalanuvchi uchta
 * rasmni ketma-ket tanlaydi, ular yuklanib bo'ladi, keyin u izoh yozib
 * bitta xabar yuboradi. Bir qadamda qilinsa, WebSocket orqali fayl
 * uzatish kerak bo'lardi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAttachmentService {

    /** Rasmdan tashqari ruxsat etilgan aniq turlar. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/zip",
        "application/x-zip-compressed",
        "text/plain"
    );

    /**
     * Ovoz uchun ruxsat etilgan turlar.
     *
     * <p>{@code image/*} dan farqli o'laroq butun {@code audio/*} ochiq
     * emas: brauzer diktofoni shu beshtadan birini beradi, qolganlari esa
     * ko'pincha ijro etilmaydigan eski formatlar.
     */
    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
        "audio/webm",
        "audio/ogg",
        "audio/mpeg",
        "audio/mp4",
        "audio/wav"
    );

    private static final String IMAGE_PREFIX = "image/";
    private static final String AUDIO_PREFIX = "audio/";
    private static final String DEFAULT_EXTENSION = ".bin";
    private static final int FILE_NAME_MAX = 255;

    private final FileStorageService fileStorageService;
    private final MessageAttachmentRepository attachmentRepository;
    private final Messages messages;

    // ── Yuklash ────────────────────────────────────────────────────────

    /**
     * Faylni diskka yozadi va uni xabarga biriktirish uchun kerakli
     * ma'lumotni qaytaradi. Xabar bu yerda yaratilmaydi.
     *
     * <p>Hajm chegarasi ikki joyda: Spring multipart sozlamasi (4MB)
     * so'rovni controllergacha yetkazmaydi, {@code FileStorageService}
     * dagi tekshiruv esa ikkinchi to'siq. Ikkovi ham 4MB.
     *
     * <p>{@code durationMs} va {@code waveform} faqat ovoz uchun.
     * Ikkovini ham brauzer hisoblaydi: server audio oqimini ochmaydi,
     * chunki buning uchun kodek kutubxonasi kerak bo'lardi. Shuning
     * uchun qiymatlar ishonchli emas — ular ko'rinish uchun, shaklidan
     * boshqasi tekshirilmaydi.
     */
    public ChatUploadResponse upload(MultipartFile file, Integer durationMs, String waveform) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(messages.get("chat.upload.empty"));
        }

        String contentType = normalizeContentType(file.getContentType());
        requireAllowedType(contentType);

        boolean isAudio = contentType.startsWith(AUDIO_PREFIX);
        Integer duration = isAudio ? requireDuration(durationMs) : null;
        String points = isAudio ? normalizeWaveform(waveform) : null;

        String originalName = safeFileName(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + extensionOf(originalName);

        String fileUrl;
        try {
            fileUrl = fileStorageService.save(file, storedName);
        } catch (IOException e) {
            log.error("Chat: fayl saqlanmadi ({}): {}", originalName, e.getMessage());
            throw new BadRequestException(messages.get("chat.upload.failed"));
        }

        int[] size = contentType.startsWith(IMAGE_PREFIX) ? imageSize(file) : null;
        return ChatUploadResponse.builder()
            .fileUrl(fileUrl)
            .fileName(originalName)
            .fileSize(file.getSize())
            .contentType(contentType)
            .width(size != null ? size[0] : null)
            .height(size != null ? size[1] : null)
            .durationMs(duration)
            .waveform(points)
            .build();
    }

    private void requireAllowedType(String contentType) {
        if (contentType.startsWith(IMAGE_PREFIX)
                || ALLOWED_TYPES.contains(contentType)
                || ALLOWED_AUDIO_TYPES.contains(contentType)) {
            return;
        }
        // audio/* bo'lsa-yu ro'yxatda bo'lmasa — sabab aniqroq aytiladi.
        if (contentType.startsWith(AUDIO_PREFIX)) {
            throw new BadRequestException(messages.get("chat.upload.audioType", contentType));
        }
        throw new BadRequestException(messages.get("chat.upload.type", contentType));
    }

    /**
     * Ovoz uzunligi: majburiy, musbat va besh daqiqadan oshmasin.
     *
     * <p>Majburiyligi ko'rinish uchun: davomiyligi noma'lum ovozli xabar
     * ekranda uzunligi yo'q chiziq bo'lib qolardi va uni ijro etmasdan
     * turib qancha vaqt olishini bilib bo'lmasdi.
     */
    private Integer requireDuration(Integer durationMs) {
        if (durationMs == null || durationMs <= 0) {
            throw new BadRequestException(messages.get("chat.voice.durationRequired"));
        }
        if (durationMs > MessageAttachment.VOICE_MAX_DURATION_MS) {
            throw new BadRequestException(messages.get("chat.voice.tooLong",
                MessageAttachment.VOICE_MAX_DURATION_MS / 60000));
        }
        return durationMs;
    }

    /**
     * To'lqin shaklini tekshiradi va tozalab qaytaradi.
     *
     * <p>Ixtiyoriy: berilmasa {@code null}, frontend tekis chiziq chizadi.
     * Berilgan bo'lsa — vergul bilan ajratilgan butun sonlar, har biri
     * {@code 0..100}, ko'pi bilan ellikta. Ortiqchasi kesib tashlanmaydi,
     * xato qaytadi: jimgina qirqish frontendda sababsiz boshqacha surat
     * beradi.
     *
     * <p>Qiymat ustunga to'g'ridan-to'g'ri yoziladi, shuning uchun
     * shaklini tekshirish shart — aks holda bu yerga istalgan 500 belgi
     * tushib, keyin chizishda xatoga aylanardi.
     */
    private String normalizeWaveform(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(",", -1);
        if (parts.length > MessageAttachment.WAVEFORM_MAX_POINTS) {
            throw new BadRequestException(messages.get("chat.voice.waveformTooLong",
                MessageAttachment.WAVEFORM_MAX_POINTS));
        }

        StringBuilder cleaned = new StringBuilder(raw.length());
        for (String part : parts) {
            int value;
            try {
                value = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException(messages.get("chat.voice.waveformInvalid"));
            }
            if (value < 0 || value > MessageAttachment.WAVEFORM_MAX_VALUE) {
                throw new BadRequestException(messages.get("chat.voice.waveformInvalid"));
            }
            if (cleaned.length() > 0) {
                cleaned.append(',');
            }
            cleaned.append(value);
        }
        return cleaned.toString();
    }

    /** Bo'sh yoki noma'lum turni ham ko'taradi — istisno tashlamaydi. */
    private static boolean isAudioType(String raw) {
        return raw != null && raw.trim().toLowerCase(Locale.ROOT).startsWith(AUDIO_PREFIX);
    }

    private String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(messages.get("chat.upload.type", "?"));
        }
        // "image/png; charset=binary" ham keladi — faqat turining o'zi kerak.
        int separator = raw.indexOf(';');
        String value = separator >= 0 ? raw.substring(0, separator) : raw;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Rasm o'lchamlari. Aniqlab bo'lmasa {@code null} — bu xato emas:
     * {@code ImageIO} SVG va ba'zi WebP variantlarini o'qiy olmaydi,
     * lekin bunday fayl ham yuborilishi kerak.
     */
    private int[] imageSize(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            return image != null ? new int[]{image.getWidth(), image.getHeight()} : null;
        } catch (IOException | RuntimeException e) {
            log.debug("Chat: rasm o'lchami aniqlanmadi: {}", e.getMessage());
            return null;
        }
    }

    /** Foydalanuvchiga ko'rinadigan nom — yo'l qismlari olib tashlanadi. */
    private String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "file";
        }
        return name.length() > FILE_NAME_MAX ? name.substring(0, FILE_NAME_MAX) : name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return DEFAULT_EXTENSION;
        }
        String extension = fileName.substring(dot);
        // Uzun "kengaytma" — aslida kengaytma emas, nuqtali nom.
        return extension.length() <= 10 ? extension.toLowerCase(Locale.ROOT) : DEFAULT_EXTENSION;
    }

    // ── Xabarga bog'lash ───────────────────────────────────────────────

    /** Saqlangan biriktirmalar va ular aniqlagan xabar turi. */
    public record Attached(MessageType type, List<ChatAttachmentResponse> attachments) {

        static Attached none() {
            return new Attached(MessageType.TEXT, List.of());
        }
    }

    /**
     * Biriktirmalarni saqlaydi; qaytishda ular va xabar turi.
     *
     * <p>Tur biriktirmalar bo'yicha aniqlanadi, so'rovdan olinmaydi:
     * mijoz "bu rasm" deb aytishi mumkin emas, aks holda faylni rasm
     * qilib ko'rsatib qo'yish oson bo'lardi.
     *
     * <p>Ovoz alohida qoidaga bo'ysunadi: ovozli xabarda biriktirma
     * BITTA bo'ladi va u boshqa fayl bilan aralashmaydi. Sababi
     * ko'rinishda — ovozli xabar ijro tugmasi va to'lqin shaklidan
     * iborat qator, uning yoniga ikkinchi faylni qo'yadigan joy yo'q.
     *
     * <p>Saqlangan qatorlar shu yerda javob shakliga o'giriladi — xabar
     * tarqatilishidan oldin ularni bazadan qayta o'qish kerak emas.
     */
    @Transactional
    public Attached attach(Message message, List<ChatAttachmentRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Attached.none();
        }
        if (requests.size() > MessageAttachment.MAX_PER_MESSAGE) {
            throw new BadRequestException(messages.get("chat.attachment.tooMany",
                MessageAttachment.MAX_PER_MESSAGE));
        }

        // Ovoz qoidasi hammasidan oldin: bitta ham qator yozilmasidan
        // turib rad etilsin, aks holda rad etish rollback'ga tayanardi.
        boolean anyAudio = requests.stream()
            .anyMatch(request -> isAudioType(request.getContentType()));
        if (anyAudio && requests.size() > 1) {
            throw new BadRequestException(messages.get("chat.voice.single"));
        }

        boolean allImages = true;
        int order = 0;
        List<ChatAttachmentResponse> saved = new ArrayList<>(requests.size());
        for (ChatAttachmentRequest request : requests) {
            requireOwnUrl(request.getFileUrl());
            String contentType = request.getContentType() == null
                ? null
                : normalizeContentType(request.getContentType());

            boolean isAudio = contentType != null && contentType.startsWith(AUDIO_PREFIX);
            if (contentType == null || !contentType.startsWith(IMAGE_PREFIX)) {
                allImages = false;
            }

            saved.add(toResponse(attachmentRepository.save(MessageAttachment.builder()
                .message(message)
                .fileUrl(request.getFileUrl())
                .fileName(safeFileName(request.getFileName()))
                .fileSize(request.getFileSize())
                .contentType(contentType)
                .width(request.getWidth())
                .height(request.getHeight())
                // Chegaralar yuklashdagidek: bu qiymatlar mijozdan qayta
                // keladi, ya'ni yo'lda o'zgargan bo'lishi mumkin.
                .durationMs(isAudio ? requireDuration(request.getDurationMs()) : null)
                .waveform(isAudio ? normalizeWaveform(request.getWaveform()) : null)
                .sortOrder(order++)
                .build())));
        }

        if (anyAudio) {
            return new Attached(MessageType.VOICE, saved);
        }
        return new Attached(allImages ? MessageType.IMAGE : MessageType.FILE, saved);
    }

    /**
     * {@code fileUrl} shu backend bergan yo'lmi.
     *
     * <p>Uch shart: {@code /api/files/} bilan boshlanadi, undan keyin
     * bitta bo'lak keladi (ya'ni {@code ../} yoki qo'shimcha katalog
     * yo'q) va o'sha nomdagi fayl yuklash katalogida haqiqatan bor.
     *
     * <p>Oxirgi shart eng muhimi: usiz mijoz o'zi o'ylab topgan yo'lni
     * yuborib, xabarni tashqi manzilga ko'rsatuvchi qilib qo'yishi yoki
     * hali mavjud bo'lmagan faylga ishora qoldirishi mumkin edi.
     */
    public void requireOwnUrl(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(FileStorageService.URL_PREFIX)) {
            throw new BadRequestException(messages.get("chat.attachment.foreignUrl"));
        }
        String fileName = fileUrl.substring(FileStorageService.URL_PREFIX.length());
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new BadRequestException(messages.get("chat.attachment.foreignUrl"));
        }
        try {
            Path path = fileStorageService.resolveSafePath(fileName);
            if (path == null || !Files.exists(path)) {
                throw new BadRequestException(messages.get("chat.attachment.missing"));
            }
        } catch (IOException e) {
            throw new BadRequestException(messages.get("chat.attachment.missing"));
        }
    }

    // ── O'qish ─────────────────────────────────────────────────────────

    /** Bir sahifadagi xabarlarning biriktirmalari — bitta so'rov. */
    @Transactional(readOnly = true)
    public Map<Long, List<ChatAttachmentResponse>> byMessageIds(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ChatAttachmentResponse>> byMessage = new HashMap<>();
        for (MessageAttachment attachment : attachmentRepository.findByMessageIds(messageIds)) {
            byMessage
                .computeIfAbsent(attachment.getMessage().getId(), key -> new ArrayList<>())
                .add(toResponse(attachment));
        }
        return byMessage;
    }

    public ChatAttachmentResponse toResponse(MessageAttachment attachment) {
        return ChatAttachmentResponse.builder()
            .id(attachment.getId())
            .fileUrl(attachment.getFileUrl())
            .fileName(attachment.getFileName())
            .fileSize(attachment.getFileSize())
            .contentType(attachment.getContentType())
            .width(attachment.getWidth())
            .height(attachment.getHeight())
            .durationMs(attachment.getDurationMs())
            .waveform(attachment.getWaveform())
            .sortOrder(attachment.getSortOrder())
            .build();
    }
}
