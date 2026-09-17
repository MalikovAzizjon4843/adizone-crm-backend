package com.crm.service;

import com.crm.dto.response.PresenceResponse;
import com.crm.entity.User;
import com.crm.repository.ConversationParticipantRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kim hozir onlayn.
 *
 * <p>Holat XOTIRADA, bazada emas: ilova bitta nusxada ishlaydi va ulanish
 * har uzilganda {@code UPDATE} yozish jadvalni bejiz qiynardi. Yagona
 * saqlanadigan narsa — {@code users.last_seen_at}, u ham faqat oxirgi
 * sessiya yopilganda.
 *
 * <p>Bayroq emas, SANOQ: bitta xodim ishni ikki tabda, telefonda va
 * noutbukda ochib qo'yishi odatiy hol. Bitta tab yopilganda u OFFLINE
 * bo'lib qolmasligi kerak, shuning uchun {@code userId -> sessiyalar
 * soni} va e'lon faqat chegara kesib o'tilganda (0↔1) chiqadi.
 *
 * <p>Ilova qayta ishga tushsa xarita bo'shaydi — bu to'g'ri, chunki
 * o'sha payt hamma WebSocket sessiyasi ham uziladi. Bunda
 * {@code last_seen_at} yozilmay qoladi: e'tiborsiz yo'qotish, chunki
 * mijozlar bir necha soniyada qayta ulanadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatPresenceService {

    /** Onlayn holat e'lon qilinadigan umumiy topik. */
    public static final String PRESENCE_TOPIC = "/topic/presence";

    /** {@code userId -> ochiq WebSocket sessiyalari soni}. Nol bo'lgan kalit saqlanmaydi. */
    private final ConcurrentHashMap<Long, Integer> sessionCounts = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final ConversationParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Sanoq ─────────────────────────────────────────────────────────

    /** Sessiya qo'shadi; {@code true} — foydalanuvchi endi ONLINE bo'ldi (0 → 1). */
    public boolean register(Long userId) {
        return sessionCounts.merge(userId, 1, Integer::sum) == 1;
    }

    /** Sessiya olib tashlaydi; {@code true} — foydalanuvchi endi OFFLINE bo'ldi (1 → 0). */
    public boolean unregister(Long userId) {
        AtomicBoolean wentOffline = new AtomicBoolean(false);
        // Atomik: sanoq va o'chirish orasida boshqa tab ulanib qolmasin.
        sessionCounts.computeIfPresent(userId, (key, count) -> {
            if (count <= 1) {
                wentOffline.set(true);
                return null;
            }
            return count - 1;
        });
        return wentOffline.get();
    }

    public boolean isOnline(Long userId) {
        return userId != null && sessionCounts.containsKey(userId);
    }

    // ── Spring hodisalari ─────────────────────────────────────────────

    /**
     * Mijoz STOMP CONNECT ni yakunladi.
     *
     * <p>{@code SessionConnected}, {@code SessionSubscribe} emas: ulanish
     * shu paytda haqiqatan tayyor va foydalanuvchi allaqachon handshake'da
     * tekshirilgan.
     */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        userOf(event.getUser()).ifPresent(user -> {
            if (register(user.getId())) {
                broadcast(user.getId(), true, user.getLastSeenAt());
                log.debug("Chat: {} onlayn", user.getUsername());
            }
        });
    }

    /**
     * Sessiya uzildi — tab yopildi, tarmoq tushdi yoki brauzer yangilandi.
     *
     * <p>{@code @Transactional} shu yerda kerak: hodisa broker oqimida
     * keladi va {@code touchLastSeenAt} uchun ochiq tranzaksiya yo'q.
     */
    @EventListener
    @Transactional
    public void onDisconnected(SessionDisconnectEvent event) {
        userOf(event.getUser()).ifPresent(user -> {
            if (unregister(user.getId())) {
                LocalDateTime lastSeenAt = LocalDateTime.now();
                userRepository.touchLastSeenAt(user.getId(), lastSeenAt);
                broadcast(user.getId(), false, lastSeenAt);
                log.debug("Chat: {} oflayn", user.getUsername());
            }
        });
    }

    private Optional<User> userOf(Principal principal) {
        if (principal == null || principal.getName() == null) {
            // Handshake tekshiruvidan keyin bunday bo'lmasligi kerak, lekin
            // hodisa oqimida istisno tashlash brokerni bejiz shovqinga
            // to'ldiradi — sessiya baribir yopilgan.
            return Optional.empty();
        }
        return userRepository.findByUsername(principal.getName());
    }

    private void broadcast(Long userId, boolean online, LocalDateTime lastSeenAt) {
        messagingTemplate.convertAndSend(PRESENCE_TOPIC, PresenceResponse.builder()
            .userId(userId)
            .online(online)
            .lastSeenAt(lastSeenAt)
            .build());
    }

    // ── Dastlabki holat ───────────────────────────────────────────────

    /**
     * Frontend ulangan zahoti so'raydigan surat: faqat joriy
     * foydalanuvchining suhbatdoshlari.
     *
     * <p>Barcha xodimlar emas — 100 kishilik markazda ro'yxatning katta
     * qismi hech qachon ekranda ko'rinmaydi.
     *
     * <p>Bitta SQL: onlaynlik xotiradan qo'shiladi.
     */
    @Transactional(readOnly = true)
    public List<PresenceResponse> peerPresence(User user) {
        List<Object[]> rows = participantRepository.findPeerPresence(user.getId());
        List<PresenceResponse> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long peerId = ((Number) row[0]).longValue();
            result.add(PresenceResponse.builder()
                .userId(peerId)
                .online(isOnline(peerId))
                .lastSeenAt((LocalDateTime) row[1])
                .build());
        }
        return result;
    }
}
