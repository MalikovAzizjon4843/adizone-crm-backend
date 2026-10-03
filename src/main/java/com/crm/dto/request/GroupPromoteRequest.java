package com.crm.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** {@code POST /api/groups/{fromId}/promote[/preview]} — phase6-api §5. */
@Data
public class GroupPromoteRequest {
    @NotNull(message = "{group.promote.targetRequired}")
    private Long targetGroupId;
    @NotEmpty(message = "{group.promote.studentsRequired}")
    @Size(max = 200, message = "{group.promote.studentsRequired}")
    private List<Long> studentIds;
    /** Yangi guruhga qo'shilish sanasi (bugun … bugun + 31); berilmasa — bugun. */
    private LocalDate date;
    @Size(max = 500)
    private String note;
}
