package com.crm.calls;

import com.crm.entity.CallDevice;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CallDeviceRepository;
import com.crm.repository.CallRequestRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Qurilmalar (docs/design/calls.md §3): ro'yxatdan o'tkazish (JWT bilan) → uzoq muddatli qurilma tokeni (bir marta
 * ko'rsatiladi, bazada SHA-256), har so'rovda tekshirish, revoke.
 *
 * <p>Qoidalar: bir xodim — bitta faol qurilma; bitta telefon ({@code deviceUid}) — bitta faol hisob. Yangi ro'yxat
 * eskilarini revoke qiladi; aynan shu xodim + shu telefon qayta ro'yxatdan o'tsa — o'sha qator, yangi token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallDeviceService {

    /** Ilovadan foydalana oladigan rollar. */
    public static final Set<UserRole> ALLOWED_ROLES =
        EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_HEAD, UserRole.SALES_MANAGER);

    static final String TOKEN_PREFIX = "cd_";
    private static final long TOUCH_INTERVAL_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CallDeviceRepository deviceRepository;
    private final CallRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final CallsProperties properties;
    private final Clock billingClock;

    public record AuthResult(boolean ok, CallDevice device, User user, String reasonCode) {
        static AuthResult fail(String code) {
            return new AuthResult(false, null, null, code);
        }
    }

    // ── Ro'yxatdan o'tkazish ─────────────────────────────────────────────

    @Transactional
    public CallDtos.RegisterResponse register(User user, CallDtos.RegisterRequest request) {
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || !ALLOWED_ROLES.contains(user.getRole())) {
            throw CodedException.forbidden("calls.role.notAllowed");
        }
        String uid = request == null ? null : trimToNull(request.deviceUid());
        if (uid == null || uid.length() > 100) {
            throw CodedException.badRequest("calls.device.uidInvalid");
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        CallDevice.SyncMode requestedMode = parseSyncMode(request.syncMode());
        LocalDateTime requestedFrom = parseSyncFrom(request.syncFrom(), now);
        String token = newToken();
        String hash = sha256(token);

        CallDevice same = null;
        for (CallDevice d : deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(user.getId())) {
            if (same == null && d.getDeviceUid().equals(uid)) {
                same = d;
            } else {
                revoke(d, now, null);
            }
        }
        for (CallDevice d : deviceRepository.findByDeviceUidAndRevokedAtIsNull(uid)) {
            if (same == null || !d.getId().equals(same.getId())) {
                revoke(d, now, null);
            }
        }
        // Qisman UNIQUE indekslar: revoke UPDATE lari INSERT dan oldin bazaga tushsin (Hibernate avval INSERT qiladi)
        deviceRepository.flush();

        // Sinxron sozlamasi — faqat yangi qatorda (o'sha xodim + o'sha telefon qayta kirsa saqlangani qoladi)
        CallDevice device = same != null ? same : CallDevice.builder()
            .userId(user.getId())
            .deviceUid(uid)
            .createdAt(now)
            .syncMode(requestedMode != null ? requestedMode : CallDevice.SyncMode.ALL)
            .syncFrom(requestedFrom != null ? requestedFrom : now.withNano(0))
            .build();
        device.setTokenHash(hash);
        device.setModel(cut(request.model(), 100));
        device.setAndroidVersion(cut(request.androidVersion(), 30));
        device.setAppVersion(cut(request.appVersion(), 30));
        if (request.fcmToken() != null) {
            device.setFcmToken(cut(trimToNull(request.fcmToken()), 512));
        }
        device.setLastSeenAt(now);
        device = deviceRepository.save(device);
        log.info("Qo'ng'iroqlar: qurilma #{} ro'yxatdan o'tdi (user #{}, {})", device.getId(), user.getId(),
            same != null ? "token yangilandi" : "yangi");

        LocalDateTime lastCall = deviceRepository.lastCallStartedAt(user.getId(), uid);
        LocalDateTime resumeFrom = lastCall == null ? device.getSyncFrom()
            : device.getSyncFrom() == null || lastCall.isAfter(device.getSyncFrom()) ? lastCall : device.getSyncFrom();
        return new CallDtos.RegisterResponse(device.getId(), token, CallDtos.UserRef.of(user), resumeFrom, settings(),
            deviceSettings(device));
    }

    // ── Qurilma sinxron sozlamasi (GET/PUT app/settings) ─────────────────

    @Transactional(readOnly = true)
    public CallDtos.DeviceSettings getSettings(CallDevicePrincipal principal) {
        return deviceSettings(requireActive(principal.deviceId()));
    }

    /** Berilmagan maydon o'zgarmaydi. {@code syncFrom} kelajakda (> 10 daqiqa) bo'lmasin. */
    @Transactional
    public CallDtos.DeviceSettings updateSettings(CallDevicePrincipal principal, CallDtos.DeviceSettingsRequest request) {
        CallDevice device = requireActive(principal.deviceId());
        if (request != null) {
            LocalDateTime now = LocalDateTime.now(billingClock);
            CallDevice.SyncMode mode = parseSyncMode(request.syncMode());
            if (mode != null) {
                device.setSyncMode(mode);
            }
            LocalDateTime from = parseSyncFrom(request.syncFrom(), now);
            if (from != null) {
                device.setSyncFrom(from);
            }
            deviceRepository.save(device);
            log.info("Qo'ng'iroqlar: qurilma #{} sozlamasi: {} / {}", device.getId(), device.effectiveSyncMode(),
                device.getSyncFrom());
        }
        return deviceSettings(device);
    }

    CallDtos.DeviceSettings deviceSettings(CallDevice d) {
        return new CallDtos.DeviceSettings(d.effectiveSyncMode().name(), d.getSyncFrom(), properties.getCrmWebUrl());
    }

    private static CallDevice.SyncMode parseSyncMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CallDevice.SyncMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("calls.settings.syncModeInvalid");
        }
    }

    private LocalDateTime parseSyncFrom(java.time.OffsetDateTime raw, LocalDateTime now) {
        if (raw == null) {
            return null;
        }
        LocalDateTime at = raw.atZoneSameInstant(billingClock.getZone()).toLocalDateTime().withNano(0);
        if (at.isAfter(now.plusMinutes(10))) {
            throw CodedException.badRequest("calls.settings.syncFromFuture");
        }
        return at;
    }

    // ── Har so'rovda ─────────────────────────────────────────────────────

    /** Token → qurilma va egasi. {@code lastSeenAt} daqiqasiga ko'pi bilan bir marta yangilanadi. */
    @Transactional
    public AuthResult authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX) || token.length() > 200) {
            return AuthResult.fail("calls.device.unauthorized");
        }
        CallDevice device = deviceRepository.findByTokenHash(sha256(token)).orElse(null);
        if (device == null) {
            return AuthResult.fail("calls.device.unauthorized");
        }
        if (!device.isActive()) {
            return AuthResult.fail("calls.device.revoked");
        }
        User user = userRepository.findById(device.getUserId()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || !ALLOWED_ROLES.contains(user.getRole())) {
            return AuthResult.fail("calls.device.userInactive");
        }
        LocalDateTime now = LocalDateTime.now(billingClock);
        if (device.getLastSeenAt() == null || device.getLastSeenAt().plusSeconds(TOUCH_INTERVAL_SECONDS).isBefore(now)) {
            device.setLastSeenAt(now);
        }
        return new AuthResult(true, device, user, null);
    }

    @Transactional(readOnly = true)
    public CallDevice requireActive(Long deviceId) {
        CallDevice device = deviceRepository.findById(deviceId)
            .orElseThrow(() -> new CodedException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "calls.device.unauthorized"));
        if (!device.isActive()) {
            throw new CodedException(org.springframework.http.HttpStatus.UNAUTHORIZED, "calls.device.revoked");
        }
        return device;
    }

    // ── Heartbeat / chiqish ──────────────────────────────────────────────

    @Transactional
    public CallDtos.HeartbeatResponse heartbeat(CallDevicePrincipal principal, CallDtos.HeartbeatRequest request) {
        CallDevice device = requireActive(principal.deviceId());
        LocalDateTime now = LocalDateTime.now(billingClock);
        device.setLastSeenAt(now);
        device.setLastHeartbeatAt(now);
        if (request != null) {
            if (request.appVersion() != null) {
                device.setAppVersion(cut(request.appVersion(), 30));
            }
            if (request.androidVersion() != null) {
                device.setAndroidVersion(cut(request.androidVersion(), 30));
            }
            if (request.fcmToken() != null) {
                device.setFcmToken(cut(trimToNull(request.fcmToken()), 512));
            }
            CallDtos.Permissions p = request.permissions();
            if (p != null) {
                device.setPermCallLog(p.callLog());
                device.setPermRecordings(p.recordingsFolder());
                device.setPermBatteryOpt(p.batteryOptimizationIgnored());
            }
        }
        deviceRepository.save(device);
        int pending = (int) requestRepository.findByUserIdAndStatusInOrderByIdDesc(device.getUserId(),
                List.of(com.crm.entity.CallRequest.Status.PENDING, com.crm.entity.CallRequest.Status.DELIVERED))
            .stream().filter(r -> r.getExpiresAt().isAfter(now)).count();
        String version = device.getAppVersion();
        CallsProperties.App app = properties.getApp();
        return new CallDtos.HeartbeatResponse(now, pending,
            version != null && AppVersions.compare(version, app.getLatestVersion()) < 0,
            version != null && AppVersions.compare(version, app.getMinSupportedVersion()) < 0,
            settings());
    }

    /** Ilovadan chiqish — qurilma o'zini revoke qiladi. */
    @Transactional
    public void logout(CallDevicePrincipal principal) {
        deviceRepository.findById(principal.deviceId())
            .filter(CallDevice::isActive)
            .ifPresent(d -> revoke(d, LocalDateTime.now(billingClock), principal.userId()));
    }

    // ── SA: ro'yxat va revoke ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CallDtos.DeviceItem> list() {
        List<CallDevice> devices = deviceRepository.findAllByOrderByRevokedAtAscLastSeenAtDescIdDesc();
        Map<Long, User> users = userRepository.findAllById(devices.stream().map(CallDevice::getUserId)
                .collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        return devices.stream().map(d -> toItem(d, users.get(d.getUserId()))).toList();
    }

    @Transactional
    public CallDtos.DeviceItem revokeById(Long id, User actor) {
        CallDevice device = deviceRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("CallDevice", id));
        if (device.isActive()) {
            revoke(device, LocalDateTime.now(billingClock), actor != null ? actor.getId() : null);
            log.info("Qo'ng'iroqlar: qurilma #{} revoke qilindi (user #{})", id, actor != null ? actor.getId() : null);
        }
        return toItem(device, userRepository.findById(device.getUserId()).orElse(null));
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    public CallDtos.AppSettings settings() {
        CallsProperties.App app = properties.getApp();
        return new CallDtos.AppSettings(properties.isAutoLead(), properties.getSyncMaxBatch(),
            properties.getSyncMaxAgeDays(), properties.getRecordingMaxBytes(), properties.getRecordingRetentionDays(),
            properties.getRequestPollSeconds(), properties.getFcm().isEnabled(), app.getLatestVersion(),
            app.getMinSupportedVersion(), properties.getCrmWebUrl());
    }

    private void revoke(CallDevice device, LocalDateTime now, Long by) {
        device.setRevokedAt(now);
        device.setRevokedBy(by);
        deviceRepository.save(device);
    }

    private static CallDtos.DeviceItem toItem(CallDevice d, User user) {
        return new CallDtos.DeviceItem(d.getId(), user != null ? CallDtos.UserRef.of(user) : null, d.getDeviceUid(),
            d.getModel(), d.getAndroidVersion(), d.getAppVersion(), d.isActive(), d.getCreatedAt(), d.getLastSeenAt(),
            d.getLastSyncAt(), d.getRevokedAt(),
            new CallDtos.Permissions(d.getPermCallLog(), d.getPermRecordings(), d.getPermBatteryOpt()),
            d.getFcmToken() != null, d.effectiveSyncMode().name(), d.getSyncFrom(), d.getLastHeartbeatAt());
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
