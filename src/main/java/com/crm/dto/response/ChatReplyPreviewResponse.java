package com.crm.dto.response;

import com.crm.entity.enums.MessageType;
import lombok.*;

/**
 * Javob berilgan xabarning qisqa ko'rinishi — lentada iqtibos bo'lib
 * chiziladi.
 *
 * <p>To'liq xabar emas: sahifada yigirmata javob bo'lsa, yigirmata to'liq
 * xabarni takrorlash ortiqcha. Foydalanuvchi iqtibosga bosganda
 * {@code ?around=} bilan asl xabarga sakraydi.
 *
 * <p>Asl xabar o'chirilgan bo'lsa {@code text} null bo'ladi — kim javob
 * berganini ko'rsatish uchun sarlavha baribir qoladi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReplyPreviewResponse {
    private Long id;
    private String senderName;
    private String text;
    private MessageType type;
}
