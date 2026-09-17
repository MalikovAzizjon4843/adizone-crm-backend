package com.crm.dto.response;

import lombok.*;

/** Xabar yonidagi bitta biriktirma. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachmentResponse {
    private Long id;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String contentType;

    /** Faqat rasmlarda; frontend yuklashdan oldin joy ajratadi. */
    private Integer width;
    private Integer height;

    /** Faqat ovozda: uzunligi millisekundda — ijro tugmasi yonida. */
    private Integer durationMs;

    /** Faqat ovozda: to'lqin shakli, vergul bilan ajratilgan sonlar. */
    private String waveform;

    private Integer sortOrder;
}
