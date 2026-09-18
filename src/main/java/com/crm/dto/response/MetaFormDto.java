package com.crm.dto.response;

import com.crm.entity.enums.MetaFormType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Formalar ro'yxatidagi bitta qator. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFormDto {

    private Long id;
    private String formId;
    private String pageId;
    private String name;

    /** Meta dagi holat: ACTIVE | ARCHIVED. */
    private String status;

    private String locale;
    private Integer leadsCount;

    private MetaFormType leadType;
    private String defaultStageCode;
    private String defaultStudyFormat;
    private String defaultSource;
    private Boolean autoCreateTask;
    private String taskTimeQuestionKey;

    /** Bizning bayroq - Meta ning {@code status} i bilan aralashtirmang. */
    private Boolean active;

    private Instant syncedAt;

    private long questionsCount;

    /** {@code crmField} qo'yilmagan savollar - sozlash kerakligining belgisi. */
    private long unmappedQuestionsCount;
}
