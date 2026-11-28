package com.crm.service;

import com.crm.dto.request.GroupPromoteRequest;
import com.crm.dto.request.TransferStudentsRequest;
import com.crm.dto.response.GroupPromoteDtos;
import com.crm.entity.StudentGroup;
import com.crm.exception.CodedException;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code POST /api/groups/{id}/transfer-students}: butun guruh (yoki tanlanganlar) boshqa guruhga — har o'quvchi mavjud
 * transfer oqimi bilan ({@link GroupPromotionService#promote} → {@code EnrollmentLifecycleService.transfer}: balans va
 * to'langan davr ko'chadi, langar uzluksiz). Farqi promote'dan: bloklovchi holatdagi o'quvchilar (guruhda yo'q,
 * muzlatilgan, maqsad guruhda allaqachon bor) o'tkazib yuboriladi — qolganlari ko'chadi; {@code dryRun} — preview.
 */
@Service
@RequiredArgsConstructor
public class GroupBulkTransferService {

    private final GroupPromotionService promotionService;
    private final StudentGroupRepository studentGroupRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * @param credit              ko'chadigan balans (musbat — kredit, manfiy — qarz ko'chadi)
     * @param newPaymentStartDate yangi guruhda hisob boshlanishi (eski to'langan davr tugagach)
     * @param codes               ogohlantirish kodlari (o'tkazib yuborilganda — bloklovchilari)
     */
    public record Item(Long studentId, String studentName, Long fromEnrollmentId, Long toEnrollmentId,
                       BigDecimal credit, BigDecimal debt, LocalDate newPaymentStartDate, List<String> codes) {
    }

    public record Result(boolean dryRun, Long fromGroupId, Long targetGroupId, LocalDate date, boolean canApply,
                         List<GroupPromoteDtos.Warning> groupWarnings, List<Item> transferred, List<Item> skipped,
                         Long batchId, Boolean idempotentReplay) {
    }

    public Result transfer(Long fromId, TransferStudentsRequest request, boolean dryRun, String idempotencyKey) {
        if (!dryRun && idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<GroupPromoteDtos.Result> replay = promotionService.replayByKey(idempotencyKey);
            if (replay.isPresent()) {
                GroupPromoteDtos.Result r = replay.get();
                if (!fromId.equals(r.fromGroupId()) || !request.getTargetGroupId().equals(r.targetGroupId())) {
                    throw new com.crm.exception.ConflictException("group.promote.idempotency.mismatch");
                }
                return new Result(false, fromId, r.targetGroupId(), r.date(), true, List.of(),
                    r.students().stream().map(m -> new Item(m.studentId(), m.studentName(), m.fromStudentGroupId(),
                        m.toStudentGroupId(), m.movedBalance(), null, null, List.of())).toList(),
                    List.of(), r.batchId(), Boolean.TRUE);
            }
        }
        List<Long> ids = request.getStudentIds() != null && !request.getStudentIds().isEmpty()
            ? request.getStudentIds() : activeStudentIds(fromId);
        if (ids.isEmpty()) {
            throw CodedException.badRequest("group.promote.studentsRequired");
        }
        GroupPromoteDtos.Preview preview = promotionService.preview(fromId, promoteRequest(request, ids));
        List<GroupPromoteDtos.StudentRow> eligible = new ArrayList<>();
        List<Item> skipped = new ArrayList<>();
        for (GroupPromoteDtos.StudentRow row : preview.students()) {
            List<String> blocking = row.warnings().stream().filter(GroupPromoteDtos.Warning::blocking)
                .map(GroupPromoteDtos.Warning::code).toList();
            if (blocking.isEmpty()) {
                eligible.add(row);
            } else {
                skipped.add(item(row, null, blocking));
            }
        }
        List<Long> eligibleIds = eligible.stream().map(GroupPromoteDtos.StudentRow::studentId).toList();
        if (eligibleIds.isEmpty()) {
            return new Result(dryRun, fromId, request.getTargetGroupId(), preview.date(), false, preview.warnings(),
                List.of(), skipped, null, null);
        }
        GroupPromoteRequest eligibleRequest = promoteRequest(request, eligibleIds);
        if (dryRun) {
            GroupPromoteDtos.Preview check = eligibleIds.size() == preview.students().size()
                ? preview : promotionService.preview(fromId, eligibleRequest);
            return new Result(true, fromId, request.getTargetGroupId(), check.date(), check.canApply(),
                check.warnings(), eligible.stream().map(r -> item(r, null, nonBlocking(r))).toList(), skipped,
                null, null);
        }
        GroupPromoteDtos.Result done = promotionService.promote(fromId, eligibleRequest,
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
        Map<Long, GroupPromoteDtos.StudentRow> rows = new HashMap<>();
        eligible.forEach(r -> rows.put(r.studentId(), r));
        List<Item> transferred = done.students().stream().map(m -> {
            GroupPromoteDtos.StudentRow r = rows.get(m.studentId());
            return new Item(m.studentId(), m.studentName(), m.fromStudentGroupId(), m.toStudentGroupId(),
                m.movedBalance(), r != null ? r.debt() : null, r != null ? r.nextBillingDate() : null,
                r != null ? nonBlocking(r) : List.of());
        }).toList();
        return new Result(false, fromId, done.targetGroupId(), done.date(), true, preview.warnings(), transferred,
            skipped, done.batchId(), null);
    }

    private List<Long> activeStudentIds(Long groupId) {
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        return read.execute(s -> studentGroupRepository.findActiveWithStudentByGroupId(groupId).stream()
            .filter(sg -> sg.getFrozenFrom() == null)
            .map(StudentGroup::getStudent)
            .map(st -> st.getId())
            .distinct()
            .sorted()
            .toList());
    }

    private static GroupPromoteRequest promoteRequest(TransferStudentsRequest request, List<Long> ids) {
        GroupPromoteRequest r = new GroupPromoteRequest();
        r.setTargetGroupId(request.getTargetGroupId());
        r.setStudentIds(new ArrayList<>(ids));
        r.setDate(request.getDate());
        r.setNote(request.getNote());
        return r;
    }

    private static List<String> nonBlocking(GroupPromoteDtos.StudentRow r) {
        return r.warnings().stream().map(GroupPromoteDtos.Warning::code).toList();
    }

    private static Item item(GroupPromoteDtos.StudentRow r, Long toId, List<String> codes) {
        return new Item(r.studentId(), r.studentName(), r.studentGroupId(), toId, r.balance(), r.debt(),
            r.nextBillingDate(), codes);
    }
}
