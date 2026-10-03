package com.crm.miniapp;

import com.crm.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Mini App chat ko'prigi (telegram-platform §11.3). App tomoni REST + polling; xodimlarga
 * {@code /topic/conversation.{id}} — servis tranzaksiyasi commit bo'lgandan KEYIN tarqatiladi (xuddi
 * {@code ChatSocketController} kabi).
 */
@RestController
@RequestMapping("/api/app/chats")
@RequiredArgsConstructor
public class AppChatController {

    private static final String CONVERSATION_TOPIC = "/topic/conversation.";

    private final AppChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping("/contacts")
    public ApiResponse<List<AppDtos.ChatContact>> contacts(@AuthenticationPrincipal AppPrincipal principal) {
        return ApiResponse.success(chatService.contacts(principal));
    }

    /** Suhbatni ochish (bor bo'lsa — o'shasi). */
    @PostMapping
    public ApiResponse<AppDtos.ChatRow> open(@AuthenticationPrincipal AppPrincipal principal,
                                             @Valid @RequestBody AppDtos.OpenChatRequest request) {
        return ApiResponse.success(chatService.open(principal, request));
    }

    @GetMapping
    public ApiResponse<List<AppDtos.ChatRow>> list(@AuthenticationPrincipal AppPrincipal principal) {
        return ApiResponse.success(chatService.list(principal));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<AppDtos.ChatMessageItem>> messages(@AuthenticationPrincipal AppPrincipal principal,
                                                               @PathVariable Long id,
                                                               @RequestParam(required = false) Long before,
                                                               @RequestParam(required = false) Integer size) {
        return ApiResponse.success(chatService.messages(principal, id, before, size));
    }

    /** Matnli xabar (JSON). */
    @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AppDtos.ChatMessageItem> sendText(@AuthenticationPrincipal AppPrincipal principal,
                                                         @PathVariable Long id,
                                                         @RequestBody AppDtos.ChatTextRequest request) {
        return ApiResponse.success(publish(chatService.send(principal, id, request.text(), null)));
    }

    /** Rasm (≤ 4MB) va ixtiyoriy izoh — multipart: {@code image}, {@code text}. */
    @PostMapping(path = "/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AppDtos.ChatMessageItem> sendImage(@AuthenticationPrincipal AppPrincipal principal,
                                                          @PathVariable Long id,
                                                          @RequestPart(name = "image", required = false) MultipartFile image,
                                                          @RequestParam(name = "text", required = false) String text) {
        return ApiResponse.success(publish(chatService.send(principal, id, text, image)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal AppPrincipal principal, @PathVariable Long id,
                                  @Valid @RequestBody AppDtos.ChatReadRequest request) {
        chatService.read(principal, id, request.messageId())
            .ifPresent(receipt -> messagingTemplate.convertAndSend(CONVERSATION_TOPIC + id, receipt));
        return ApiResponse.success(null);
    }

    private AppDtos.ChatMessageItem publish(AppChatService.Sent sent) {
        messagingTemplate.convertAndSend(CONVERSATION_TOPIC + sent.broadcast().getConversationId(), sent.broadcast());
        return sent.item();
    }
}
