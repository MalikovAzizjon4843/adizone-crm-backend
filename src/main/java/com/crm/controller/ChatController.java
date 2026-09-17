package com.crm.controller;

import com.crm.dto.request.ConversationPinRequest;
import com.crm.dto.request.DirectConversationRequest;
import com.crm.dto.request.GroupConversationRequest;
import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatSearchResponse;
import com.crm.dto.response.ChatUserResponse;
import com.crm.dto.response.ConversationResponse;
import com.crm.dto.response.ChatUploadResponse;
import com.crm.dto.response.PresenceResponse;
import com.crm.dto.response.UnreadCountResponse;
import com.crm.entity.User;
import com.crm.service.ChatAccessService;
import com.crm.service.ChatAttachmentService;
import com.crm.service.ChatPresenceService;
import com.crm.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Ichki chatning REST qismi: ro'yxatlar, tarix va suhbat ochish.
 *
 * <p>Jonli qism ({@code /app/chat.send}, {@code /app/chat.read},
 * {@code /app/chat.typing}) —
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
    private final ChatPresenceService chatPresenceService;
    private final ChatAttachmentService chatAttachmentService;

    /** Suhbatlar ro'yxati. Suhbatlar soniga bog'liq bo'lmagan 4 ta so'rov. */
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> conversations() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.listConversations(me)));
    }

    /**
     * Suhbat lentasi, yangi → eski.
     *
     * <p>{@code before} — oldingi sahifadagi eng eski xabar {@code id} si;
     * birinchi sahifada berilmaydi.
     *
     * <p>{@code around} — qidiruv natijasidan xabarga sakrash: o'sha
     * xabar va ikki tomonidagi kontekst. {@code before} bilan birga
     * berilsa 400.
     */
    @GetMapping("/conversations/{id:\\d+}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> messages(
            @PathVariable Long id,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "around", required = false) Long around,
            @RequestParam(name = "size", required = false) Integer size) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.listMessages(me, id, before, around, size)));
    }

    /**
     * Bitta suhbat ichida qidiruv.
     *
     * <p>Javobdagi {@code id} — mo'ljaldagi xabar, {@code createdAt} esa
     * uning o'rnini ko'rsatadi; frontend keyin
     * {@code ?around=<id>} bilan o'sha joyga sakraydi.
     */
    @GetMapping("/conversations/{id:\\d+}/messages/search")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> searchInConversation(
            @PathVariable Long id,
            @RequestParam(name = "q", required = false) String query) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.searchInConversation(me, id, query)));
    }

    /**
     * Umumiy qidiruv: suhbat nomlari va xabar matnlari.
     *
     * <p>{@code q} bo'sh yoki ikki belgidan qisqa bo'lsa bo'sh javob —
     * bunday so'rov bazaga umuman bormaydi.
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<ChatSearchResponse>> search(
            @RequestParam(name = "q", required = false) String query) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.search(me, query)));
    }

    /**
     * Onlayn holatning dastlabki surati — frontend ulangan zahoti
     * so'raydi, keyin {@code /topic/presence} dagi hodisalar bilan
     * yangilab boradi.
     *
     * <p>Faqat suhbatdoshlar: barcha xodimlar ro'yxati ekranda hech
     * qachon ko'rinmaydi.
     */
    @GetMapping("/presence")
    public ResponseEntity<ApiResponse<List<PresenceResponse>>> presence() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatPresenceService.peerPresence(me)));
    }

    /** DIRECT suhbat: bor bo'lsa mavjudi, yo'q bo'lsa yangisi. */
    @PostMapping("/conversations/direct")
    public ResponseEntity<ApiResponse<ConversationResponse>> direct(
            @Valid @RequestBody DirectConversationRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.getOrCreateDirect(me, request.getUserId())));
    }

    /** Guruh yaratish; yaratuvchi avtomatik a'zo bo'ladi. */
    @PostMapping("/conversations/group")
    public ResponseEntity<ApiResponse<ConversationResponse>> group(
            @Valid @RequestBody GroupConversationRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            "Guruh yaratildi", chatService.createGroup(me, request)));
    }

    /** Qadash; faqat so'ragan foydalanuvchining ro'yxatiga ta'sir qiladi. */
    @PatchMapping("/conversations/{id:\\d+}/pin")
    public ResponseEntity<ApiResponse<ConversationResponse>> pin(
            @PathVariable Long id,
            @Valid @RequestBody ConversationPinRequest request) {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            chatService.setPinned(me, id, request.getPinned())));
    }

    /** Kim bilan yozishish mumkin: faol xodimlar, o'zidan tashqari. */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<ChatUserResponse>>> users() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(chatService.listChatUsers(me)));
    }

    /**
     * Biriktirma yuklash. Fayl saqlanadi, xabar esa hali yaratilmaydi —
     * frontend javobni {@code /app/chat.send} ning {@code attachments}
     * iga qo'yadi.
     *
     * <p>Bu yerda, WebSocket'da emas: STOMP matnli protokol va fayl uni
     * base64 ga aylantirishni talab qilardi.
     *
     * <p>Ruxsat: autentifikatsiyadan o'tgan har kim. Suhbat a'zoligi bu
     * qadamda tekshirilmaydi — qaysi suhbatga ketishi hali ma'lum emas;
     * u xabar yuborishda tekshiriladi.
     *
     * <p>{@code durationMs} va {@code waveform} — faqat ovoz uchun,
     * boshqa turlarda e'tiborsiz qoldiriladi. Ikkovini ham brauzer
     * hisoblaydi: server audio oqimini ochmaydi.
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChatUploadResponse>> upload(
            @RequestParam(name = "file") MultipartFile file,
            @RequestParam(name = "durationMs", required = false) Integer durationMs,
            @RequestParam(name = "waveform", required = false) String waveform) {
        return ResponseEntity.ok(ApiResponse.success(
            "Fayl yuklandi",
            chatAttachmentService.upload(file, durationMs, waveform)));
    }

    /** Sidebar/header belgisi uchun umumiy o'qilmaganlar. */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount() {
        User me = chatAccessService.currentUserOrThrow();
        return ResponseEntity.ok(ApiResponse.success(
            UnreadCountResponse.builder().count(chatService.unreadCount(me)).build()));
    }
}
