package com.crm.dto.response;

import com.crm.entity.enums.TaskType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lid lentasining bitta yozuvi — barcha manbalar uchun yagona shakl.
 *
 * <p>Turga aloqasi yo'q maydonlar javobga umuman tushmaydi
 * ({@code NON_NULL}), shuning uchun bitta keng DTO frontend uchun
 * shovqin yaratmaydi.
 *
 * <p>{@code type} qiymatlari {@code LeadTimelineService} dagi
 * konstantalar: TASK_COMPLETED, TASK_CREATED, STATUS_CHANGED, NOTE,
 * ASSIGNEE_CHANGED, LEAD_CREATED. Enum emas — {@code AuditAction} kabi
 * kengaytiriladigan matn ro'yxati.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LeadTimelineItemDto {

    private String type;

    /** Voqea sodir bo'lgan payt — saralash shu bo'yicha. */
    private LocalDateTime at;

    /**
     * Voqeani bajargan odam. ASSIGNEE_CHANGED da {@code actorId} null
     * bo'ladi: audit yozuvi foydalanuvchi nomini saqlaydi, id sini emas.
     */
    private Long actorId;
    private String actorName;

    /** Vazifa sarlavhasi yoki lid nomi. Izohda bo'lmaydi. */
    private String title;

    /** Izoh matni yoki vazifa natijasi. */
    private String text;

    private TaskType taskType;
    private String taskTypeLabel;

    /** TASK_CREATED uchun rejalashtirilgan muddat. */
    private LocalDateTime dueAt;

    /** STATUS_CHANGED va ASSIGNEE_CHANGED uchun eski/yangi qiymat. */
    private String fromValue;
    private String toValue;

    /** Faqat STATUS_CHANGED: oldingi bosqichda necha kun turgan. */
    private Long daysInPrevious;

    /** Manba yozuvning id si (task, history, note, comment, auditLog yoki lead). */
    private Long refId;

    /**
     * NOTE turidagi yozuv tahrirlanadimi. Frontend tahrirlash menyusini
     * shu maydonga qarab ko'rsatadi.
     *
     * <p>{@code true} — {@code lead_notes} dan kelgan, PUT/DELETE bor.
     * {@code false} — eski {@code lead_comments} dan kelgan, o'zgarmas
     * (shu jumladan {@code updateStatus} avtomatik yozgan izohlar).
     * Boshqa turlarda null bo'ladi va {@code NON_NULL} tufayli javobga
     * umuman tushmaydi.
     *
     * <p>{@code refId} ikki xil jadvaldan kelishi mumkinligi uchun bu
     * maydon frontend uchun yagona ishonchli belgi. Backend tomonda esa
     * izoh endpointlari faqat {@code LeadNoteRepository} bilan ishlaydi,
     * ya'ni {@code editable=false} yozuvga PUT/DELETE yetib bormaydi.
     */
    private Boolean editable;
}
