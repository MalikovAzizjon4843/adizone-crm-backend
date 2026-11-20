package com.crm.appeal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Murojaatlar API DTO lari (docs/audit/api-inventory.md, miniapp-api.md §7b). {@code telegram_chat_id} hech bir
 * DTO da yo'q; anonim murojaatda yuboruvchi bloki ({@link Sender}) {@code null}.
 */
public final class AppealDtos {

    private AppealDtos() {
    }

    public record Ref(Long id, String name) {
    }

    // ── CRM ──────────────────────────────────────────────────────────────

    /** Ro'yxat qatori. {@code senderName} — anonimda null. */
    public record ListItem(Long id, String number, String type, String status, boolean anonymous, String source,
                           String textPreview, String senderName, Ref assignee, Ref teacher, int messagesCount,
                           LocalDateTime lastMessageAt, LocalDateTime createdAt, LocalDateTime firstResponseAt,
                           LocalDateTime resolvedAt) {
    }

    /**
     * Yuboruvchi (faqat anonim EMAS murojaatda). {@code linked} — Mini App / botga ulangan identity'dan;
     * {@code phone} — ulangan bo'lsa identity telefoni, aks holda kiritilgan raqam.
     */
    public record Sender(boolean linked, String kind, String name, String phone, Ref student, Ref parent) {
    }

    public record Message(Long id, String authorType, Ref author, String text, boolean internal, boolean delivered,
                          LocalDateTime createdAt) {
    }

    public record Detail(Long id, String number, String type, String status, boolean anonymous, String source,
                         String language, String text, Sender sender, Ref assignee, Ref teacher,
                         LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime firstResponseAt,
                         LocalDateTime resolvedAt, List<Message> messages) {
    }

    /**
     * PATCH: berilgan maydonlargina o'zgaradi. {@code assigneeUserId: null} / {@code teacherId: null} — tozalash
     * (maydon umuman berilmasa — o'zgarmaydi).
     */
    @Getter
    public static class PatchRequest {
        private String status;
        private Long assigneeUserId;
        @JsonIgnore
        private boolean assigneeSet;
        private Long teacherId;
        @JsonIgnore
        private boolean teacherSet;

        @JsonSetter(value = "status")
        public void setStatus(String status) {
            this.status = status;
        }

        @JsonSetter(value = "assigneeUserId")
        public void setAssigneeUserId(Long assigneeUserId) {
            this.assigneeUserId = assigneeUserId;
            this.assigneeSet = true;
        }

        @JsonSetter(value = "teacherId")
        public void setTeacherId(Long teacherId) {
            this.teacherId = teacherId;
            this.teacherSet = true;
        }
    }

    public record StaffMessageRequest(String text, Boolean internal) {
    }

    /** Sidebar badge: {@code {"new": 3, "inProgress": 5}} — joriy xodim ko'radiganlari bo'yicha. */
    public record Counts(@JsonProperty(value = "new") long newCount, long inProgress) {
    }

    public record Stats(String from, String to, long total, Map<String, Long> byType, Map<String, Long> byStatus,
                        long responded, Long avgFirstResponseMinutes, long resolved, Long avgResolutionMinutes) {
    }

    // ── Mini App ─────────────────────────────────────────────────────────

    public record AppMessage(Long id, String authorType, String text, LocalDateTime createdAt) {
    }

    public record AppItem(Long id, String number, String type, String status, boolean anonymous, String text,
                          Ref teacher, LocalDateTime createdAt, LocalDateTime resolvedAt, List<AppMessage> messages) {
    }

    /** {@code GET /api/app/appeals}: o'z murojaatlari va yangi murojaat formasi uchun ma'lumot. */
    public record AppList(boolean enabled, int dailyLimit, int remainingToday, int textMax, List<String> types,
                          List<Ref> teachers, List<AppItem> items) {
    }

    public record AppCreateRequest(String type, String text, Boolean anonymous, Long studentId, Long teacherId) {
    }

    public record AppMessageRequest(String text) {
    }
}
