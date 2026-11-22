package com.crm.dashboard;

import com.crm.dashboard.DirectorDtos.CountPercent;
import com.crm.dashboard.DirectorDtos.TrialRow;
import com.crm.dashboard.DirectorDtos.TrialsSection;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sinov → to'lov (director-dashboard §1.5, §7 #9/#10). Kogorta — sinovga KELGANLAR (sinov boshlanishi ∈ P);
 * {@code k = to'lov kuni − sinov kuni}: D0 (sinov kuni), D1, D2_7, D8_PLUS; to'lamagan — IN_TRIAL (sinovda,
 * ≤ 14 kun) yoki NOT_PAID.
 *
 * <p><b>Sinov yozilmasi</b> — {@code trial_outcome IS NOT NULL} (har yaratish yo'lida {@code @PrePersist} qo'yadi,
 * to'lovliga o'tgandan keyin ham qoladi) yoki {@code is_trial = true} (V53 dan oldingi yozilmalar).
 *
 * <p><b>Sinov boshlanishi</b> ({@link #resolve}) — avval faqat {@code trial_started_at} edi; u davomat
 * {@code is_trial = true} paytida belgilansagina yoziladi va prod'da deyarli bo'sh qoldi (to'lov qabulxonada darsdan
 * oldin / o'qituvchi davomatni keyin belgilaydi → yozilma allaqachon to'lovli; yoki sinov darsi belgilanmaydi).
 * Endi: {@code trial_started_at} → yozilmaning birinchi PRESENT/LATE darsi ({@code join_date} dan to'lovliga o'tgan
 * kungacha) → to'lovliga o'tgan bo'lsa {@code join_date} (taxminiy, {@code estimated}).
 */
@Service
@RequiredArgsConstructor
public class TrialMetricsService {

    public enum Bucket { D0, D1, D2_7, D8_PLUS, IN_TRIAL, NOT_PAID, NO_SHOW }

    private static final List<AttendanceStatus> ATTENDED = List.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);

    private final DashboardQueries queries;
    private final DashboardProperties properties;

    @Transactional(readOnly = true)
    public TrialsSection summary(DashboardPeriod p) {
        List<Resolved> resolved = resolve(p);
        List<TrialRow> cohort = cohortRows(p, resolved);
        long noShow = noShowRows(p, resolved).size();
        Map<String, CountPercent> buckets = new LinkedHashMap<>();
        for (Bucket b : List.of(Bucket.D0, Bucket.D1, Bucket.D2_7, Bucket.D8_PLUS, Bucket.IN_TRIAL, Bucket.NOT_PAID)) {
            long n = cohort.stream().filter(r -> b.name().equals(r.bucket())).count();
            buckets.put(b.name(), new CountPercent(n, Ratios.percent(n, cohort.size())));
        }
        long paid = cohort.stream().filter(r -> r.convertedDay() != null).count();
        long decided = cohort.size() - buckets.get(Bucket.IN_TRIAL.name()).count();
        return new TrialsSection(cohort.size(), noShow, buckets, Ratios.percent(paid, decided), stayed(cohort, p),
            cohort.stream().anyMatch(TrialRow::estimated));
    }

    @Transactional(readOnly = true)
    public List<TrialRow> rows(DashboardPeriod p, Bucket bucket) {
        List<Resolved> resolved = resolve(p);
        if (bucket == Bucket.NO_SHOW) {
            return noShowRows(p, resolved);
        }
        return cohortRows(p, resolved).stream().filter(r -> bucket.name().equals(r.bucket())).toList();
    }

    public static Bucket parseBucket(String raw) {
        try {
            return Bucket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw com.crm.exception.CodedException.badRequest("dashboard.param.invalid", "bucket");
        }
    }

    private List<TrialRow> cohortRows(DashboardPeriod p, List<Resolved> resolved) {
        LocalDate asOf = p.asOfDate();
        List<TrialRow> rows = new ArrayList<>();
        for (Resolved r : resolved) {
            if (r.start() == null || r.start().isBefore(p.from()) || r.start().isAfter(p.to())) {
                continue;
            }
            StudentGroup sg = r.sg();
            LocalDate paid = r.paidDay();
            Integer days = null;
            String bucket;
            if (paid != null && !paid.isAfter(asOf)) {
                days = (int) ChronoUnit.DAYS.between(r.start(), paid);
                bucket = days <= 0 ? "D0" : days == 1 ? "D1" : days <= 7 ? "D2_7" : "D8_PLUS";
            } else {
                paid = null;
                boolean stillTrial = Boolean.TRUE.equals(sg.getIsTrial()) && Boolean.TRUE.equals(sg.getIsActive());
                bucket = stillTrial && ChronoUnit.DAYS.between(r.start(), asOf) <= properties.getTrialDecisionDays()
                    ? "IN_TRIAL" : "NOT_PAID";
            }
            rows.add(row(sg, r.start(), paid, days, bucket, r.estimated()));
        }
        rows.sort(Comparator.comparing(TrialRow::trialStartedAt).thenComparing(TrialRow::studentGroupId));
        return rows;
    }

    /** Sinovga yozilgan ({@code join_date ∈ P}), lekin hech kelmagan va to'lovliga o'tmagan. */
    private List<TrialRow> noShowRows(DashboardPeriod p, List<Resolved> resolved) {
        return resolved.stream()
            .filter(r -> r.start() == null && r.sg().getJoinDate() != null
                && !r.sg().getJoinDate().isBefore(p.from()) && !r.sg().getJoinDate().isAfter(p.to()))
            .map(r -> row(r.sg(), null, null, null, "NO_SHOW", false))
            .sorted(Comparator.comparing(TrialRow::studentGroupId)).toList();
    }

    // ── Sinov boshlanishi ───────────────────────────────────────────────

    private record Resolved(StudentGroup sg, LocalDate start, LocalDate paidDay, boolean estimated) {
    }

    /**
     * Davrga tegishli bo'lishi mumkin bo'lgan sinov yozilmalari va har birining boshlanishi / birinchi to'lovi.
     * Nomzodlar: {@code trial_started_at ∈ P}, yoki u bo'sh va {@code join_date ≤ P.to} (boshlanish join_date dan
     * oldin bo'lmaydi) hamda to'lovliga o'tgan kun P dan oldin emas (boshlanish o'sha kundan keyin bo'lmaydi).
     */
    private List<Resolved> resolve(DashboardPeriod p) {
        List<StudentGroup> sgs = queries.em().createQuery("""
                SELECT sg FROM StudentGroup sg JOIN FETCH sg.student LEFT JOIN FETCH sg.group
                WHERE (sg.trialOutcome IS NOT NULL OR sg.isTrial = true)
                  AND (sg.trialStartedAt BETWEEN :f AND :t
                       OR (sg.trialStartedAt IS NULL AND (sg.joinDate IS NULL OR sg.joinDate <= :t)
                           AND (sg.trialConvertedAt IS NULL OR sg.trialConvertedAt >= :f)))
                """, StudentGroup.class).setParameter("f", p.from()).setParameter("t", p.to()).getResultList();
        if (sgs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LocalDate>> paidDays = paidDays(sgs);
        Map<String, List<LocalDate>> attended = attendedDays(sgs.stream()
            .filter(sg -> sg.getTrialStartedAt() == null).toList());

        List<Resolved> out = new ArrayList<>(sgs.size());
        for (StudentGroup sg : sgs) {
            LocalDate join = sg.getJoinDate();
            List<LocalDate> paid = paidDays.getOrDefault(sg.getId(), List.of());
            LocalDate start = sg.getTrialStartedAt();
            boolean estimated = "BACKFILL".equals(sg.getTrialSource());
            if (start == null) {
                LocalDate conversion = earliest(sg.getTrialConvertedAt(), firstOnOrAfter(paid, join));
                start = firstIn(attended.get(key(sg.getStudent().getId(), sg.getGroup())), join, conversion);
                estimated = false;
                if (start == null && conversion != null) {
                    start = join == null || join.isAfter(conversion) ? conversion : join;
                    estimated = true;
                }
            }
            out.add(new Resolved(sg, start, start == null ? null : firstOnOrAfter(paid, start), estimated));
        }
        return out;
    }

    /** SG bo'yicha PAID to'lov kunlari (o'sish tartibida); {@code cash > 0}, eski to'lovda cash yo'q — summa. */
    private Map<Long, List<LocalDate>> paidDays(List<StudentGroup> sgs) {
        Map<Long, List<LocalDate>> out = new HashMap<>();
        List<Object[]> rows = queries.em().createQuery("""
                SELECT p.studentGroup.id, p.paymentDate FROM Payment p
                WHERE p.studentGroup.id IN :ids AND p.status = :paid AND COALESCE(p.cashAmount, p.amount) > 0
                ORDER BY p.paymentDate, p.id
                """, Object[].class)
            .setParameter("ids", sgs.stream().map(StudentGroup::getId).toList())
            .setParameter("paid", PaymentStatus.PAID)
            .getResultList();
        for (Object[] r : rows) {
            out.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add((LocalDate) r[1]);
        }
        return out;
    }

    /** O'quvchi + guruh bo'yicha PRESENT/LATE kunlari (o'sish tartibida). */
    private Map<String, List<LocalDate>> attendedDays(List<StudentGroup> sgs) {
        Map<String, List<LocalDate>> out = new HashMap<>();
        Set<Long> studentIds = sgs.stream().filter(sg -> sg.getGroup() != null)
            .map(sg -> sg.getStudent().getId()).collect(Collectors.toSet());
        if (studentIds.isEmpty()) {
            return out;
        }
        List<Object[]> rows = queries.em().createQuery("""
                SELECT a.student.id, a.group.id, a.attendanceDate FROM Attendance a
                WHERE a.student.id IN :ids AND a.status IN :st
                ORDER BY a.attendanceDate
                """, Object[].class)
            .setParameter("ids", studentIds).setParameter("st", ATTENDED).getResultList();
        for (Object[] r : rows) {
            out.computeIfAbsent(r[0] + ":" + r[1], k -> new ArrayList<>()).add((LocalDate) r[2]);
        }
        return out;
    }

    private static String key(Long studentId, com.crm.entity.Group group) {
        return studentId + ":" + (group != null ? group.getId() : null);
    }

    /** {@code [from, to]} oralig'idagi birinchi kun (chegaralar null bo'lsa — ochiq). */
    private static LocalDate firstIn(List<LocalDate> days, LocalDate from, LocalDate to) {
        if (days == null) {
            return null;
        }
        for (LocalDate d : days) {
            if ((from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))) {
                return d;
            }
        }
        return null;
    }

    private static LocalDate firstOnOrAfter(List<LocalDate> days, LocalDate from) {
        return firstIn(days, from, null);
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : (a.isBefore(b) ? a : b);
    }

    /** To'laganlardan {@code paid + 30} kunda yozilmasi hali ochiq bo'lganlar % (shu kun kelganlar orasida). */
    private java.math.BigDecimal stayed(List<TrialRow> cohort, DashboardPeriod p) {
        long eligible = 0;
        long stayed = 0;
        Map<Long, StudentGroup> sgs = new HashMap<>();
        if (!cohort.isEmpty()) {
            queries.em().createQuery("SELECT sg FROM StudentGroup sg WHERE sg.id IN :ids", StudentGroup.class)
                .setParameter("ids", cohort.stream().map(TrialRow::studentGroupId).toList()).getResultList()
                .forEach(sg -> sgs.put(sg.getId(), sg));
        }
        for (TrialRow r : cohort) {
            if (r.convertedDay() == null) {
                continue;
            }
            LocalDate check = r.convertedDay().plusDays(properties.getTrialStayDays());
            if (check.isAfter(p.asOfDate())) {
                continue;
            }
            eligible++;
            StudentGroup sg = sgs.get(r.studentGroupId());
            boolean open = sg.getFrozenFrom() != null
                || (sg.getLeaveDate() == null ? Boolean.TRUE.equals(sg.getIsActive()) : sg.getLeaveDate().isAfter(check));
            if (open) {
                stayed++;
            }
        }
        return Ratios.percent(stayed, eligible);
    }

    private TrialRow row(StudentGroup sg, LocalDate start, LocalDate paid, Integer days, String bucket,
                         boolean estimated) {
        var s = sg.getStudent();
        return new TrialRow(sg.getId(), s.getId(), FunnelMetricsService.name(s.getFirstName(), s.getLastName()),
            s.getPhone(), sg.getGroup() != null ? sg.getGroup().getGroupName() : null, start,
            paid, days, bucket, sg.getTrialOutcome() != null ? sg.getTrialOutcome().name() : null,
            s.getConvertedFromLeadId(), estimated);
    }
}
