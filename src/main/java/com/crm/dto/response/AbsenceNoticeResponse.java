package com.crm.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * O'quvchi / ota-onaning sabab bildirishi (telegram-platform §11.2) — CRM ro'yxati va davomat qatoridagi
 * {@code absenceNotice}. {@code type}: ABSENT | LATE | OTHER; {@code submittedAs}: SELF | PARENT.
 */
public record AbsenceNoticeResponse(Long id, Long studentId, String studentName, Long groupId, String groupName,
                                    LocalDate lessonDate, String type, String comment, String status,
                                    String submittedAs, LocalDateTime createdAt) {
}
