package com.crm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Xabarga biriktiriladigan fayl — {@code POST /api/chat/upload} javobidan
 * o'zgarishsiz ko'chiriladi.
 *
 * <p>{@code fileUrl} dan boshqa hamma narsa ko'rinish uchun: server uni
 * ishonchli deb qabul qilmaydi, faqat yozib qo'yadi. {@code fileUrl} esa
 * tekshiriladi — {@code ChatAttachmentService.requireOwnUrl}.
 */
@Data
public class ChatAttachmentRequest {

    @NotBlank(message = "{chat.attachment.url.required}")
    @Size(max = 500, message = "{chat.attachment.url.size}")
    private String fileUrl;

    @NotBlank(message = "{chat.attachment.name.required}")
    @Size(max = 255, message = "{chat.attachment.name.size}")
    private String fileName;

    private Long fileSize;

    @Size(max = 100, message = "{chat.attachment.contentType.size}")
    private String contentType;

    private Integer width;
    private Integer height;

    /** Faqat {@code audio/*} da: ovoz uzunligi millisekundda. */
    private Integer durationMs;

    /** Faqat {@code audio/*} da: to'lqin shakli, "12,45,78,...". */
    @Size(max = 500, message = "{chat.attachment.waveform.size}")
    private String waveform;
}
