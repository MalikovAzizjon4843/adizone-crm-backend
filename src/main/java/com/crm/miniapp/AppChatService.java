package com.crm.miniapp;

import com.crm.dto.request.ChatAttachmentRequest;
import com.crm.dto.request.ChatReadRequest;
import com.crm.dto.request.ChatSendRequest;
import com.crm.dto.response.ChatAttachmentResponse;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatReadReceiptResponse;
import com.crm.dto.response.ChatUploadResponse;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.Conversation;
import com.crm.entity.ConversationParticipant;
import com.crm.entity.Message;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.ConversationType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.ConversationParticipantRepository;
import com.crm.repository.ConversationRepository;
import com.crm.repository.MessageRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.ChatAttachmentService;
import com.crm.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chat ko'prigi — Mini App tomoni (docs/design/telegram-platform.md §11.3).
 *
 * <p>Ikki tomon:
 * <ul>
 *   <li><b>CLIENT</b> — o'quvchi/ota-ona: suhbat egasi ({@code conversations.external_identity_id});</li>
 *   <li><b>STAFF</b> — o'qituvchi rejimidagi identity: suhbatning xodim ishtirokchisi, xodim sifatida
 *       {@link ChatService#send} orqali yozadi (CH-01 a'zolik qoidasi — o'sha).</li>
 * </ul>
 * Begona suhbat — 403 {@code app.chat.forbidden}.
 */
@Service
@RequiredArgsConstructor
public class AppChatService {

    public static final String TARGET_TEACHER = "TEACHER";
    public static final String TARGET_SUPPORT = "SUPPORT";
    public static final String TARGET_DIRECTOR = "DIRECTOR";
    static final Set<UserRole> SUPPORT_ROLES = EnumSet.of(UserRole.ADMIN, UserRole.SALES_HEAD, UserRole.SALES_MANAGER);
    static final Set<UserRole> DIRECTOR_ROLES = EnumSet.of(UserRole.SUPER_ADMIN);
    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 100;

    private final MiniAppAuthService authService;
    private final MiniAppQueryService queryService;
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final ChatService chatService;
    private final ChatAttachmentService chatAttachmentService;
    private final AppChatPushService pushService;
    private final Clock billingClock;

    /** Yuborish natijasi: app javobi va xodimlarga ({@code /topic/conversation.{id}}) tarqatiladigan hodisa. */
    public record Sent(AppDtos.ChatMessageItem item, ChatMessageResponse broadcast) {
    }

    private enum Side { CLIENT, STAFF }

    private record Access(Conversation conversation, Side side, AppIdentity identity, User staffUser) {
    }

    // ── Kontaktlar va suhbat ochish ──────────────────────────────────────

    /** O'z o'qituvchilari (identity o'quvchilarining ochiq guruhlari), "Menejer" va direktor. */
    @Transactional(readOnly = true)
    public List<AppDtos.ChatContact> contacts(AppPrincipal principal) {
        List<AppDtos.ChatContact> out = new ArrayList<>();
        teachersOf(principal).forEach((user, groups) -> out.add(new AppDtos.ChatContact(TARGET_TEACHER, user.getId(),
            fullName(user), "ustoz", List.copyOf(groups))));
        out.add(new AppDtos.ChatContact(TARGET_SUPPORT, null, "Menejer", "menejer", List.of()));
        out.add(new AppDtos.ChatContact(TARGET_DIRECTOR, null, "Direktor", "direktor", List.of()));
        return out;
    }

    /** Bir identity + manzil uchun bitta suhbat: bor bo'lsa — o'shasi, yo'q bo'lsa yaratiladi. */
    @Transactional
    public AppDtos.ChatRow open(AppPrincipal principal, AppDtos.OpenChatRequest request) {
        String target = request.target().trim().toUpperCase(Locale.ROOT);
        Long staffUserId = null;
        switch (target) {
            case TARGET_TEACHER -> {
                Long wanted = request.teacherUserId();
                staffUserId = teachersOf(principal).keySet().stream().map(User::getId)
                    .filter(id -> id.equals(wanted)).findFirst()
                    .orElseThrow(() -> CodedException.forbidden("app.chat.forbidden"));
            }
            case TARGET_SUPPORT, TARGET_DIRECTOR -> {
                // umumiy navbat — xodim tanlanmaydi
            }
            default -> throw CodedException.badRequest("app.chat.targetInvalid", request.target());
        }
        AppIdentity identity = authService.identity(principal);
        String key = Conversation.externalKeyOf(identity.getId(), target, staffUserId);
        Long finalStaffUserId = staffUserId;
        Conversation conversation = conversationRepository.findByExternalKey(key).orElseGet(() ->
            conversationRepository.save(Conversation.builder()
                .type(ConversationType.EXTERNAL)
                .title(clientTitle(identity))
                .externalIdentityId(identity.getId())
                .externalTarget(target)
                .externalStaffUserId(finalStaffUserId)
                .externalKey(key)
                .status("OPEN")
                .build()));
        syncParticipants(conversation);
        return clientRow(conversation, identity);
    }

    // ── Ro'yxat ──────────────────────────────────────────────────────────

    /** CLIENT suhbatlari va (o'qituvchi rejimida) xodim sifatidagi EXTERNAL suhbatlar — oxirgi xabar bo'yicha. */
    @Transactional(readOnly = true)
    public List<AppDtos.ChatRow> list(AppPrincipal principal) {
        AppIdentity identity = authService.identity(principal);
        List<AppDtos.ChatRow> rows = new ArrayList<>();
        for (Conversation c : conversationRepository.findByExternalIdentityIdOrderByLastMessageAtDescIdDesc(identity.getId())) {
            rows.add(clientRow(c, identity));
        }
        if (principal.isTeacher()) {
            for (ConversationParticipant p : participantRepository.findActiveForUserByType(principal.staffUserId(),
                    ConversationType.EXTERNAL)) {
                rows.add(staffRow(p));
            }
        }
        rows.sort(Comparator.comparing(AppDtos.ChatRow::lastMessageAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AppDtos.ChatRow::id, Comparator.reverseOrder()));
        return rows;
    }

    // ── Xabarlar ─────────────────────────────────────────────────────────

    /** Yangi → eski, kursor {@code before} (oldingi sahifaning eng eski id si). */
    @Transactional(readOnly = true)
    public List<AppDtos.ChatMessageItem> messages(AppPrincipal principal, Long conversationId, Long before, Integer size) {
        Access access = access(principal, conversationId);
        int pageSize = size == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, size));
        List<Message> page = before == null
            ? messageRepository.findLatest(conversationId, PageRequest.ofSize(pageSize))
            : messageRepository.findBefore(conversationId, before, PageRequest.ofSize(pageSize));
        Map<Long, List<ChatAttachmentResponse>> attachments = page.isEmpty() ? Map.of()
            : chatAttachmentService.byMessageIds(page.stream().map(Message::getId).toList());
        return page.stream().map(m -> item(m, access, attachments.get(m.getId()))).toList();
    }

    /**
     * Matn va/yoki bitta rasm (≤ 4MB, CH-02 — {@link ChatAttachmentService#upload}). CLIENT — app xabari;
     * STAFF — xodim xabari ({@link ChatService#send}, mijozga push).
     */
    @Transactional
    public Sent send(AppPrincipal principal, Long conversationId, String text, MultipartFile image) {
        Access access = access(principal, conversationId);
        List<ChatAttachmentRequest> attachments = image == null || image.isEmpty() ? List.of() : List.of(upload(image));
        ChatMessageResponse response;
        if (access.side() == Side.CLIENT) {
            syncParticipants(access.conversation());
            response = chatService.appendAppMessage(conversationId, access.identity().getId(), text, attachments);
            messageRepository.findById(response.getId())
                .ifPresent(m -> pushService.onAppMessage(access.conversation(), m));
        } else {
            ChatSendRequest request = new ChatSendRequest();
            request.setConversationId(conversationId);
            request.setText(text);
            request.setAttachments(attachments);
            response = chatService.send(access.staffUser(), request);
        }
        Message saved = messageRepository.findById(response.getId()).orElseThrow();
        return new Sent(item(saved, access, response.getAttachments()), response);
    }

    /**
     * O'qildi: CLIENT — suhbatdagi app kursori (faqat oldinga); STAFF — xodim kursori ({@link ChatService#markRead}),
     * o'qildi hodisasi xodimlarga tarqatiladi.
     */
    @Transactional
    public Optional<ChatReadReceiptResponse> read(AppPrincipal principal, Long conversationId, Long messageId) {
        Access access = access(principal, conversationId);
        if (!messageRepository.existsByIdAndConversationId(messageId, conversationId)) {
            throw CodedException.badRequest("app.chat.messageNotFound");
        }
        if (access.side() == Side.STAFF) {
            ChatReadRequest request = new ChatReadRequest();
            request.setConversationId(conversationId);
            request.setMessageId(messageId);
            return chatService.markRead(access.staffUser(), request);
        }
        Conversation c = access.conversation();
        if (c.getExternalLastReadMessageId() == null || c.getExternalLastReadMessageId() < messageId) {
            c.setExternalLastReadMessageId(messageId);
        }
        return Optional.empty();
    }

    // ── Ichki ────────────────────────────────────────────────────────────

    private Access access(AppPrincipal principal, Long conversationId) {
        Conversation c = conversationRepository.findById(conversationId)
            .filter(Conversation::isExternal)
            .orElseThrow(() -> CodedException.forbidden("app.chat.forbidden"));
        if (principal.identityId().equals(c.getExternalIdentityId())) {
            return new Access(c, Side.CLIENT, authService.identity(principal), null);
        }
        if (principal.isTeacher()
                && participantRepository.existsByConversationIdAndUserIdAndLeftAtIsNull(c.getId(), principal.staffUserId())) {
            return new Access(c, Side.STAFF, authService.identity(principal), authService.requireTeacher(principal));
        }
        throw CodedException.forbidden("app.chat.forbidden");
    }

    /** O'qituvchi useri → u dars beradigan guruh nomlari (identity o'quvchilarining ochiq guruhlari). */
    private Map<User, Set<String>> teachersOf(AppPrincipal principal) {
        Map<Long, User> users = new LinkedHashMap<>();
        Map<Long, Set<String>> groups = new LinkedHashMap<>();
        for (AppIdentityStudent link : authService.links(principal.identityId())) {
            for (StudentGroup sg : queryService.openEnrollments(link.getStudentId())) {
                var teacher = sg.getGroup().getTeacher();
                User user = teacher != null ? teacher.getUser() : null;
                if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || user.getRole() != UserRole.TEACHER
                        || user.getId().equals(principal.staffUserId())) {
                    continue;
                }
                users.putIfAbsent(user.getId(), user);
                groups.computeIfAbsent(user.getId(), k -> new LinkedHashSet<>()).add(sg.getGroup().getGroupName());
            }
        }
        Map<User, Set<String>> out = new LinkedHashMap<>();
        users.forEach((id, user) -> out.put(user, groups.get(id)));
        return out;
    }

    /**
     * Xodim ishtirokchilari: TEACHER — o'qituvchi; SUPPORT — faol A + SH + SM; DIRECTOR — faol SA. Yangi xodim
     * qo'shiladi, nofaol / rol o'zgargan — {@code left_at} (tarix saqlanadi).
     */
    private void syncParticipants(Conversation c) {
        Set<Long> desired = new LinkedHashSet<>();
        switch (c.getExternalTarget()) {
            case TARGET_TEACHER -> userRepository.findById(c.getExternalStaffUserId())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()) && u.getRole() == UserRole.TEACHER)
                .ifPresent(u -> desired.add(u.getId()));
            case TARGET_SUPPORT -> userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(SUPPORT_ROLES)
                .forEach(u -> desired.add(u.getId()));
            case TARGET_DIRECTOR -> userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(DIRECTOR_ROLES)
                .forEach(u -> desired.add(u.getId()));
            default -> { }
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        Set<Long> present = new LinkedHashSet<>();
        for (ConversationParticipant p : participantRepository.findByConversationId(c.getId())) {
            Long userId = p.getUser().getId();
            present.add(userId);
            if (desired.contains(userId) && p.getLeftAt() != null) {
                p.setLeftAt(null);
            } else if (!desired.contains(userId) && p.getLeftAt() == null) {
                p.setLeftAt(now);
            }
        }
        for (Long userId : desired) {
            if (!present.contains(userId)) {
                participantRepository.save(ConversationParticipant.builder()
                    .conversation(c)
                    .user(userRepository.getReferenceById(userId))
                    .joinedAt(now)
                    .build());
            }
        }
    }

    /** Xodim tomonidagi sarlavha: "Ota-ona: Ali Karimov, Vali Karimov" / "O'quvchi: …". */
    private String clientTitle(AppIdentity identity) {
        List<AppIdentityStudent> links = authService.links(identity.getId());
        Map<Long, Student> students = studentRepository.findAllById(
                links.stream().map(AppIdentityStudent::getStudentId).toList()).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        String names = links.stream().map(l -> students.get(l.getStudentId())).filter(java.util.Objects::nonNull)
            .map(MiniAppQueryService::fullName).collect(Collectors.joining(", "));
        String title;
        if (names.isEmpty()) {
            title = "Telegram: " + (identity.getFirstName() != null ? identity.getFirstName() : identity.getTelegramUserId());
        } else if (links.stream().anyMatch(l -> l.getRelation() == AppIdentityStudent.Relation.PARENT)) {
            title = "Ota-ona: " + names;
        } else {
            title = "O'quvchi: " + names;
        }
        return title.length() > 255 ? title.substring(0, 252) + "..." : title;
    }

    private AppDtos.ChatRow clientRow(Conversation c, AppIdentity identity) {
        String title;
        String label;
        switch (c.getExternalTarget()) {
            case TARGET_TEACHER -> {
                title = userRepository.findById(c.getExternalStaffUserId()).map(AppChatService::fullName).orElse("Ustoz");
                label = "ustoz";
            }
            case TARGET_DIRECTOR -> {
                title = "Direktor";
                label = "direktor";
            }
            default -> {
                title = "Menejer";
                label = "menejer";
            }
        }
        Message last = lastMessage(c.getId());
        long unread = messageRepository.countStaffMessagesAfter(c.getId(),
            c.getExternalLastReadMessageId() != null ? c.getExternalLastReadMessageId() : 0L);
        return new AppDtos.ChatRow(c.getId(), Side.CLIENT.name(), c.getExternalTarget(), title, label,
            last != null && last.getDeletedAt() == null ? last.getText() : null,
            last != null ? last.getType().name() : null,
            last != null ? last.getCreatedAt() : c.getLastMessageAt(),
            last != null ? identity.getId().equals(last.getSenderAppIdentityId()) : null,
            unread, c.isClosed() ? "CLOSED" : "OPEN");
    }

    private AppDtos.ChatRow staffRow(ConversationParticipant me) {
        Conversation c = me.getConversation();
        Message last = lastMessage(c.getId());
        long unread = messageRepository.countUnreadGrouped(me.getUser().getId(), List.of(c.getId())).stream()
            .mapToLong(r -> ((Number) r[1]).longValue()).sum();
        return new AppDtos.ChatRow(c.getId(), Side.STAFF.name(), c.getExternalTarget(), c.getTitle(), "mijoz",
            last != null && last.getDeletedAt() == null ? last.getText() : null,
            last != null ? last.getType().name() : null,
            last != null ? last.getCreatedAt() : c.getLastMessageAt(),
            last != null ? last.getSender() != null && last.getSender().getId().equals(me.getUser().getId()) : null,
            unread, c.isClosed() ? "CLOSED" : "OPEN");
    }

    private Message lastMessage(Long conversationId) {
        List<Message> last = messageRepository.findLastMessages(List.of(conversationId));
        return last.isEmpty() ? null : last.get(0);
    }

    private AppDtos.ChatMessageItem item(Message m, Access access, List<ChatAttachmentResponse> attachments) {
        boolean deleted = m.getDeletedAt() != null;
        boolean mine = access.side() == Side.CLIENT
            ? access.identity().getId().equals(m.getSenderAppIdentityId())
            : m.getSender() != null && m.getSender().getId().equals(access.staffUser().getId());
        String senderName;
        String senderLabel;
        if (m.getSender() != null) {
            senderName = fullName(m.getSender());
            senderLabel = AppChatPushService.staffLabel(m.getSender().getRole());
        } else {
            senderName = access.conversation().getTitle();
            senderLabel = "mijoz";
        }
        List<AppDtos.ChatAttachmentItem> files = deleted || attachments == null ? null : attachments.stream()
            .map(a -> new AppDtos.ChatAttachmentItem(a.getFileUrl(), a.getFileName(), a.getContentType(),
                a.getFileSize(), a.getWidth(), a.getHeight()))
            .toList();
        return new AppDtos.ChatMessageItem(m.getId(), m.getType().name(), deleted ? null : m.getText(), files,
            m.getCreatedAt(), mine, senderName, senderLabel, deleted);
    }

    /** Faqat rasm (CH-02: kengaytma + MIME + o'lcham — {@link ChatAttachmentService#upload}). */
    private ChatAttachmentRequest upload(MultipartFile image) {
        String type = image.getContentType() == null ? "" : image.getContentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("image/")) {
            throw CodedException.badRequest("app.chat.imageOnly");
        }
        ChatUploadResponse uploaded = chatAttachmentService.upload(image, null, null);
        ChatAttachmentRequest request = new ChatAttachmentRequest();
        request.setFileUrl(uploaded.getFileUrl());
        request.setFileName(uploaded.getFileName());
        request.setFileSize(uploaded.getFileSize());
        request.setContentType(uploaded.getContentType());
        request.setWidth(uploaded.getWidth());
        request.setHeight(uploaded.getHeight());
        return request;
    }

    private static String fullName(User u) {
        return (u.getFirstName() + " " + u.getLastName()).trim();
    }
}
