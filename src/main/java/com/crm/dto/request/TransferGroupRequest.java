package com.crm.dto.request;

import com.crm.entity.enums.StudyFormat;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferGroupRequest {
    /** Optional — if set, closes this active enrollment. */
    private Long fromGroupId;

    @NotNull(message = "{transferGroup.toGroupId.required}")
    private Long toGroupId;

    /**
     * Yangi guruhdagi o'qish formati. Berilmasa yopilayotgan yozuvdan
     * meros olinadi — pastdagi izohga qarang.
     */
    private StudyFormat studyFormat;

    private String reason;
    private String note;
}
