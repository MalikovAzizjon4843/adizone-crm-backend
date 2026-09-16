package com.crm.service;

import com.crm.config.Messages;
import com.crm.dto.request.ChatReadRequest;
import com.crm.dto.request.ChatSendRequest;
import com.crm.dto.request.GroupConversationRequest;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatReadReceiptResponse;
import com.crm.dto.response.ChatUserResponse;
import com.crm.dto.response.ConversationResponse;
import com.crm.entity.Conversation;
import com.crm.entity.ConversationParticipant;
import com.crm.entity.Message;
import com.crm.entity.User;
import com.crm.entity.enums.ConversationType;
import com.crm.entity.enums.MessageType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.ConversationParticipantRepository;
import com.crm.repository.ConversationRepository;
import com.crm.repository.MessageRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ichki chat — 1-bosqich: matnli xabar va o'qilgan belgisi.
 *
 * <p>Qamrov: {@code User} hisobi bor xodimlar. O'quvchi va ota-onalar
 * keyinroq qo'shiladi — ularning entity'lari {@code User} ga bog'langani
 * uchun hisob ochilishi bilan chat o'zi ishlaydi, bu yerda o'zgarish
 * kerak bo'lmaydi.
 *
 * <p>Servis hech narsani tarqatmaydi: u DTO qaytaradi, yuborishni
 * chaqiruvchi bajaradi. Sabab — tranzaksiya. Xabar commit bo'lmasidan
 * tarqatilsa, uni olgan mijoz darhol lentani qayta so'raganda o'sha
 * xabarni ko'rmasligi mumkin edi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    /** Bir sahifadagi xabarlar: standart va yuqori chegara. */
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatAccessService chatAccessService;
    private final PlatformTransactionManager transactionManager;
    private final Messages messages;

    // ── TASK 4: xabar yuborish ─────────────────────────────────────────

    /**
     * Xabarni saqlaydi va tarqatiladigan shaklda qaytaradi.
     *
     * <p>Tekshiruvlar shu yerda, controllerda emas: xabar ham STOMP,
     * ham (kelajakda) REST orqali kelishi mumkin, qoidalar esa bitta.
     */
    @Transactional
    public ChatMessageResponse send(User sender, ChatSendRequest request) {
        String text = request.getText() == null ? "" : request.getText().trim();
        if (text.isEmpty()) {
            throw new BadRequestException(messages.get("chat.text.required"));
        }
        if (text.length() > Message.TEXT_MAX) {
            throw new BadRequestException(messages.get("chat.text.size"));
        }

        ConversationParticipant participant =
            chatAccessService.requireParticipant(request.getConversationId(), sender.getId());
        Conversation conversation = participant.getConversation();

        Message message = messageRepository.save(Message.builder()
            .conversation(conversation)
            .sender(sender)
            .text(text)
            .type(MessageType.TEXT)
            .build());

        conversation.setLastMessageAt(message.getCreatedAt());

        // Yuboruvchi o'z xabarini o'qigan hisoblanadi — aks holda u
        // yuborgan zahoti o'ziga o'qilmagan bo'lib ko'rinardi.
        advanceReadCursor(participant, message.getId());

        return toMessageResponse(message, request.getClientId());
    }

    // ── TASK 5: o'qilgan belgisi ───────────────────────────────────────

    /**
     * O'qilganlik kursorini oldinga suradi.
     *
     * <p>Faqat oldinga: eski {@code messageId} kelsa hech narsa o'zgarmaydi
     * va {@code empty} qaytadi — bu holda chaqiruvchi boshqalarga xabar
     * bermaydi. Shu bilan ikkita oynasi ochiq foydalanuvchida hisoblagich
     * orqaga sakramaydi.
     */
    @Transactional
    public Optional<ChatReadReceiptResponse> markRead(User user, ChatReadRequest request) {
        ConversationParticipant participant =
            chatAccessService.requireParticipant(request.getConversationId(), user.getId());

        if (!messageRepository.existsByIdAndConversationId(
                request.getMessageId(), request.getConversationId())) {
            throw new BadRequestException(messages.get("chat.message.notFound"));
        }

        if (!advanceReadCursor(participant, request.getMessageId())) {
            return Optional.empty();
        }

        return Optional.of(ChatReadReceiptResponse.builder()
            .conversationId(request.getConversationId())
            .userId(user.getId())
            .messageId(request.getMessageId())
            .build());
    }

    /** Kursor haqiqatan siljigan bo'lsa true. */
    private boolean advanceReadCursor(ConversationParticipant participant, Long messageId) {
        Long current = participant.getLastReadMessageId();
        if (current != null && current >= messageId) {
            return false;
        }
        participant.setLastReadMessageId(messageId);
        return true;
    }

    // ── TASK 6: suhbatlar ro'yxati ─────────────────────────────────────

    /**
     * Foydalanuvchining suhbatlari — qadalganlar yuqorida, keyin oxirgi
     * xabar vaqti bo'yicha.
     *
     * <p>Suhbatlar soni qancha bo'lishidan qat'i nazar TO'RTTA so'rov:
     * a'zoliklar (suhbatlari bilan), ishtirokchilar, oxirgi xabarlar va
     * o'qilmaganlar (bitta GROUP BY). Har bir suhbat uchun alohida so'rov
     * yuborilmaydi.
     */
    @Transactional(readOnly = true)
    public List<ConversationResponse> listConversations(User user) {
        // 1-so'rov
        List<ConversationParticipant> mine = participantRepository.findActiveForUser(user.getId());
        if (mine.isEmpty()) {
            return List.of();
        }

        List<Long> conversationIds = mine.stream()
            .map(participant -> participant.getConversation().getId())
            .toList();

        // 2-so'rov: barcha suhbatlarning faol ishtirokchilari
        Map<Long, List<ConversationParticipant>> membersByConversation = new HashMap<>();
        for (ConversationParticipant member
                : participantRepository.findActiveByConversationIds(conversationIds)) {
            membersByConversation
                .computeIfAbsent(member.getConversation().getId(), key -> new ArrayList<>())
                .add(member);
        }

        // 3-so'rov: har bir suhbatning oxirgi xabari
        Map<Long, Message> lastByConversation = new HashMap<>();
        for (Message message : messageRepository.findLastMessages(conversationIds)) {
            lastByConversation.put(message.getConversation().getId(), message);
        }

        // 4-so'rov: o'qilmaganlar, bitta GROUP BY
        Map<Long, Long> unreadByConversation = unreadCounts(user.getId(), conversationIds);

        List<ConversationResponse> result = new ArrayList<>(mine.size());
        for (ConversationParticipant me : mine) {
            Long conversationId = me.getConversation().getId();
            result.add(toConversationResponse(
                me,
                membersByConversation.getOrDefault(conversationId, List.of()),
                lastByConversation.get(conversationId),
                unreadByConversation.getOrDefault(conversationId, 0L)));
        }
        return result;
    }

    private Map<Long, Long> unreadCounts(Long userId, Collection<Long> conversationIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadGrouped(userId, conversationIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Suhbat lentasi: yangi → eski, kursor bilan.
     *
     * <p>{@code before} — oldingi sahifadagi eng eski xabar {@code id} si.
     * Kursor {@code offset} dan afzal: yozishuv davomida yangi xabar kelsa
     * ham sahifalar siljib, takror yoki tushib qolgan xabar chiqmaydi.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> listMessages(User user, Long conversationId,
                                                   Long before, Integer size) {
        chatAccessService.requireParticipant(conversationId, user.getId());

        Pageable pageable = PageRequest.ofSize(normalizeSize(size));
        List<Message> page = (before == null)
            ? messageRepository.findLatest(conversationId, pageable)
            : messageRepository.findBefore(conversationId, before, pageable);

        return page.stream()
            .map(message -> toMessageResponse(message, null))
            .toList();
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * DIRECT suhbat: bor bo'lsa mavjudi, yo'q bo'lsa yangisi.
     *
     * <p>Ikki so'rov bir vaqtda kelsa, {@code direct_key} dagi UNIQUE
     * ikkinchisini rad etadi va shu holatda mavjud suhbat qayta o'qiladi.
     * Har bosqich alohida tranzaksiyada — PostgreSQL'da yiqilgan INSERT'dan
     * keyin o'sha seansda davom etib bo'lmaydi.
     */
    public ConversationResponse getOrCreateDirect(User user, Long otherUserId) {
        if (otherUserId == null || otherUserId.equals(user.getId())) {
            throw new BadRequestException(messages.get("chat.direct.self"));
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String directKey = Conversation.directKeyOf(user.getId(), otherUserId);

        Optional<ConversationResponse> existing = tx.execute(status -> loadDirect(user, directKey));
        if (existing != null && existing.isPresent()) {
            return existing.get();
        }

        try {
            return tx.execute(status -> createDirect(user, otherUserId, directKey));
        } catch (DataIntegrityViolationException e) {
            log.debug("DIRECT suhbat parallel yaratildi: {}", directKey);
            return tx.execute(status -> loadDirect(user, directKey))
                .orElseThrow(() -> new BadRequestException(messages.get("chat.direct.failed")));
        }
    }

    private Optional<ConversationResponse> loadDirect(User user, String directKey) {
        return conversationRepository.findByDirectKey(directKey)
            .map(conversation -> singleRow(user, conversation));
    }

    private ConversationResponse createDirect(User user, Long otherUserId, String directKey) {
        User other = userRepository.findById(otherUserId)
            .orElseThrow(() -> new ResourceNotFoundException(messages.get("chat.user.notFound")));
        if (!Boolean.TRUE.equals(other.getIsActive())) {
            throw new BadRequestException(messages.get("chat.user.inactive"));
        }

        Conversation conversation = conversationRepository.save(Conversation.builder()
            .type(ConversationType.DIRECT)
            .directKey(directKey)
            .createdBy(user)
            // Yangi suhbat ro'yxat tepasida turadi, aks holda birinchi xabar
            // yozilmaguncha uni ro'yxatdan topib bo'lmaydi.
            .lastMessageAt(LocalDateTime.now())
            .build());

        participantRepository.save(newParticipant(conversation, user));
        participantRepository.save(newParticipant(conversation, other));

        return singleRow(user, conversation);
    }

    /** Guruh: yaratuvchi doim a'zo, takrorlangan id lar bir marta olinadi. */
    @Transactional
    public ConversationResponse createGroup(User user, GroupConversationRequest request) {
        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        if (title.isEmpty()) {
            throw new BadRequestException(messages.get("chat.title.required"));
        }

        Set<Long> memberIds = new LinkedHashSet<>(request.getUserIds());
        memberIds.remove(user.getId());
        if (memberIds.isEmpty()) {
            throw new BadRequestException(messages.get("chat.group.members"));
        }

        List<User> members = userRepository.findAllById(memberIds);
        if (members.size() != memberIds.size()) {
            throw new ResourceNotFoundException(messages.get("chat.user.notFound"));
        }

        Conversation conversation = conversationRepository.save(Conversation.builder()
            .type(ConversationType.GROUP)
            .title(title)
            .createdBy(user)
            .lastMessageAt(LocalDateTime.now())
            .build());

        participantRepository.save(newParticipant(conversation, user));
        for (User member : members) {
            participantRepository.save(newParticipant(conversation, member));
        }

        return singleRow(user, conversation);
    }

    private ConversationParticipant newParticipant(Conversation conversation, User user) {
        return ConversationParticipant.builder()
            .conversation(conversation)
            .user(user)
            .build();
    }

    /** Qadash — faqat shu foydalanuvchining ro'yxatiga ta'sir qiladi. */
    @Transactional
    public ConversationResponse setPinned(User user, Long conversationId, Boolean pinned) {
        ConversationParticipant participant =
            chatAccessService.requireParticipant(conversationId, user.getId());
        participant.setIsPinned(Boolean.TRUE.equals(pinned));
        return singleRow(user, participant.getConversation());
    }

    /** Kim bilan yozishish mumkin: faol xodimlar, o'zidan tashqari. */
    @Transactional(readOnly = true)
    public List<ChatUserResponse> listChatUsers(User user) {
        return userRepository.findByIsActiveTrue().stream()
            .filter(candidate -> !candidate.getId().equals(user.getId()))
            .sorted(Comparator
                .comparing(User::getFirstName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(User::getLastName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .map(ChatService::toUserResponse)
            .toList();
    }

    // ── TASK 7: umumiy o'qilmaganlar ───────────────────────────────────

    /** Sidebar/header belgisi — bitta so'rov, suhbatlar bo'yicha aylanmaydi. */
    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        return messageRepository.countUnreadForUser(user.getId());
    }

    // ── Ko'rinishga o'girish ───────────────────────────────────────────

    /**
     * Bitta suhbat uchun to'liq qator — yaratish va qadashdan keyin
     * qaytariladi, ya'ni frontend ro'yxatni butunlay qayta so'ramaydi.
     */
    private ConversationResponse singleRow(User user, Conversation conversation) {
        ConversationParticipant me =
            chatAccessService.requireParticipant(conversation.getId(), user.getId());
        List<ConversationParticipant> members =
            participantRepository.findActiveByConversationIds(List.of(conversation.getId()));
        List<Message> last = messageRepository.findLastMessages(List.of(conversation.getId()));
        long unread = unreadCounts(user.getId(), List.of(conversation.getId()))
            .getOrDefault(conversation.getId(), 0L);

        return toConversationResponse(me, members, last.isEmpty() ? null : last.get(0), unread);
    }

    private ConversationResponse toConversationResponse(ConversationParticipant me,
                                                         List<ConversationParticipant> members,
                                                         Message lastMessage,
                                                         long unread) {
        Conversation conversation = me.getConversation();

        // DIRECT: ro'yxatda suhbatdosh ko'rinadi, o'zing emas.
        // GROUP: barcha faol a'zolar — avatarlar tizimchasi uchun.
        List<ConversationParticipant> shown = conversation.isDirect()
            ? members.stream()
                .filter(member -> !member.getUser().getId().equals(me.getUser().getId()))
                .toList()
            : members;

        ConversationParticipant peer = conversation.isDirect() && !shown.isEmpty()
            ? shown.get(0)
            : null;

        return ConversationResponse.builder()
            .id(conversation.getId())
            .type(conversation.getType())
            .title(conversation.isDirect()
                ? (peer != null ? fullName(peer.getUser()) : null)
                : conversation.getTitle())
            .photoUrl(peer != null ? peer.getUser().getPhotoUrl() : null)
            .participants(shown.stream()
                .map(member -> toUserResponse(member.getUser()))
                .toList())
            .lastMessageText(lastMessage != null ? lastMessage.getText() : null)
            .lastMessageAt(lastMessage != null
                ? lastMessage.getCreatedAt()
                : conversation.getLastMessageAt())
            .lastMessageSenderId(lastMessage != null ? lastMessage.getSender().getId() : null)
            .unreadCount(unread)
            .lastReadMessageId(me.getLastReadMessageId())
            // Suhbatdosh qayergacha o'qigani. Bu bo'lmasa DIRECT dagi ✓✓
            // faqat jonli READ hodisasida chiqib, sahifa yangilangach
            // yo'qolib qolardi.
            .peerLastReadMessageId(peer != null ? peer.getLastReadMessageId() : null)
            .isPinned(me.getIsPinned())
            .isMuted(me.getIsMuted())
            .build();
    }

    private ChatMessageResponse toMessageResponse(Message message, String clientId) {
        User sender = message.getSender();
        return ChatMessageResponse.builder()
            .id(message.getId())
            .uuid(message.getUuid())
            .conversationId(message.getConversation().getId())
            .senderId(sender.getId())
            .senderName(fullName(sender))
            .senderPhotoUrl(sender.getPhotoUrl())
            .text(message.getText())
            .messageType(message.getType())
            .replyToId(message.getReplyToId())
            .clientId(clientId)
            .createdAt(message.getCreatedAt())
            .editedAt(message.getEditedAt())
            .build();
    }

    private static ChatUserResponse toUserResponse(User user) {
        return ChatUserResponse.builder()
            .id(user.getId())
            .fullName(fullName(user))
            .username(user.getUsername())
            .role(user.getRole())
            .photoUrl(user.getPhotoUrl())
            .build();
    }

    private static String fullName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }
}
