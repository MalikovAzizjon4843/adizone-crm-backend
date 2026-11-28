package com.crm.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** {@code POST /api/groups/{id}/transfer-students} — ommaviy ko'chirish (transfer oqimi bilan). */
@Data
public class TransferStudentsRequest {
    @NotNull(message = "{group.promote.targetRequired}")
    private Long targetGroupId;
    /** Berilmasa — guruhning barcha faol (muzlatilmagan) o'quvchilari. */
    @Size(max = 200, message = "{group.promote.studentsRequired}")
    private List<Long> studentIds;
    /** Yangi guruhga qo'shilish sanasi (bugun … bugun + 31); berilmasa — bugun. */
    private LocalDate date;
    @Size(max = 500)
    private String note;
}
