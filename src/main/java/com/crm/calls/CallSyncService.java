package com.crm.calls;

import com.crm.dashboard.LeadFunnelTracker;
import com.crm.entity.CallDevice;
import com.crm.entity.CallExclusion;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.PhoneCall;
import com.crm.entity.User;
import com.crm.exception.CodedException;
import com.crm.repository.CallDeviceRepository;
import com.crm.repository.CallExclusionRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code POST /api/calls/app/sync} (docs/design/calls.md §4.2).
 *
 * <p><b>Idempotentlik</b>: kalit — {@code (deviceId, key)}; bazada UNIQUE. Avval ko'rilgan kalit → DUPLICATE (o'sha
 * {@code callId} va kontakt), partiya ichidagi takror ham. Har element o'z tranzaksiyasida: bitta yaroqsiz element
 * butun partiyani yiqitmaydi; parallel ikki so'rov bir kalitni yozsa — UNIQUE, yutqazgani DUPLICATE qaytaradi.
 *
 * <p><b>Sinxron qilinmaydi</b> (SKIPPED, bazaga hech narsa yozilmaydi): xodimning shaxsiy raqami (exclusion), xodim
 * raqami, yashirin raqam, {@code app.calls.sync-max-age-days} dan eski qo'ng'iroq, qurilma {@code sync_from} idan
 * oldingi qo'ng'iroq (BEFORE_SYNC_FROM) va {@code sync_mode = KNOWN_ONLY} da CRM'ga mos kelmagan raqam
 * (UNKNOWN_CONTACT — avto-lid ham ochilmaydi; xodimning shaxsiy telefoni).
 *
 * <p><b>Avtomatik lid</b> ({@code app.calls.auto-lead}): notanish O'zbekiston raqamidan IN / MISSED → lid (manba
 * PHONE_CALL, mas'ul — qurilma egasi). Dedupe: avval {@link CallContactMatcher} (har qanday lid bo'lsa — yangisi
 * yaratilmaydi); bir raqam uchun parallel sinxronlar JVM qulfi bilan navbatga qo'yiladi (bitta ilova nusxasi).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallSyncService {

    static final String AUTO_LEAD_NAME = "Qo'ng'iroq";
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int MAX_FUTURE_MINUTES = 10;
    private static final Object[] PHONE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < PHONE_LOCKS.length; i++) {
            PHONE_LOCKS[i] = new Object();
        }
    }

    private final PhoneCallRepository callRepository;
    private final CallDeviceRepository deviceRepository;
    private final CallExclusionRepository exclusionRepository;
    private final UserRepository userRepository;
    private final CallDeviceService deviceService;
    private final CallContactMatcher matcher;
    private final CallLookupService lookupService;
    private final LeadService leadService;
    private final LeadFunnelTracker leadFunnelTracker;
    private final CallsProperties properties;
    private final TransactionTemplate tx;
    private final Clock billingClock;

    public CallDtos.SyncResponse sync(CallDevicePrincipal principal, CallDtos.SyncRequest request) {
        List<CallDtos.SyncItem> items = request == null || request.calls() == null ? List.of() : request.calls();
        if (items.size() > properties.getSyncMaxBatch()) {
            throw CodedException.badRequest("calls.sync.tooMany", properties.getSyncMaxBatch());
        }
        CallDevice device = deviceService.requireActive(principal.deviceId());
        User owner = userRepository.findById(principal.userId())
            .orElseThrow(() -> CodedException.forbidden("calls.device.userInactive"));
        LocalDateTime now = LocalDateTime.now(billingClock);
        LocalDateTime oldest = now.minusDays(properties.getSyncMaxAgeDays());

        Set<String> exclusions = exclusionRepository.findByUserIdOrderByIdAsc(owner.getId()).stream()
            .map(CallExclusion::getPhoneNormalized).collect(Collectors.toSet());
        Set<String> keys = items.stream().map(i -> i == null ? null : CallDeviceService.trimToNull(i.key()))
            .filter(k -> k != null && k.length() <= 100).collect(Collectors.toSet());
        Map<String, PhoneCall> existing = new HashMap<>();
        if (!keys.isEmpty()) {
            for (PhoneCall c : callRepository.findByDeviceIdAndDeviceCallKeyIn(device.getId(), keys)) {
                existing.put(c.getDeviceCallKey(), c);
            }
        }

        Map<String, Boolean> staffCache = new HashMap<>();
        Map<String, CallContactMatcher.Match> matchCache = new HashMap<>();
        Set<String> touchedPhones = new HashSet<>();
        List<CallDtos.SyncResult> results = new ArrayList<>(items.size());
        int created = 0;
        int duplicates = 0;
        int skipped = 0;
        int rejected = 0;

        for (CallDtos.SyncItem item : items) {
            CallDtos.SyncResult r = processOne(item, device, owner, now, oldest, exclusions, existing, staffCache,
                matchCache, touchedPhones);
            results.add(r);
            switch (r.status()) {
                case "CREATED" -> created++;
                case "DUPLICATE" -> duplicates++;
                case "SKIPPED" -> skipped++;
                default -> rejected++;
            }
        }

        tx.executeWithoutResult(s -> deviceRepository.findById(device.getId()).ifPresent(d -> {
            d.setLastSyncAt(now);
            d.setLastSeenAt(now);
            deviceRepository.save(d);
        }));
        touchedPhones.forEach(lookupService::evict);
        if (created > 0) {
            log.info("Qo'ng'iroqlar: qurilma #{} — {} yangi, {} takror, {} o'tkazildi, {} rad", device.getId(),
                created, duplicates, skipped, rejected);
        }
        return new CallDtos.SyncResponse(created, duplicates, skipped, rejected, results);
    }

    private CallDtos.SyncResult processOne(CallDtos.SyncItem item, CallDevice device, User owner, LocalDateTime now,
                                           LocalDateTime oldest, Set<String> exclusions,
                                           Map<String, PhoneCall> existing, Map<String, Boolean> staffCache,
                                           Map<String, CallContactMatcher.Match> matchCache,
                                           Set<String> touchedPhones) {
        String key = item == null ? null : CallDeviceService.trimToNull(item.key());
        if (key == null || key.length() > 100) {
            return rejected(key, "calls.sync.invalidKey");
        }
        PhoneCall dup = existing.get(key);
        if (dup != null) {
            return duplicate(key, dup);
        }
        PhoneCall.Direction direction = parseDirection(item.direction());
        if (direction == null) {
            return rejected(key, "calls.sync.invalidDirection");
        }
        if (item.startedAt() == null) {
            return rejected(key, "calls.sync.invalidStartedAt");
        }
        LocalDateTime startedAt = item.startedAt().atZoneSameInstant(billingClock.getZone()).toLocalDateTime();
        if (startedAt.isAfter(now.plusMinutes(MAX_FUTURE_MINUTES))) {
            return rejected(key, "calls.sync.invalidStartedAt");
        }
        int duration = item.durationSec() == null ? 0 : item.durationSec();
        if (duration < 0 || duration > 86_400) {
            return rejected(key, "calls.sync.invalidDuration");
        }
        if (direction.unanswered()) {
            duration = 0;
        }
        if (startedAt.isBefore(oldest)) {
            return skipped(key, "TOO_OLD");
        }
        if (device.getSyncFrom() != null && startedAt.isBefore(device.getSyncFrom())) {
            return skipped(key, "BEFORE_SYNC_FROM");
        }
        String normalized = CallContactMatcher.normalize(item.phone());
        if (normalized == null) {
            return skipped(key, "NO_NUMBER");
        }
        if (exclusions.contains(normalized)) {
            return skipped(key, "EXCLUDED");
        }
        String canonical = CallContactMatcher.canonical(normalized);
        if (canonical != null && staffCache.computeIfAbsent(canonical, matcher::isStaff)) {
            return skipped(key, "STAFF");
        }

        if (device.effectiveSyncMode() == CallDevice.SyncMode.KNOWN_ONLY) {
            CallContactMatcher.Match known = canonical == null ? CallContactMatcher.Match.UNKNOWN
                : matchCache.computeIfAbsent(canonical, matcher::match);
            if (known.type() == PhoneCall.ContactType.UNKNOWN) {
                return skipped(key, "UNKNOWN_CONTACT");
            }
        }

        final int dur = duration;
        final String raw = CallDeviceService.cut(item.phone(), 40);
        try {
            return withPhoneLock(canonical, () -> tx.execute(s -> {
                CallContactMatcher.Match match = canonical == null ? CallContactMatcher.Match.UNKNOWN
                    : matchCache.computeIfAbsent(canonical, matcher::match);
                boolean autoLead = false;
                if (match.type() == PhoneCall.ContactType.UNKNOWN && canonical != null && properties.isAutoLead()
                        && device.effectiveSyncMode() == CallDevice.SyncMode.ALL
                        && (direction == PhoneCall.Direction.IN || direction == PhoneCall.Direction.MISSED)) {
                    Lead lead = leadService.createFromCall(AUTO_LEAD_NAME + " " + canonical, canonical, owner,
                        autoLeadNote(direction, startedAt));
                    match = new CallContactMatcher.Match(PhoneCall.ContactType.LEAD, lead.getId(), null, null,
                        lead.getFullName());
                    autoLead = true;
                }
                PhoneCall call = callRepository.saveAndFlush(PhoneCall.builder()
                    .deviceId(device.getId())
                    .userId(owner.getId())
                    .direction(direction)
                    .phoneRaw(raw)
                    .phoneNormalized(normalized)
                    .startedAt(startedAt)
                    .durationSec(dur)
                    .deviceCallKey(key)
                    .contactType(match.type())
                    .leadId(match.leadId())
                    .studentId(match.studentId())
                    .parentId(match.parentId())
                    .autoLeadCreated(autoLead)
                    .createdAt(now)
                    .build());
                if (match.leadId() != null && (direction == PhoneCall.Direction.OUT
                        || (direction == PhoneCall.Direction.IN && dur > 0))) {
                    // Direktor dashboardi: qo'ng'iroq ham operatorning javobi (§1.7)
                    leadFunnelTracker.onOperatorAction(match.leadId(), owner, LeadAssignment.KIND_CALL, startedAt);
                }
                if (autoLead) {
                    // Keyingi elementlar shu raqamni endi oddiy LEAD deb ko'rsin (tranzaksiya commit bo'lgach)
                    matchCache.put(canonical, new CallContactMatcher.Match(PhoneCall.ContactType.LEAD,
                        match.leadId(), null, null, match.name()));
                }
                existing.put(key, call);
                touchedPhones.add(normalized);
                return new CallDtos.SyncResult(key, "CREATED", call.getId(), null, match.toRef(autoLead));
            }));
        } catch (DataIntegrityViolationException e) {
            // Parallel so'rov shu kalitni allaqachon yozgan
            matchCache.remove(canonical == null ? "" : canonical);
            PhoneCall other = callRepository.findByDeviceIdAndDeviceCallKey(device.getId(), key).orElse(null);
            if (other != null) {
                existing.put(key, other);
                return duplicate(key, other);
            }
            log.warn("Qo'ng'iroqlar: sinxron elementi yozilmadi (qurilma #{}, kalit {}): {}", device.getId(), key,
                e.getMostSpecificCause().getMessage());
            return rejected(key, "calls.sync.invalid");
        } catch (CodedException e) {
            matchCache.remove(canonical == null ? "" : canonical);
            return rejected(key, e.getCode());
        }
    }

    private static <T> T withPhoneLock(String canonical, java.util.function.Supplier<T> body) {
        if (canonical == null) {
            return body.get();
        }
        synchronized (PHONE_LOCKS[Math.floorMod(canonical.hashCode(), PHONE_LOCKS.length)]) {
            return body.get();
        }
    }

    private static String autoLeadNote(PhoneCall.Direction direction, LocalDateTime at) {
        return (direction == PhoneCall.Direction.MISSED ? "Javobsiz qo'ng'iroq" : "Kiruvchi qo'ng'iroq")
            + " — " + NOTE_TIME.format(at) + " (avtomatik lid)";
    }

    static PhoneCall.Direction parseDirection(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PhoneCall.Direction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CallDtos.SyncResult duplicate(String key, PhoneCall c) {
        CallContactMatcher.Match m = new CallContactMatcher.Match(c.getContactType(), c.getLeadId(), c.getStudentId(),
            c.getParentId(), null);
        return new CallDtos.SyncResult(key, "DUPLICATE", c.getId(), null, m.toRef(c.isAutoLeadCreated()));
    }

    private static CallDtos.SyncResult skipped(String key, String reason) {
        return new CallDtos.SyncResult(key, "SKIPPED", null, reason, null);
    }

    private static CallDtos.SyncResult rejected(String key, String code) {
        return new CallDtos.SyncResult(key, "REJECTED", null, code, null);
    }
}
