package com.crm.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * O'quvchi oqimi va retention ({@code GET /api/analytics/student-flow}). Hamma sonlar — YOZILMALAR
 * (o'quvchi ikki guruhda bo'lsa ikki marta); retention — o'quvchilar (distinct).
 */
public final class StudentFlowDtos {

    private StudentFlowDtos() {
    }

    /**
     * Shu oy real to'lov bilan yopilgan davri bor o'quvchilardan ({@code paidStudents}) keyingi oyda ham shunday
     * davri borlar ({@code retainedStudents}, istalgan guruhda). {@code final = false} — keyingi oy hali tugamagan.
     */
    public record Retention(long paidStudents, long retainedStudents, BigDecimal percent,
                            @JsonProperty(value = "final") boolean finalized) {
    }

    /**
     * Jami / o'qituvchi / guruh qatori. {@code lost = leftWithReason + leftNoReason}; {@code potential =
     * startActive + joined + transferredIn − completed − transferredOut}; {@code lostRevenue} — yo'qotilgan
     * yozilmalarning oylik narxi (chegirmadan keyin). {@code groups} — faqat o'qituvchi qatorida.
     */
    public record FlowRow(Long teacherId, String teacherName, Long groupId, String groupName, String groupStatus,
                          long startActive, long joined, long transferredIn, long completed,
                          long leftWithReason, Map<String, Long> leftByReason, long leftNoReason,
                          long transferredOut, long frozen, long endActive, long lost, long potential,
                          BigDecimal lostRevenue, Retention retentionNextMonth, List<FlowRow> groups) {
    }

    /** {@code asOf} — hisob sanasi (joriy oyda bugun); {@code monthFinal = false} — oy hali tugamagan. */
    public record Report(String month, LocalDate from, LocalDate to, LocalDate asOf, boolean monthFinal,
                         FlowRow total, List<FlowRow> teachers) {
    }

    /** Drill-down qatori; {@code date} — kirish (yangi / ko'chib kelgan) yoki chiqish sanasi. */
    public record StudentRow(Long studentId, String studentName, String phone, Long studentGroupId,
                             Long groupId, String groupName, Long teacherId, String teacherName,
                             String category, LocalDate date, String reasonCode, String reasonLabel,
                             String notes, BigDecimal monthlyPrice) {
    }
}
