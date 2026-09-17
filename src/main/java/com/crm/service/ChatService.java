package com.crm.service;

import com.crm.config.Messages;
import com.crm.dto.request.ChatDeleteRequest;
import com.crm.dto.request.ChatEditRequest;
import com.crm.dto.request.ChatReadRequest;
import com.crm.dto.request.ChatSendRequest;
import com.crm.dto.request.ChatTypingRequest;
import com.crm.dto.request.GroupConversationRequest;
import com.crm.dto.response.ChatAttachmentResponse;
import com.crm.dto.response.ChatMessageDeletedResponse;
import com.crm.dto.response.ChatMessageEditedResponse;
import com.crm.dto.response.ChatMessageResponse;
import com.crm.dto.response.ChatReadReceiptResponse;
import com.crm.dto.response.ChatReplyPreviewResponse;
import com.crm.dto.response.ChatSearchResponse;
import com.crm.dto.response.ChatTypingResponse;
import com.crm.dto.response.ChatUserResponse;
import com.crm.dto.response.ConversationResponse;
import com.crm.entity.Conversation;
import com.crm.entity.ConversationParticipant;
import com.crm.entity.Message;
import com.crm.entity.User;
import com.crm.entity.enums.ConversationType;
import com.crm.entity.enums.MessageType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ForbiddenException;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ichki chat: matnli xabar, o'qilgan belgisi, "yozmoqda" va qidiruv.
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

    /** Qidiruvda har bir bo'limdan qaytariladigan qatorlar soni. */
    public static final int SEARCH_LIMIT = 20;

    /**
     * Qidiruv so'zining eng qisqa uzunligi. Bitta belgi butun arxivni
     * qaytaradi va foydasi yo'q — bunday so'rov bazaga umuman bormaydi.
     */
    public static final int MIN_QUERY_LENGTH = 2;

    /**
     * Xabarni tahrirlash oynasi. Undan keyin matn o'zgarmaydi: suhbatdosh
     * allaqachon o'qib bo'lgan gapni keyinroq boshqasiga almashtirib
     * qo'yish mumkin bo'lmasligi kerak.
     */
    public static final Duration EDIT_WINDOW = Duration.ofMinutes(15);

    /** O'zganikini ham o'chira oladigan rollar. */
    private static final Set<UserRole> DELETE_ANY_ROLES =
        EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN);

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatAccessService chatAccessService;
    private final ChatPresenceService chatPresenceService;
    private final ChatAttachmentService chatAttachmentService;
    private final PlatformTransactionManager transactionManager;
    private final Messages messages;

    // ── Xabar yuborish ─────────────────────────────────────────────────

    /**
     * Xabarni saqlaydi va tarqatiladigan shaklda qaytaradi.
     *
     * <p>Tekshiruvlar shu yerda, controllerda emas: xabar ham STOMP,
     * ham (kelajakda) REST orqali kelishi mumkin, qoidalar esa bitta.
     *
     * <p>Matn biriktirma bo'lganda ixtiyoriy — rasm ostidagi izoh bo'lishi
     * ham, bo'lmasligi ham mumkin. Ikkovi ham bo'sh bo'lsa yuborib
     * bo'lmaydi: bo'sh xabar lentada bo'sh joy bo'lib qolardi.
     */
    @Transactional
    public ChatMessageResponse send(User sender, ChatSendRequest request) {
        String text = request.getText() == null ? "" : request.getText().trim();
        boolean hasAttachments = request.getAttachments() != null
            && !request.getAttachments().isEmpty();
        if (text.isEmpty() && !hasAttachments) {
            throw new BadRequestException(messages.get("chat.text.required"));
        }
        if (text.length() > Message.TEXT_MAX) {
            throw new BadRequestException(messages.get("chat.text.size"));
        }

        ConversationParticipant participant =
            chatAccessService.requireParticipant(request.getConversationId(), sender.getId());
        Conversation conversation = participant.getConversation();

        Long replyToId = request.getReplyToId();
        if (replyToId != null
                && !messageRepository.existsByIdAndConversationId(
                        replyToId, conversation.getId())) {
            // Boshqa suhbatdagi xabarga javob berib bo'lmaydi: iqtibos
            // o'sha suhbat a'zolariga begona matnni ko'rsatib qo'yardi.
            throw new BadRequestException(messages.get("chat.reply.notInConversation"));
        }

        Message message = messageRepository.save(Message.builder()
            .conversation(conversation)
            .sender(sender)
            .text(text.isEmpty() ? null : text)
            .type(MessageType.TEXT)
            .replyToId(replyToId)
            .build());

        // Tur biriktirmalardan kelib chiqadi — so'rovdan emas.
        ChatAttachmentService.Attached attached =
            chatAttachmentService.attach(message, request.getAttachments());
        message.setType(attached.type());

        conversation.setLastMessageAt(message.getCreatedAt());

        // Yuboruvchi o'z xabarini o'qigan hisoblanadi — aks holda u
        // yuborgan zahoti o'ziga o'qilmagan bo'lib ko'rinardi.
        advanceReadCursor(participant, message.getId());

        MessageContext context = new MessageContext(
            Map.of(message.getId(), attached.attachments()),
            replyPreviews(List.of(message)));
        return toMessageResponse(message, request.getClientId(), context);
    }

    // ── Tahrirlash va o'chirish ────────────────────────────────

    /**
     * Matnni almashtiradi.
     *
     * <p>Faqat muallif va faqat sof matnli xabar: biriktirmasi bor
     * xabarda tahrir izohni o'zgartirib, fayl esa joyida qolgan bo'lardi
     * — bu chalkash, shuning uchun umuman ruxsat berilmaydi.
     *
     * <p>Oyna {@link #EDIT_WINDOW}. Undan keyin xabar tarixning bir
     * qismi: hamma o'qib bo'lgan.
     */
    @Transactional
    public ChatMessageEditedResponse edit(User user, ChatEditRequest request) {
        Message message = messageOrThrow(request.getMessageId());
        chatAccessService.requireParticipant(
            message.getConversation().getId(), user.getId());

        if (!message.getSender().getId().equals(user.getId())) {
            throw new ForbiddenException(messages.get("chat.edit.notAuthor"));
        }
        if (message.getDeletedAt() != null) {
            throw new BadRequestException(messages.get("chat.edit.deleted"));
        }
        if (message.getType() != MessageType.TEXT) {
            throw new BadRequestException(messages.get("chat.edit.notText"));
        }
        if (message.getCreatedAt().plus(EDIT_WINDOW).isBefore(LocalDateTime.now())) {
            throw new BadRequestException(messages.get("chat.edit.tooOld",
                EDIT_WINDOW.toMinutes()));
        }

        String text = request.getText() == null ? "" : request.getText().trim();
        if (text.isEmpty()) {
            throw new BadRequestException(messages.get("chat.text.required"));
        }

        message.setText(text);
        message.setEditedAt(LocalDateTime.now());

        return ChatMessageEditedResponse.builder()
            .conversationId(message.getConversation().getId())
            .messageId(message.getId())
            .text(text)
            .editedAt(message.getEditedAt())
            .build();
    }

    /**
     * Yumshoq o'chirish: {@code deletedAt} qo'yiladi, qator ham,
     * biriktirmalar ham, diskdagi fayllar ham joyida qoladi.
     *
     * <p>Fayl ataylab o'chirilmaydi: adashib o'chirilgan xabarni bazadan
     * tiklash mumkin, diskdan o'chgan faylni esa yo'q.
     *
     * <p>Allaqachon o'chirilgan bo'lsa {@code empty} — ikki marta bosilgan
     * tugma ikkita bir xil hodisa tarqatmasin.
     */
    @Transactional
    public Optional<ChatMessageDeletedResponse> delete(User user, ChatDeleteRequest request) {
        Message message = messageOrThrow(request.getMessageId());
        chatAccessService.requireParticipant(
            message.getConversation().getId(), user.getId());

        boolean isAuthor = message.getSender().getId().equals(user.getId());
        if (!isAuthor && !DELETE_ANY_ROLES.contains(user.getRole())) {
            throw new ForbiddenException(messages.get("chat.delete.notAllowed"));
        }
        if (message.getDeletedAt() != null) {
            return Optional.empty();
        }

        message.setDeletedAt(LocalDateTime.now());

        return Optional.of(ChatMessageDeletedResponse.builder()
            .conversationId(message.getConversation().getId())
            .messageId(message.getId())
            .deletedBy(user.getId())
            .build());
    }

    private Message messageOrThrow(Long messageId) {
        return messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("chat.message.notFound")));
    }

    // ── O'qilgan belgisi ───────────────────────────────────────────────

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

    // ── "Yozmoqda" ─────────────────────────────────────────────────────

    /**
     * "Yozmoqda" hodisasini tayyorlaydi. Bazaga hech narsa yozilmaydi.
     *
     * <p>A'zolik baribir tekshiriladi: begona odam suhbat topikiga
     * shovqin yubora olmasin.
     *
     * <p>Server taymer yuritmaydi — belgini frontend uch soniyada o'zi
     * o'chiradi. Aks holda har bir yozayotgan odam uchun bittadan
     * rejalashtirilgan vazifa turishi kerak bo'lardi.
     */
    @Transactional(readOnly = true)
    public ChatTypingResponse typing(User user, ChatTypingRequest request) {
        chatAccessService.requireParticipant(request.getConversationId(), user.getId());
        return ChatTypingResponse.builder()
            .conversationId(request.getConversationId())
            .userId(user.getId())
            .userName(fullName(user))
            .typing(Boolean.TRUE.equals(request.getTyping()))
            .build();
    }

    // ── Suhbatlar ro'yxati ─────────────────────────────────────────────

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
        List<ConversationParticipant> mine = participantRepository.findActiveForUser(user.getId());
        if (mine.isEmpty()) {
            return List.of();
        }
        List<Long> ids = conversationIdsOf(mine);
        return toRows(user, mine, membersOf(ids), lastMessagesOf(ids),
            unreadCounts(user.getId(), ids));
    }

    /**
     * Suhbat lentasi: yangi → eski, kursor bilan.
     *
     * <p>{@code before} — oldingi sahifadagi eng eski xabar {@code id} si.
     * Kursor {@code offset} dan afzal: yozishuv davomida yangi xabar kelsa
     * ham sahifalar siljib, takror yoki tushib qolgan xabar chiqmaydi.
     *
     * <p>{@code around} — qidiruv natijasidan xabarga sakrash uchun:
     * berilgan xabar va uning ikki tomonidagi kontekst. Ikkovi birga
     * berilmaydi, chunki bir so'rov ikki xil joydan sahifalay olmaydi.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> listMessages(User user, Long conversationId,
                                                   Long before, Long around, Integer size) {
        if (before != null && around != null) {
            throw new BadRequestException(messages.get("chat.cursor.conflict"));
        }
        chatAccessService.requireParticipant(conversationId, user.getId());

        int pageSize = normalizeSize(size);
        if (around != null) {
            return aroundWindow(conversationId, around, pageSize);
        }

        Pageable pageable = PageRequest.ofSize(pageSize);
        List<Message> page = (before == null)
            ? messageRepository.findLatest(conversationId, pageable)
            : messageRepository.findBefore(conversationId, before, pageable);

        return toMessageResponses(page);
    }

    /**
     * Xabar atrofidagi oyna: yarmi undan eski, yarmi yangi.
     *
     * <p>Ikki so'rov, chunki kursordan ikki yoqqa {@code LIMIT} bilan
     * yurish bitta {@code ORDER BY} ga sig'maydi. Yangi tomoni ESKI →
     * YANGI tartibda o'qiladi (aks holda {@code LIMIT} lentaning eng
     * oxirini kesib olardi) va shu yerda qayta ag'dariladi, natijada
     * javob odatdagidek yangi → eski bo'lib qoladi.
     */
    private List<ChatMessageResponse> aroundWindow(Long conversationId, Long around, int size) {
        if (!messageRepository.existsByIdAndConversationId(around, conversationId)) {
            throw new BadRequestException(messages.get("chat.message.notFound"));
        }

        int half = Math.max(size / 2, 1);
        List<Message> older = messageRepository.findBefore(
            conversationId, around, PageRequest.ofSize(half));
        // half + 1: mo'ljaldagi xabarning o'zi ham shu ro'yxatda keladi.
        List<Message> newer = messageRepository.findFrom(
            conversationId, around, PageRequest.ofSize(half + 1));

        List<Message> window = new ArrayList<>(older.size() + newer.size());
        for (int i = newer.size() - 1; i >= 0; i--) {
            window.add(newer.get(i));
        }
        window.addAll(older);
        return toMessageResponses(window);
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    // ── Qidiruv ────────────────────────────────────────────────────────

    /**
     * Umumiy qidiruv: suhbat nomlari va xabar matnlari.
     *
     * <p>Xabarlar faqat foydalanuvchi a'zo bo'lgan suhbatlardan olinadi
     * — chiqib ketgan guruhining arxivi ham ko'rinmaydi.
     *
     * <p>Beshta so'rov, natija hajmidan qat'i nazar: mos suhbatlar, mos
     * xabarlar, ishtirokchilar (ikkala ro'yxat uchun birgalikda), oxirgi
     * xabarlar va o'qilmaganlar.
     */
    @Transactional(readOnly = true)
    public ChatSearchResponse search(User user, String query) {
        String pattern = likePatternOrNull(query);
        if (pattern == null) {
            return ChatSearchResponse.builder()
                .conversations(List.of())
                .messages(List.of())
                .build();
        }

        Pageable limit = PageRequest.ofSize(SEARCH_LIMIT);
        List<ConversationParticipant> matched = participantRepository.searchByName(
            user.getId(), pattern, ConversationType.GROUP, ConversationType.DIRECT, limit);
        List<Message> found = messageRepository.searchForUser(user.getId(), pattern, limit);

        List<Long> matchedIds = conversationIdsOf(matched);

        // Ikkala ro'yxatning suhbatlari bitta so'rovda olinadi: mos
        // suhbatlar qatorini qurish uchun ham, topilgan xabar yonidagi
        // suhbat nomi uchun ham aynan shu ma'lumot kerak.
        Set<Long> allIds = new LinkedHashSet<>(matchedIds);
        found.forEach(message -> allIds.add(message.getConversation().getId()));
        Map<Long, List<ConversationParticipant>> members = membersOf(allIds);

        List<ConversationResponse> conversations = matched.isEmpty()
            ? List.of()
            : toRows(user, matched, members, lastMessagesOf(matchedIds),
                unreadCounts(user.getId(), matchedIds));

        MessageContext context = contextFor(found);
        List<ChatMessageResponse> hits = found.stream()
            .map(message -> {
                ChatMessageResponse response = toMessageResponse(message, null, context);
                response.setConversationTitle(displayTitle(user, message.getConversation(),
                    members.getOrDefault(message.getConversation().getId(), List.of())));
                return response;
            })
            .toList();

        return ChatSearchResponse.builder()
            .conversations(conversations)
            .messages(hits)
            .build();
    }

    /**
     * Bitta suhbat ichidagi qidiruv.
     *
     * <p>Javob odatdagi xabar shaklida: {@code id} — mo'ljaldagi xabar,
     * {@code createdAt} esa frontend o'sha joyga sakragach kontekstni
     * to'g'ri joylashtirishi uchun. Sakrash
     * {@code ?around=} bilan bajariladi.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> searchInConversation(User user, Long conversationId,
                                                           String query) {
        chatAccessService.requireParticipant(conversationId, user.getId());

        String pattern = likePatternOrNull(query);
        if (pattern == null) {
            return List.of();
        }
        return toMessageResponses(messageRepository
            .searchInConversation(conversationId, pattern, PageRequest.ofSize(SEARCH_LIMIT)));
    }

    /**
     * LIKE naqshi; so'rov juda qisqa bo'lsa {@code null}.
     *
     * <p>{@code %} va {@code _} qochiriladi. Boshqa qidiruvlardan farqi
     * shu: chatda "50% chegirma" yoki "so'm_" kabi matn odatiy va uni
     * qidirgan odam butun arxivni emas, o'sha xabarni kutadi.
     *
     * <p>Registr {@code LOWER(...) LIKE} bilan e'tiborsiz qoldiriladi —
     * JPQL da {@code ILIKE} yo'q, natija esa aynan o'sha.
     */
    private static String likePatternOrNull(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return null;
        }
        String escaped = trimmed.toLowerCase(Locale.ROOT)
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
        return "%" + escaped + "%";
    }

    // ── Suhbat ochish ──────────────────────────────────────────────────

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
            .map(this::toUserResponse)
            .toList();
    }

    // ── Umumiy o'qilmaganlar ───────────────────────────────────────────

    /** Sidebar/header belgisi — bitta so'rov, suhbatlar bo'yicha aylanmaydi. */
    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        return messageRepository.countUnreadForUser(user.getId());
    }

    // ── Yig'ma o'qishlar ───────────────────────────────────────────────

    private List<Long> conversationIdsOf(List<ConversationParticipant> participants) {
        return participants.stream()
            .map(participant -> participant.getConversation().getId())
            .toList();
    }

    private Map<Long, List<ConversationParticipant>> membersOf(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ConversationParticipant>> byConversation = new HashMap<>();
        for (ConversationParticipant member
                : participantRepository.findActiveByConversationIds(conversationIds)) {
            byConversation
                .computeIfAbsent(member.getConversation().getId(), key -> new ArrayList<>())
                .add(member);
        }
        return byConversation;
    }

    private Map<Long, Message> lastMessagesOf(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Message> byConversation = new HashMap<>();
        for (Message message : messageRepository.findLastMessages(conversationIds)) {
            byConversation.put(message.getConversation().getId(), message);
        }
        return byConversation;
    }

    private Map<Long, Long> unreadCounts(Long userId, Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadGrouped(userId, conversationIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    // ── Ko'rinishga o'girish ───────────────────────────────────────────

    private List<ConversationResponse> toRows(User user,
                                               List<ConversationParticipant> mine,
                                               Map<Long, List<ConversationParticipant>> members,
                                               Map<Long, Message> lastMessages,
                                               Map<Long, Long> unread) {
        List<ConversationResponse> result = new ArrayList<>(mine.size());
        for (ConversationParticipant me : mine) {
            Long conversationId = me.getConversation().getId();
            result.add(toConversationResponse(
                me,
                members.getOrDefault(conversationId, List.of()),
                lastMessages.get(conversationId),
                unread.getOrDefault(conversationId, 0L)));
        }
        return result;
    }

    /**
     * Bitta suhbat uchun to'liq qator — yaratish va qadashdan keyin
     * qaytariladi, ya'ni frontend ro'yxatni butunlay qayta so'ramaydi.
     */
    private ConversationResponse singleRow(User user, Conversation conversation) {
        ConversationParticipant me =
            chatAccessService.requireParticipant(conversation.getId(), user.getId());
        List<Long> ids = List.of(conversation.getId());
        return toRows(user, List.of(me), membersOf(ids), lastMessagesOf(ids),
            unreadCounts(user.getId(), ids)).get(0);
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
            .lastMessageType(lastMessage != null ? lastMessage.getType() : null)
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

    /** Qidiruv natijasidagi sarlavha: guruh nomi yoki suhbatdosh ismi. */
    private String displayTitle(User user, Conversation conversation,
                                 List<ConversationParticipant> members) {
        if (!conversation.isDirect()) {
            return conversation.getTitle();
        }
        return members.stream()
            .filter(member -> !member.getUser().getId().equals(user.getId()))
            .findFirst()
            .map(member -> fullName(member.getUser()))
            .orElse(null);
    }

    /**
     * Bir sahifadagi xabarlarga qo'shimcha bo'lib keladigan narsalar.
     *
     * <p>Ikkovi ham xabar boshiga emas, sahifa boshiga bitta so'rov
     * bilan yig'iladi — aks holda ellik xabarli lenta yuzta so'rov
     * yuborardi.
     */
    private record MessageContext(Map<Long, List<ChatAttachmentResponse>> attachments,
                                  Map<Long, ChatReplyPreviewResponse> replies) {

        static MessageContext empty() {
            return new MessageContext(Map.of(), Map.of());
        }
    }

    private List<ChatMessageResponse> toMessageResponses(List<Message> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        MessageContext context = contextFor(page);
        return page.stream()
            .map(message -> toMessageResponse(message, null, context))
            .toList();
    }

    private MessageContext contextFor(List<Message> page) {
        if (page.isEmpty()) {
            return MessageContext.empty();
        }
        List<Long> ids = page.stream().map(Message::getId).toList();
        return new MessageContext(chatAttachmentService.byMessageIds(ids), replyPreviews(page));
    }

    /**
     * Javob berilgan xabarlarning iqtiboslari — bitta so'rov.
     *
     * <p>Asl xabar o'chirilgan bo'lsa matn berilmaydi: uning mazmuni
     * lentada qolib ketmasligi kerak, kim yozgani esa qoladi.
     */
    private Map<Long, ChatReplyPreviewResponse> replyPreviews(List<Message> page) {
        Set<Long> targetIds = page.stream()
            .map(Message::getReplyToId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ChatReplyPreviewResponse> previews = new HashMap<>();
        for (Message target : messageRepository.findAllWithSender(targetIds)) {
            previews.put(target.getId(), ChatReplyPreviewResponse.builder()
                .id(target.getId())
                .senderName(fullName(target.getSender()))
                .text(target.getDeletedAt() == null ? target.getText() : null)
                .type(target.getType())
                .build());
        }
        return previews;
    }

    /**
     * O'chirilgan xabar metama'lumoti bilan qaytadi, mazmunisiz:
     * {@code text} va {@code attachments} null. Xabarning o'zi lentadan
     * yo'qolmaydi, aks holda javob zanjiri uzilib qolardi.
     */
    private ChatMessageResponse toMessageResponse(Message message, String clientId,
                                                   MessageContext context) {
        User sender = message.getSender();
        boolean deleted = message.getDeletedAt() != null;
        return ChatMessageResponse.builder()
            .id(message.getId())
            .uuid(message.getUuid())
            .conversationId(message.getConversation().getId())
            .senderId(sender.getId())
            .senderName(fullName(sender))
            .senderPhotoUrl(sender.getPhotoUrl())
            .text(deleted ? null : message.getText())
            .messageType(message.getType())
            .replyToId(message.getReplyToId())
            .attachments(deleted ? null : context.attachments().get(message.getId()))
            .replyTo(message.getReplyToId() == null
                ? null
                : context.replies().get(message.getReplyToId()))
            .clientId(clientId)
            .createdAt(message.getCreatedAt())
            .editedAt(message.getEditedAt())
            .deletedAt(message.getDeletedAt())
            .build();
    }

    private ChatUserResponse toUserResponse(User user) {
        return ChatUserResponse.builder()
            .id(user.getId())
            .fullName(fullName(user))
            .username(user.getUsername())
            .role(user.getRole())
            .photoUrl(user.getPhotoUrl())
            // Xotiradagi sanoqdan — qo'shimcha SQL yo'q.
            .online(chatPresenceService.isOnline(user.getId()))
            .lastSeenAt(user.getLastSeenAt())
            .build();
    }

    private static String fullName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }
}
