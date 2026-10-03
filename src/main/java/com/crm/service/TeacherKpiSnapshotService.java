package com.crm.service;

import com.crm.dto.response.TeacherKpiScoresDto;
import com.crm.entity.Teacher;
import com.crm.entity.TeacherKpiMonthly;
import com.crm.exception.BadRequestException;
import com.crm.repository.GroupRepository;
import com.crm.repository.TeacherKpiMonthlyRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O'qituvchi KPI oy yakuni snapshot'i ({@code teacher_kpi_monthly}, V72).
 *
 * <p>Har oyning 1-kuni 01:00 (Asia/Tashkent) da o'tgan oy yoziladi. To'lov ko'rsatkichlari shu
 * paytdagi holat bo'yicha: muddati oyning oxirgi kunlarida kelib, grace i hali tugamagan va
 * to'lanmagan davrlar "kutilmoqda" ({@code periods_pending}) — maxrajga kirmaydi. Ular aniq
 * bo'lgach (yoki tarixiy ma'lumot tuzatilgach) SUPER_ADMIN oyni qayta hisoblaydi:
 * {@code POST /api/teachers/kpi/snapshots?month=YYYY-MM}.
 *
 * <p>Qaysi o'qituvchilar: faol o'qituvchilar + oyda ma'lumoti bor har kim. Ro'yxatdan chiqib ketgan
 * o'qituvchining eski qatori qayta hisoblashda o'chiriladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherKpiSnapshotService {

    private final TeacherKpiService kpiService;
    private final TeacherKpiMonthlyRepository repository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final com.crm.billing.BillingStatusService billingStatusService;
    private final Clock billingClock;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${app.kpi.monthly-snapshot-cron:0 0 1 1 * *}", zone = "Asia/Tashkent")
    public void monthly() {
        YearMonth previous = YearMonth.from(billingStatusService.today()).minusMonths(1);
        try {
            Integer rows = transactionTemplate.execute(s -> snapshotMonth(previous, TeacherKpiMonthly.SOURCE_JOB));
            log.info("O'qituvchi KPI snapshot {}: {} qator", previous, rows);
        } catch (RuntimeException e) {
            log.error("O'qituvchi KPI snapshot {} yozilmadi: {}", previous, e.getMessage(), e);
        }
    }

    /** SUPER_ADMIN: yopilgan oyni qayta hisoblash (ustidan yozadi). Joriy / kelajak oy — 400. */
    @Transactional
    public int recompute(YearMonth month) {
        if (!month.isBefore(YearMonth.from(billingStatusService.today()))) {
            throw new BadRequestException("Faqat yopilgan oy qayta hisoblanadi; joriy oy jonli hisoblanadi: " + month);
        }
        return snapshotMonth(month, TeacherKpiMonthly.SOURCE_MANUAL);
    }

    /** @return yozilgan qatorlar soni */
    @Transactional
    public int snapshotMonth(YearMonth month, String source) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        LocalDateTime now = LocalDateTime.now(billingClock);
        Map<Long, TeacherKpiService.Counts> counts = kpiService.computeCounts(from, to, now.toLocalDate());
        Map<Long, Integer> groupCounts = new HashMap<>();
        for (Object[] row : groupRepository.countGroupsGroupedByTeacher()) {
            if (row[0] != null) {
                groupCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
            }
        }

        Map<Long, TeacherKpiMonthly> existing = new HashMap<>();
        List<TeacherKpiMonthly> duplicates = new java.util.ArrayList<>();
        for (TeacherKpiMonthly m : repository.findByMonthStart(from)) {
            // UNIQUE (teacher_id, month_start) bo'lmagan bazada (V74 dan oldin) dublikat bo'lishi mumkin —
            // eng yangisi yangilanadi, qolganlari o'chiriladi
            TeacherKpiMonthly prev = existing.put(m.getTeacherId(), m);
            if (prev != null) {
                boolean prevNewer = TeacherKpiService.FRESHEST.compare(prev, m) >= 0;
                existing.put(m.getTeacherId(), prevNewer ? prev : m);
                duplicates.add(prevNewer ? m : prev);
            }
        }
        if (!duplicates.isEmpty()) {
            log.warn("teacher_kpi_monthly {}: {} ta dublikat qator o'chirildi", month, duplicates.size());
            repository.deleteAll(duplicates);
            repository.flush();
        }

        int written = 0;
        List<Teacher> teachers = teacherRepository.findAll();
        for (Teacher t : teachers) {
            TeacherKpiService.Counts c = counts.get(t.getId());
            if (c == null && !Boolean.TRUE.equals(t.getIsActive())) {
                continue;
            }
            if (c == null) {
                c = TeacherKpiService.Counts.EMPTY;
            }
            TeacherKpiScoresDto s = TeacherKpiService.toScores(c);
            TeacherKpiMonthly row = existing.remove(t.getId());
            if (row == null) {
                row = new TeacherKpiMonthly();
                row.setTeacherId(t.getId());
                row.setMonthStart(from);
            }
            row.setAttendancePresent((int) c.attendancePresent());
            row.setAttendanceTotal((int) c.attendanceTotal());
            row.setPeriodsDecided((int) c.periodsDecided());
            row.setPeriodsPaid((int) c.periodsPaid());
            row.setPeriodsOnTime((int) c.periodsOnTime());
            row.setPeriodsPending((int) c.periodsPending());
            row.setOpenAtEnd((int) c.openAtEnd());
            row.setGraduated((int) c.graduated());
            row.setChurned((int) c.churned());
            row.setAttendanceRate(s.getAttendanceRate());
            row.setPaymentRate(s.getPaymentRate());
            row.setOnTimePaymentRate(s.getOnTimePaymentRate());
            row.setRetentionRate(s.getRetentionRate());
            row.setOverallScore(s.getOverallScore());
            row.setInsufficientData(Boolean.TRUE.equals(s.getInsufficientData()));
            row.setGroupCount(groupCounts.getOrDefault(t.getId(), 0));
            row.setStudentCount((int) c.openAtEnd());
            row.setSource(source);
            row.setComputedAt(now);
            repository.save(row);
            written++;
        }
        // Endi ro'yxatda yo'q (nofaol va ma'lumotsiz) o'qituvchining eski qatori
        repository.deleteAll(existing.values());
        return written;
    }
}
