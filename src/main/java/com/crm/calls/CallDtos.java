package com.crm.calls;

import com.crm.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * "Adizone Qo'ng'iroqlar" DTO lari (docs/design/calls.md). Vaqtlar ({@code LocalDateTime}) — Toshkent vaqti, zonasiz;
 * ilova yuboradigan {@code startedAt} — ISO-8601, zona bilan (masalan {@code 2026-11-21T05:12:00Z}).
 */
public final class CallDtos {

    private CallDtos() {
    }

    // ── Umumiy ───────────────────────────────────────────────────────────

    public record UserRef(Long id, String name, String role) {
        public static UserRef of(User u) {
            if (u == null) {
                return null;
            }
            String name = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
            return new UserRef(u.getId(), name.isEmpty() ? u.getUsername() : name,
                u.getRole() != null ? u.getRole().name() : null);
        }
    }

    public record Ref(Long id, String name) {
    }

    public record AppSettings(boolean autoLead, int syncMaxBatch, int syncMaxAgeDays, long recordingMaxBytes,
                              int recordingRetentionDays, int requestPollSeconds, boolean fcmEnabled,
                              String latestVersion, String minSupportedVersion) {
    }

    // ── Qurilma ──────────────────────────────────────────────────────────

    public record RegisterRequest(String deviceUid, String model, String androidVersion, String appVersion,
                                  String fcmToken) {
    }

    /**
     * {@code deviceToken} — FAQAT shu javobda. {@code syncFrom} — shu telefonda shu xodimning eng oxirgi sinxron
     * qilingan qo'ng'irog'i (qayta o'rnatishdan keyin shu vaqtdan davom etish; yo'q bo'lsa {@code null}).
     */
    public record RegisterResponse(Long deviceId, String deviceToken, UserRef user, LocalDateTime syncFrom,
                                   AppSettings settings) {
    }

    public record Permissions(Boolean callLog, Boolean recordingsFolder, Boolean batteryOptimizationIgnored) {
    }

    public record HeartbeatRequest(String appVersion, String androidVersion, String fcmToken, Permissions permissions) {
    }

    public record HeartbeatResponse(LocalDateTime serverTime, int pendingRequests, boolean updateAvailable,
                                    boolean upgradeRequired, AppSettings settings) {
    }

    public record DeviceItem(Long id, UserRef user, String deviceUid, String model, String androidVersion,
                             String appVersion, boolean active, LocalDateTime createdAt, LocalDateTime lastSeenAt,
                             LocalDateTime lastSyncAt, LocalDateTime revokedAt, Permissions permissions,
                             boolean pushRegistered) {
    }

    public record VersionResponse(String latest, String minSupported, String apkUrl) {
    }

    // ── Sinxron ──────────────────────────────────────────────────────────

    /**
     * @param key        qurilmadagi barqaror kalit (tavsiya: {@code CallLog._ID + ":" + DATE}), ≤ 100
     * @param direction  IN | OUT | MISSED | REJECTED
     * @param phone      telefondagi raqam (xom)
     * @param startedAt  boshlanish vaqti, zona bilan
     * @param durationSec suhbat davomiyligi, soniya (MISSED/REJECTED da 0)
     */
    public record SyncItem(String key, String direction, String phone, OffsetDateTime startedAt, Integer durationSec) {
    }

    public record SyncRequest(List<SyncItem> calls) {
    }

    /** {@code type}: LEAD | STUDENT | PARENT | UNKNOWN. */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record ContactRef(String type, Long leadId, Long studentId, Long parentId, String name,
                             Boolean autoLeadCreated) {
    }

    /**
     * {@code status}: CREATED | DUPLICATE | SKIPPED | REJECTED. {@code reason}: SKIPPED — EXCLUDED | STAFF | NO_NUMBER |
     * TOO_OLD; REJECTED — xato kodi ({@code calls.sync.*}).
     */
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public record SyncResult(String key, String status, Long callId, String reason, ContactRef contact) {
    }

    public record SyncResponse(int created, int duplicates, int skipped, int rejected, List<SyncResult> results) {
    }

    // ── Yozuv ────────────────────────────────────────────────────────────

    public record RecordingInfo(Long callId, String mime, long sizeBytes, Integer durationSec, String sha256,
                                LocalDateTime uploadedAt, boolean duplicate) {
    }

    public record RecordingRef(boolean available, String mime, long sizeBytes, Integer durationSec,
                               LocalDateTime uploadedAt, LocalDateTime deletedAt) {
    }

    public record RecordingLink(String url, LocalDateTime expiresAt) {
    }

    // ── Kiruvchi qo'ng'iroq kartasi ──────────────────────────────────────

    public record GroupRef(Long id, String name) {
    }

    /** {@code debtAmount} — faqat SUPER_ADMIN / ADMIN, qolganlarga {@code null}. */
    public record StudentCard(Long id, String name, String status, List<GroupRef> groups, boolean debtor,
                              Long debtDays, BigDecimal debtAmount) {
    }

    public record LeadCard(Long id, String stageCode, String stageName, String source, UserRef owner,
                           LocalDateTime createdAt) {
    }

    public record LastCall(Long id, LocalDateTime startedAt, String direction, String resultCode, String note,
                           UserRef by) {
    }

    public record TaskRef(Long id, String title, LocalDateTime dueAt, UserRef assignee) {
    }

    /**
     * {@code type}: LEAD | STUDENT | PARENT | UNKNOWN. {@code restricted = true} — kontakt bor, lekin joriy xodimga
     * tafsiloti ko'rinmaydi (SALES_MANAGER va boshqa operatorning lidi): faqat {@code type}, {@code name},
     * {@code lead.owner}.
     */
    public record LookupResponse(String phone, String type, String name, boolean restricted, LeadCard lead,
                                 StudentCard student, List<StudentCard> children, Ref parent, LastCall lastCall,
                                 TaskRef openTask, String crmPath) {
    }

    // ── Natija ───────────────────────────────────────────────────────────

    public record NextTask(String title, LocalDateTime dueAt, String type, String comment, Boolean allDay,
                           Long assigneeId) {
    }

    /**
     * {@code resultCode}: TALKED | NO_ANSWER | CALLBACK | THINKING | TRIAL_BOOKED | REJECTED | OTHER (majburiy).
     * {@code leadStageId} — faqat lid qo'ng'irog'ida; {@code nextTask} — lid / o'quvchi / bog'lanmagan vazifa.
     */
    public record ResultRequest(String resultCode, String note, Long leadStageId, NextTask nextTask) {
    }

    public record ResultResponse(CallItem call, boolean timelineWritten, boolean stageChanged, Long taskId) {
    }

    // ── CRM'dan qo'ng'iroq so'rovi ───────────────────────────────────────

    /** {@code userId} — kimning telefonida (faqat SA / ADMIN / SALES_HEAD boshqani tanlay oladi; standart — o'zi). */
    public record CreateRequest(String phone, Long leadId, Long studentId, Long userId) {
    }

    public record AckRequest(String status) {
    }

    public record RequestItem(Long id, Long userId, String phone, String status, Ref lead, Ref student,
                              String contactName, UserRef createdBy, LocalDateTime createdAt, LocalDateTime expiresAt,
                              LocalDateTime deliveredAt, LocalDateTime dialedAt, LocalDateTime closedAt) {
    }

    // ── Istisnolar ───────────────────────────────────────────────────────

    public record ExclusionItem(String phone, String label) {
    }

    public record ExclusionsRequest(List<ExclusionItem> items) {
    }

    // ── CRM ro'yxati va statistika ───────────────────────────────────────

    public record CallItem(Long id, String direction, String phone, String phoneRaw, LocalDateTime startedAt,
                           int durationSec, UserRef user, String contactType, Ref lead, Ref student, Ref parent,
                           boolean autoLeadCreated, String resultCode, String note, UserRef resultBy,
                           LocalDateTime resultAt, RecordingRef recording) {
    }

    public record StatsRow(Long userId, String name, long total, long incoming, long outgoing, long missed,
                           long rejected, long talkSeconds, long missedNotCalledBack) {
    }

    public record StatsResponse(LocalDate from, LocalDate to, List<StatsRow> rows, StatsRow totals) {
    }
}
