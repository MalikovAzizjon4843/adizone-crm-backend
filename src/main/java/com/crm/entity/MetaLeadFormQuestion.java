package com.crm.entity;

import com.crm.entity.converter.MetaCrmFieldConverter;
import com.crm.entity.enums.MetaCrmField;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Meta formasidagi bitta savol va uning CRM maydoniga bog'lanishi.
 *
 * <p><b>{@code questionKey} XOM saqlanadi.</b> Meta uni foydalanuvchi
 * yozgan savol matnidan yasaydi, shuning uchun ichida apostrof, {@code ?},
 * {@code /} va oxirida {@code _} bo'ladi — masalan
 * {@code o'qishni_qachondan_boshlamoqchisiz?}. {@code field_data} ham
 * AYNAN shu kalitni qaytaradi, shuning uchun normalizatsiya qilish
 * mumkin emas: bir tomonda tozalab, ikkinchi tomonda tozalamasak,
 * javob hech qachon savoliga tushmaydi.
 *
 * <p>{@code crmField} null bo'lishi mumkin — "hali sozlanmagan". Yangi
 * savol paydo bo'lganda sinxronizatsiya uni taxmin bilan to'ldiradi,
 * MAVJUD savolning qiymatiga esa hech qachon tegmaydi.
 */
@Entity
@Table(name = "meta_lead_form_questions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_meta_form_question", columnNames = {"form_id", "question_key"}),
    indexes = {
        @Index(name = "idx_meta_form_questions_form", columnList = "form_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaLeadFormQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false)
    private MetaLeadForm form;

    /** Meta dagi {@code key}. Xom holicha — yuqoridagi izohga qarang. */
    @Column(name = "question_key", nullable = false, length = 255)
    private String questionKey;

    /** Foydalanuvchi ko'rgan savol matni — izohlarda shu ishlatiladi. */
    @Column(name = "label", length = 512)
    private String label;

    /** Meta dagi tur: {@code CUSTOM}, {@code PHONE}, {@code FULL_NAME}, ... */
    @Column(name = "type", length = 64)
    private String type;

    /**
     * Variantlar lug'ati {@code {optionKey: label}} — JSON matn sifatida.
     *
     * <p>Kerak, chunki {@code field_data} javobda KALIT ni qaytaradi
     * ({@code ha_qulay_borib_o'qiy_olaman_}), foydalanuvchi ko'rgan matnni
     * emas. Lidning izohiga kalitni yozib qo'ysak, uni o'qigan operator
     * javobni tushunmaydi.
     */
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Convert(converter = MetaCrmFieldConverter.class)
    @Column(name = "crm_field", length = 32)
    private MetaCrmField crmField;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
