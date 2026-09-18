package com.crm.dto.request;

import com.crm.entity.enums.MetaFormType;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Forma sozlamalari. FAQAT bizning maydonlar - Meta dan keladigan
 * {@code name}, {@code status}, {@code locale}, {@code leadsCount} bu
 * yerda yo'q va ularni tahrirlab bo'lmaydi.
 *
 * <p>Maydon null bo'lsa u TEGILMAYDI (joriy qiymat qoladi). Qiymatni
 * tozalash uchun bo'sh matn yuboriladi. Shunda frontend faqat
 * o'zgartirgan maydonini yuborishi mumkin va ikkita operator bir-birining
 * sozlamasini tasodifan o'chirib yubormaydi.
 */
@Data
public class MetaFormSettingsRequest {

    private MetaFormType leadType;

    /** {@code lead_stages.code} - mavjudligi tekshiriladi, yo'q bo'lsa 400. */
    @Size(max = 50)
    private String defaultStageCode;

    /** ONLINE | OFFLINE. */
    @Size(max = 20)
    private String defaultStudyFormat;

    /** {@code MarketingSource} nomi - tekshiriladi. */
    @Size(max = 30)
    private String defaultSource;

    private Boolean autoCreateTask;

    /** XOM {@code questionKey} - shu formada mavjudligi tekshiriladi. */
    @Size(max = 255)
    private String taskTimeQuestionKey;

    private Boolean active;
}
