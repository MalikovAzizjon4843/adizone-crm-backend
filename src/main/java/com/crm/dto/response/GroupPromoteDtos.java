package com.crm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Guruhga ko'chirish javoblari — phase6-api §5. */
public final class GroupPromoteDtos {

    private GroupPromoteDtos() {
    }

    /** {@code blocking = true} — bu ogohlantirish bilan ko'chirib bo'lmaydi (apply → 409). */
    public record Warning(String code, boolean blocking) {
    }

    public record GroupInfo(Long id, String name, String status, String courseName, Integer maxStudents,
                            long activeStudents, Integer freeSeats) {
    }

    public record StudentRow(Long studentId, String studentName, Long studentGroupId, String paymentType,
                             BigDecimal oldPrice, BigDecimal newPrice, BigDecimal priceDiff,
                             BigDecimal balance, BigDecimal debt, LocalDate nextBillingDate,
                             List<Warning> warnings) {
    }

    public record Preview(GroupInfo fromGroup, GroupInfo targetGroup, LocalDate date, boolean canApply,
                          List<Warning> warnings, List<StudentRow> students) {
    }

    public record Moved(Long studentId, String studentName, Long fromStudentGroupId, Long toStudentGroupId,
                        BigDecimal movedBalance) {
    }

    public record Result(Long batchId, Long fromGroupId, Long targetGroupId, LocalDate date, List<Moved> students,
                         Boolean idempotentReplay) {
        public Result replay() {
            return new Result(batchId, fromGroupId, targetGroupId, date, students, Boolean.TRUE);
        }
    }
}
