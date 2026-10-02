package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** "Shu guruhning shu sanadagi darsini X o'tdi" — leaves-exams-contracts §2.2 (SA, A). */
@Data
public class SubstitutionRequest {

    @NotNull(message = "{substitution.groupId.required}")
    private Long groupId;

    @NotNull(message = "{substitution.lessonDate.required}")
    private LocalDate lessonDate;

    @NotNull(message = "{substitution.substituteTeacherId.required}")
    private Long substituteTeacherId;

    /** Ta'til bilan bog'liq bo'lsa (ixtiyoriy). */
    private Long leaveRequestId;

    private String note;
}
