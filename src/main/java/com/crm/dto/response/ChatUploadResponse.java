package com.crm.dto.response;

import lombok.*;

/**
 * {@code POST /api/chat/upload} javobi.
 *
 * <p>Fayl saqlanadi, xabar esa hali yaratilmaydi — foydalanuvchi bir
 * nechta faylni ketma-ket yuklab, keyin hammasini bitta xabar qilib
 * yuborishi mumkin. Frontend shu javobni o'zgarishsiz
 * {@code /app/chat.send} ning {@code attachments} iga qo'yadi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUploadResponse {
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String contentType;

    /** Rasm bo'lmasa null. */
    private Integer width;
    private Integer height;

    /**
     * Ovoz bo'lmasa null. Ikkovi ham so'rovdan kelgan holicha
     * qaytariladi — frontend javobni o'zgarishsiz
     * {@code /app/chat.send} ga uzatadi.
     */
    private Integer durationMs;
    private String waveform;
}
