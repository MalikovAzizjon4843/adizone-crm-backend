package com.crm.dashboard;

import com.crm.billing.PeriodCoverageService;
import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.LeadComment;
import com.crm.entity.LeadStatusHistory;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Task;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TrialOutcome;
import com.crm.exception.CodedException;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStatusHistoryRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.service.LeadStageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Tarixni bir martalik to'ldirish (director-dashboard §2.2 "Tarixni to'ldirish", G2–G6, G10).
 * Billing migratsiyasi uslubida: avval DRY-RUN (hech narsa yozmaydi, faqat hisobot), keyin
 * {@code confirm=BACKFILL-APPLY} bilan qo'llash. Faqat BO'SH maydonlar to'ldiriladi — qayta
 * ishga tushirish xavfsiz (idempotent), jonli yozilgan qiymatlar ustidan yozilmaydi.
 * Ledgerga tegmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardBackfillService {

    public static final String CONFIRM = "BACKFILL-APPLY";
    private static final int SAMPLE = 20;
    private static final List<AttendanceStatus> ATTENDED = List.of(AttendanceStatus.PRESENT, AttendanceStatus.LATE);

    private final LeadRepository leadRepository;
    private final LeadStatusHistoryRepository historyRepository;
    private final LeadAssignmentRepository assignmentRepository;
    private final LeadCommentRepository commentRepository;
    private final TaskRepository taskRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeadStageService leadStageService;
    private final PeriodCoverageService periodCoverageService;
    private final PlatformTransactionManager transactionManager;
    private final Clock billingClock;

    // ── Hisobot ─────────────────────────────────────────────────────────

    public record Item(String code, String title, int candidates, int changes, List<String> sample) {
    }

    public record Report(boolean dryRun, LocalDateTime generatedAt, List<Item> items, List<String> notes) {
    }

    // ── Rejalashtirilgan o'zgarishlar (entity'ga tegmasdan) ─────────────

    record LeadDates(Long leadId, LocalDateTime contacted, LocalDateTime visited,
                     LocalDateTime converted, LocalDateTime rejected) {
        boolean any() {
            return contacted != null || visited != null || converted != null || rejected != null;
        }
    }

    record NewAssignment(Long leadId, Long userId, LocalDateTime assignedAt,
                         LocalDateTime firstResponseAt, String kind) {
    }

    record TrialFill(Long sgId, LocalDate startedAt, LocalDate convertedAt, TrialOutcome outcome) {
    }

    record ExitFill(Long sgId, ExitReasonCode code) {
    }

    record Plan(List<LeadDates> leadDates, int leadsScanned, List<NewAssignment> assignments, int assignedLeads,
                Map<Long, Integer> coverage, int sgWithPeriods, List<TrialFill> trials, int trialCandidates,
                List<ExitFill> exits, int closedCandidates) {
    }

    // ── API ─────────────────────────────────────────────────────────────

    public Report run(boolean dryRun, String confirm) {
        if (!dryRun && !CONFIRM.equals(confirm)) {
            throw CodedException.badRequest("migration.confirmRequired", CONFIRM);
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(dryRun);
        Plan plan = tx.execute(s -> {
            Plan p = plan();
            if (!dryRun) {
                apply(p);
            }
            return p;
        });
        Report report = report(plan, dryRun);
        log.info("Dashboard backfill {}: {}", dryRun ? "DRY-RUN" : "APPLIED",
            report.items().stream().map(i -> i.code() + "=" + i.changes()).toList());
        return report;
    }

    // ── Reja ────────────────────────────────────────────────────────────

    Plan plan() {
        List<Lead> leads = leadRepository.findAll(Sort.by("id"));
        Map<Long, List<LeadStatusHistory>> history = new HashMap<>();
        for (LeadStatusHistory h : historyRepository.findAll()) {
            history.computeIfAbsent(h.getLead().getId(), k -> new ArrayList<>()).add(h);
        }
        history.values().forEach(l -> l.sort(Comparator.comparing(LeadStatusHistory::getChangedAt)
            .thenComparing(LeadStatusHistory::getId)));

        Map<String, TreeSet<LocalDate>> attended = new HashMap<>();
        Map<Long, LocalDate> firstAttendanceByStudent = new HashMap<>();
        for (Object[] row : attendanceRepository.findTriplesByStatuses(ATTENDED)) {
            Long studentId = (Long) row[0];
            Long groupId = (Long) row[1];
            LocalDate date = (LocalDate) row[2];
            attended.computeIfAbsent(studentId + ":" + groupId, k -> new TreeSet<>()).add(date);
            firstAttendanceByStudent.merge(studentId, date, (a, b) -> a.isBefore(b) ? a : b);
        }
        Map<Long, Long> studentByLead = new HashMap<>();
        for (Student s : studentRepository.findAll()) {
            if (s.getConvertedFromLeadId() != null) {
                studentByLead.put(s.getConvertedFromLeadId(), s.getId());
            }
        }

        // G2 — lid qadam sanalari
        List<LeadDates> leadDates = new ArrayList<>();
        for (Lead lead : leads) {
            LeadDates d = leadDates(lead, history.getOrDefault(lead.getId(), List.of()),
                studentByLead.get(lead.getId()), firstAttendanceByStudent);
            if (d.any()) {
                leadDates.add(d);
            }
        }

        // G3/G4 — tayinlash tarixi (faqat tarixsiz lidlar uchun bitta qator)
        Set<Long> withAssignments = new HashSet<>();
        assignmentRepository.findAll().forEach(a -> withAssignments.add(a.getLeadId()));
        Map<Long, List<Task>> doneTasks = new HashMap<>();
        for (Task t : taskRepository.findAll()) {
            if (t.getLead() != null && t.getStatus() == TaskStatus.DONE && t.getCompletedAt() != null) {
                doneTasks.computeIfAbsent(t.getLead().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<Long, List<LeadComment>> comments = new HashMap<>();
        for (LeadComment c : commentRepository.findAll()) {
            comments.computeIfAbsent(c.getLead().getId(), k -> new ArrayList<>()).add(c);
        }
        List<NewAssignment> assignments = new ArrayList<>();
        int assignedLeads = 0;
        for (Lead lead : leads) {
            if (lead.getAssignedUser() == null) {
                continue;
            }
            assignedLeads++;
            if (withAssignments.contains(lead.getId())) {
                continue;
            }
            Long userId = lead.getAssignedUser().getId();
            LocalDateTime assignedAt = lead.getAssignedAt() != null ? lead.getAssignedAt() : lead.getCreatedAt();
            if (assignedAt == null) {
                continue;
            }
            LocalDateTime t0 = lead.getCreatedAt() != null && lead.getCreatedAt().isAfter(assignedAt)
                ? lead.getCreatedAt() : assignedAt;
            Response r = firstResponse(userId, t0, history.getOrDefault(lead.getId(), List.of()),
                doneTasks.getOrDefault(lead.getId(), List.of()), comments.getOrDefault(lead.getId(), List.of()));
            assignments.add(new NewAssignment(lead.getId(), userId, assignedAt,
                r != null ? r.at() : null, r != null ? r.kind() : null));
        }

        // G5 — davrlar qachon yopilgani
        Map<Long, Integer> coverage = new HashMap<>();
        List<StudentGroup> sgs = studentGroupRepository.findAll(Sort.by("id"));
        int sgWithPeriods = sgs.size();
        for (StudentGroup sg : sgs) {
            int n = periodCoverageService.diff(sg);
            if (n > 0) {
                coverage.put(sg.getId(), n);
            }
        }

        // G6 — sinov; G10 — chiqish sababi
        List<TrialFill> trials = new ArrayList<>();
        List<ExitFill> exits = new ArrayList<>();
        int trialCandidates = 0;
        int closedCandidates = 0;
        for (StudentGroup sg : sgs) {
            TreeSet<LocalDate> dates = sg.getStudent() != null && sg.getGroup() != null
                ? attended.getOrDefault(sg.getStudent().getId() + ":" + sg.getGroup().getId(), new TreeSet<>())
                : new TreeSet<>();
            TrialFill trial = trialFill(sg, dates);
            if (Boolean.TRUE.equals(sg.getIsTrial()) || trial != null) {
                trialCandidates++;
            }
            if (trial != null) {
                trials.add(trial);
            }
            boolean closed = !Boolean.TRUE.equals(sg.getIsActive()) || sg.getFrozenFrom() != null;
            if (closed) {
                closedCandidates++;
                if (sg.getExitReasonCode() == null) {
                    exits.add(new ExitFill(sg.getId(), sg.getFrozenFrom() != null
                        ? ExitReasonCode.FROZEN : ExitReasonCode.fromLegacy(sg.getExitReason())));
                }
            }
        }
        return new Plan(leadDates, leads.size(), assignments, assignedLeads, coverage, sgWithPeriods,
            trials, trialCandidates, exits, closedCandidates);
    }

    /** Tarixdan birinchi kirishlar; bo'sh maydonlargina (jonli yozilgani ustidan yozilmaydi). */
    private LeadDates leadDates(Lead lead, List<LeadStatusHistory> history, Long studentId,
                                Map<Long, LocalDate> firstAttendanceByStudent) {
        LocalDateTime contacted = null;
        LocalDateTime visited = null;
        LocalDateTime converted = null;
        LocalDateTime rejected = null;
        // Tarixsiz lid boshlang'ich bosqichda yaratilgan bo'lishi mumkin (qo'lda, import)
        List<Stage> entries = new ArrayList<>();
        if (history.isEmpty() || !Objects.equals(history.get(0).getFromStatus(), null)) {
            String initial = history.isEmpty() ? lead.getStatus() : history.get(0).getFromStatus();
            if (initial != null && lead.getCreatedAt() != null) {
                entries.add(new Stage(initial, lead.getCreatedAt()));
            }
        }
        history.forEach(h -> entries.add(new Stage(h.getToStatus(), h.getChangedAt())));
        for (Stage e : entries) {
            int rank = leadStageService.funnelRank(e.code());
            if (rank >= 1 && contacted == null) contacted = e.at();
            if (rank == 2 && visited == null) visited = e.at();
            if (rank >= 3 && converted == null) converted = e.at();
            if (leadStageService.isRejected(e.code()) && rejected == null) rejected = e.at();
        }
        // §7 #1: konvert qilingan o'quvchining birinchi PRESENT/LATE davomati ham tashrif
        if (visited == null && studentId != null && firstAttendanceByStudent.containsKey(studentId)) {
            visited = firstAttendanceByStudent.get(studentId).atStartOfDay();
        }
        if (contacted == null && visited != null) contacted = visited;
        if (contacted == null && converted != null) contacted = converted;
        return new LeadDates(lead.getId(),
            lead.getContactedAt() == null ? contacted : null,
            lead.getVisitedAt() == null ? visited : null,
            lead.getConvertedAt() == null ? converted : null,
            lead.getRejectedAt() == null ? rejected : null);
    }

    private record Stage(String code, LocalDateTime at) {
    }

    private record Response(LocalDateTime at, String kind) {
    }

    private static Response firstResponse(Long userId, LocalDateTime t0, List<LeadStatusHistory> history,
                                          List<Task> tasks, List<LeadComment> comments) {
        return Stream.of(
                history.stream()
                    .filter(h -> h.getChangedBy() != null && userId.equals(h.getChangedBy().getId()))
                    .filter(h -> !h.getChangedAt().isBefore(t0))
                    .map(h -> new Response(h.getChangedAt(), LeadAssignment.KIND_STATUS)),
                tasks.stream()
                    .filter(t -> t.getCompletedBy() != null && userId.equals(t.getCompletedBy().getId()))
                    .filter(t -> !t.getCompletedAt().isBefore(t0))
                    .map(t -> new Response(t.getCompletedAt(), LeadAssignment.KIND_TASK)),
                comments.stream()
                    .filter(c -> c.getAuthor() != null && userId.equals(c.getAuthor().getId()))
                    .filter(c -> c.getCreatedAt() != null && !c.getCreatedAt().isBefore(t0))
                    .map(c -> new Response(c.getCreatedAt(), LeadAssignment.KIND_COMMENT)))
            .flatMap(s -> s)
            .min(Comparator.comparing(Response::at))
            .orElse(null);
    }

    /**
     * Hozir sinovdagilar — aniq; to'lovliga o'tganlar — taxminiy: langardan oldingi birinchi
     * kelgan dars sinov boshi, langar = konvertatsiya kuni ({@code trial_source = BACKFILL}).
     */
    private static TrialFill trialFill(StudentGroup sg, TreeSet<LocalDate> attended) {
        LocalDate from = sg.getJoinDate();
        if (Boolean.TRUE.equals(sg.getIsTrial())) {
            LocalDate started = sg.getTrialStartedAt() == null
                ? (from != null ? attended.ceiling(from) : (attended.isEmpty() ? null : attended.first()))
                : null;
            TrialOutcome outcome = null;
            boolean closed = !Boolean.TRUE.equals(sg.getIsActive()) && sg.getFrozenFrom() == null;
            if (sg.getTrialOutcome() == null || (closed && sg.getTrialOutcome() == TrialOutcome.IN_TRIAL)) {
                LocalDate effectiveStart = started != null ? started : sg.getTrialStartedAt();
                outcome = closed ? (effectiveStart != null ? TrialOutcome.LEFT : TrialOutcome.NO_SHOW)
                    : TrialOutcome.IN_TRIAL;
                if (outcome == sg.getTrialOutcome()) {
                    outcome = null;
                }
            }
            return started != null || outcome != null ? new TrialFill(sg.getId(), started, null, outcome) : null;
        }
        if (sg.getTrialOutcome() != null || sg.getPaymentStartDate() == null) {
            return null;
        }
        LocalDate anchor = sg.getPaymentStartDate();
        LocalDate first = from != null ? attended.ceiling(from) : (attended.isEmpty() ? null : attended.first());
        if (first == null || !first.isBefore(anchor) || (from != null && !from.isBefore(anchor))) {
            return null;
        }
        return new TrialFill(sg.getId(), first, anchor, TrialOutcome.CONVERTED);
    }

    // ── Qo'llash (faqat bo'sh maydonlar) ───────────────────────────────

    private void apply(Plan p) {
        for (LeadDates d : p.leadDates()) {
            leadRepository.findById(d.leadId()).ifPresent(lead -> {
                if (lead.getContactedAt() == null && d.contacted() != null) lead.setContactedAt(d.contacted());
                if (lead.getVisitedAt() == null && d.visited() != null) lead.setVisitedAt(d.visited());
                if (lead.getConvertedAt() == null && d.converted() != null) lead.setConvertedAt(d.converted());
                if (lead.getRejectedAt() == null && d.rejected() != null) lead.setRejectedAt(d.rejected());
                leadRepository.save(lead);
            });
        }
        for (NewAssignment a : p.assignments()) {
            if (assignmentRepository.existsByLeadId(a.leadId())) {
                continue;
            }
            assignmentRepository.save(LeadAssignment.builder()
                .leadId(a.leadId()).userId(a.userId()).assignedAt(a.assignedAt())
                .firstResponseAt(a.firstResponseAt()).firstResponseKind(a.kind())
                .source("BACKFILL").build());
        }
        for (Long sgId : p.coverage().keySet()) {
            studentGroupRepository.findById(sgId).ifPresent(periodCoverageService::refresh);
        }
        for (TrialFill t : p.trials()) {
            studentGroupRepository.findById(t.sgId()).ifPresent(sg -> {
                if (t.startedAt() != null && sg.getTrialStartedAt() == null) sg.setTrialStartedAt(t.startedAt());
                if (t.convertedAt() != null && sg.getTrialConvertedAt() == null) sg.setTrialConvertedAt(t.convertedAt());
                if (t.outcome() != null) sg.setTrialOutcome(t.outcome());
                if (sg.getTrialSource() == null) sg.setTrialSource("BACKFILL");
                studentGroupRepository.save(sg);
            });
        }
        for (ExitFill e : p.exits()) {
            studentGroupRepository.findById(e.sgId()).ifPresent(sg -> {
                if (sg.getExitReasonCode() == null) {
                    sg.setExitReasonCode(e.code());
                    studentGroupRepository.save(sg);
                }
            });
        }
    }

    private Report report(Plan p, boolean dryRun) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("G2", "Lid voronka sanalari (contacted/visited/converted/rejected)",
            p.leadsScanned(), p.leadDates().size(),
            p.leadDates().stream().limit(SAMPLE).map(d -> "lead#" + d.leadId()
                + (d.contacted() != null ? " contacted=" + d.contacted() : "")
                + (d.visited() != null ? " visited=" + d.visited() : "")
                + (d.converted() != null ? " converted=" + d.converted() : "")
                + (d.rejected() != null ? " rejected=" + d.rejected() : "")).toList()));
        items.add(new Item("G3/G4", "Tayinlash tarixi (bitta qator) va birinchi javob",
            p.assignedLeads(), p.assignments().size(),
            p.assignments().stream().limit(SAMPLE).map(a -> "lead#" + a.leadId() + " user#" + a.userId()
                + " assigned=" + a.assignedAt()
                + (a.firstResponseAt() != null ? " response=" + a.firstResponseAt() + " (" + a.kind() + ")" : " javobsiz"))
                .toList()));
        items.add(new Item("G5", "Davr muddati va yopilishi (paid_on, FIFO replay)",
            p.sgWithPeriods(), p.coverage().values().stream().mapToInt(Integer::intValue).sum(),
            p.coverage().entrySet().stream().limit(SAMPLE).map(e -> "sg#" + e.getKey() + " davrlar=" + e.getValue())
                .toList()));
        items.add(new Item("G6", "Sinov boshlanishi va natijasi", p.trialCandidates(), p.trials().size(),
            p.trials().stream().limit(SAMPLE).map(t -> "sg#" + t.sgId()
                + (t.startedAt() != null ? " started=" + t.startedAt() : "")
                + (t.convertedAt() != null ? " converted=" + t.convertedAt() : "")
                + (t.outcome() != null ? " outcome=" + t.outcome() : "")).toList()));
        items.add(new Item("G10", "Chiqish sababi kodi", p.closedCandidates(), p.exits().size(),
            p.exits().stream().limit(SAMPLE).map(e -> "sg#" + e.sgId() + " " + e.code()).toList()));
        List<String> notes = List.of(
            "Faqat bo'sh maydonlar to'ldiriladi — qayta ishga tushirish xavfsiz.",
            "G3: audit_logs (ASSIGN) asosidagi oldingi tayinlashlar to'ldirilmaydi — faqat oxirgi tayinlash.",
            "G6: to'lovliga o'tgan sinovlar taxminiy (trial_source = BACKFILL).",
            "G8 (bayramlar), G11 (kunlik snapshot) — tarix yo'q, ishga tushgan kundan.");
        return new Report(dryRun, LocalDateTime.now(billingClock), items, notes);
    }
}
