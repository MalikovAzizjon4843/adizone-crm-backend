package com.crm.controller;

import com.crm.dto.request.ConversationPinRequest;
import com.crm.dto.request.DirectConversationRequest;
import com.crm.dto.request.GroupConversationRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatUserResponse;
import com.crm.dto.response.ConversationResponse;
import com.crm.dto.response.UnreadCountResponse;
import com.crm.entity.User;
import com.crm.service.ChatAccessService;
import com.crm.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ichki chatning REST qismi: ro'yxatlar, tarix va suhbat ochish.
 *
 * <p>Jonli qism ({@code /app/chat.send}, {@code /app/chat.read}) —
 * {@link ChatSocketController} da. Bu yerda faqat sahifa ochilganda yoki
 * ulanish uzilganda kerak bo'ladigan narsalar.
 *
 * <p>Rol cheklovi yo'q — chat barcha xodimlar uchun. Suhbatga kirish
 * huquqi a'zolik bo'yicha {@link ChatAccessService} da tekshiriladi.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatAccessService chatAccessService;

    /** TASK 6 — suhbatlar ro'yxati. Suhbatlar soniga bog'liq bo'lmagan 4 ta so'rov. */
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> conversations() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.listConversations(me)));
    }

    /**
     * TASK 6 — suhbat lentasi, yangi → eski.
     *
     * <p>{@code before} — oldingi sahifadagi eng eski xabar {@code id} si;
     * birinchi sahifada berilmaydi.
     */
    @GetMapping("/conversations/{id:\\d+}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> messages(
            @PathVariable Long id,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "size", required = false) Integer size) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.listMessages(me, id, before, size)));
    }

    /** TASK 6 — DIRECT suhbat: bor bo'lsa mavjudi, yo'q bo'lsa yangisi. */
    @PostMapping("/conversations/direct")
    public ResponseEntity<ApiResponse<ConversationResponse>> direct(
            @Valid @RequestBody DirectConversationRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.getOrCreateDirect(me, request.getUserId())));
    }

    /** TASK 6 — guruh yaratish; yaratuvchi avtomatik a'zo bo'ladi. */
    @PostMapping("/conversations/group")
    public ResponseEntity<ApiResponse<ConversationResponse>> group(
            @Valid @RequestBody GroupConversationRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            "Guruh yaratildi", chatService.createGroup(me, request)));
    }

    /** TASK 6 — qadash; faqat so'ragan foydalanuvchining ro'yxatiga ta'sir qiladi. */
    @PatchMapping("/conversations/{id:\\d+}/pin")
    public ResponseEntity<ApiResponse<ConversationResponse>> pin(
            @PathVariable Long id,
            @Valid @RequestBody ConversationPinRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.setPinned(me, id, request.getPinned())));
    }

    /** TASK 6 — kim bilan yozishish mumkin: faol xodimlar, o'zidan tashqari. */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<ChatUserResponse>>> users() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.listChatUsers(me)));
    }

    /** TASK 7 — sidebar/header belgisi uchun umumiy o'qilmaganlar. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            UnreadCountResponse.builder().count(chatService.unreadCount(me)).build()));
    }
}
