package com.crm.entity;

import com.crm.entity.converter.MetaFormTypeConverter;
import com.crm.entity.enums.MetaFormType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Meta (Facebook/Instagram) Lead Ads formasi va uning CRM sozlamalari.
 *
 * <p>Ikki xil ma'lumot bitta qatorda yashaydi va ular O'RTASIDA aniq
 * chegara bor:
 * <ul>
 *   <li><b>Meta niki</b> — {@code name}, {@code status}, {@code locale},
 *       {@code leadsCount}. Har sinxronizatsiyada ustiga yoziladi.</li>
 *   <li><b>Biznikilar</b> — {@code leadType}, {@code defaultStageCode},
 *       {@code defaultStudyFormat}, {@code defaultSource},
 *       {@code autoCreateTask}, {@code taskTimeQuestionKey},
 *       {@code active}. Sinxronizatsiya ularga HECH QACHON tegmaydi.</li>
 * </ul>
 * Chegara buzilsa operator har sinxronizatsiyadan keyin formalarni qaytadan
 * sozlashi kerak bo'lardi.
 *
 * <p>{@code status} (Meta dagi ACTIVE/ARCHIVED) va {@code active} (bizning
 * bayroq) ATAYLAB alohida: arxivlangan formadan ham eski lid kelib qolishi
 * mumkin, va biz faol formani vaqtincha o'chirib qo'yishimiz mumkin.
 */
@Entity
@Table(name = "meta_lead_forms", indexes = {
    @Index(name = "idx_meta_lead_forms_page", columnList = "page_id"),
    @Index(name = "idx_meta_lead_forms_type", columnList = "lead_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaLeadForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Meta dagi forma id si — bizning yagona ishonchli kalit.
     *
     * <p>Son emas, matn: Graph API 64-bit dan katta id larni ham matn
     * sifatida qaytaradi va JSON raqamiga aylantirilganda aniqlik yo'qoladi.
     */
    @Column(name = "form_id", nullable = false, unique = true, length = 64)
    private String formId;

    @Column(name = "page_id", length = 64)
    private String pageId;

    @Column(name = "name", length = 255)
    private String name;

    /** Meta dagi holat: {@code ACTIVE} | {@code ARCHIVED} | {@code DRAFT}. */
    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "locale", length = 16)
    private String locale;

    /** Meta hisoblagan lidlar soni — backfill hajmini baholash uchun. */
    @Column(name = "leads_count")
    private Integer leadsCount;

    @Convert(converter = MetaFormTypeConverter.class)
    @Column(name = "lead_type", nullable = false, length = 20)
    @Builder.Default
    private MetaFormType leadType = MetaFormType.UNMAPPED;

    /**
     * Lid qaysi bosqichda tug'ilishi — {@code lead_stages.code}.
     *
     * <p>JPA bog'lanishi yo'q, faqat matn: {@code Lead.status} ham shunday
     * saqlanadi va ikkisi bir xil qoidada qolishi kerak. Bo'sh bo'lsa
     * {@code Lead.DEFAULT_STATUS} ishlatiladi.
     */
    @Column(name = "default_stage_code", length = 50)
    private String defaultStageCode;

    /** {@code ONLINE} | {@code OFFLINE} | null — {@code Lead.format} ga tushadi. */
    @Column(name = "default_study_format", length = 20)
    private String defaultStudyFormat;

    /**
     * Marketing manbasi ({@code MarketingSource} nomi). Bo'sh bo'lsa
     * lid platformadan aniqlanadi: {@code ig} → INSTAGRAM, {@code fb} →
     * FACEBOOK.
     */
    @Column(name = "default_source", length = 30)
    private String defaultSource;

    @Column(name = "auto_create_task", nullable = false)
    @Builder.Default
    private Boolean autoCreateTask = false;

    /**
     * Qaysi savolning javobi vazifa sarlavhasiga vaqt bo'lib tushadi.
     *
     * <p>XOM {@code questionKey} — normalizatsiya qilinmaydi, chunki
     * {@code field_data} dagi kalit ham xom keladi va ikkisi aynan
     * solishtiriladi.
     */
    @Column(name = "task_time_question_key", length = 255)
    private String taskTimeQuestionKey;

    /**
     * Bizning bayroq. false bo'lsa formadan kelgan lid HAM yaratiladi —
     * bu faqat sozlash ro'yxatidagi ko'rinish belgisi. Lid tashlab
     * yuborish uchun {@code leadType = IGNORE} bor.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = false;

    /** Oxirgi marta Graph dan qachon o'qilgani. */
    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (leadType == null) {
            leadType = MetaFormType.UNMAPPED;
        }
        if (autoCreateTask == null) {
            autoCreateTask = false;
        }
        if (active == null) {
            active = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
