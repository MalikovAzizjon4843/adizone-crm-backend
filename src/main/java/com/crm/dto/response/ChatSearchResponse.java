package com.crm.dto.response;

import lombok.*;

import java.util.List;

/**
 * {@code GET /api/chat/search} javobi — ikki qismli.
 *
 * <p>Suhbatlar nomi bo'yicha, xabarlar matni bo'yicha topiladi. Ikkovi
 * bitta so'rovda: foydalanuvchi qidirayotganda "Dilnoza" ismmi yoki
 * xabardagi so'zmi — buni oldindan bilmaydi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSearchResponse {

    private List<ConversationResponse> conversations;
    private List<ChatMessageResponse> messages;
}
