package com.crm.billing;

import com.crm.dto.response.ManualTransferDtos.TransferWarning;
import com.crm.entity.Group;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.ExitReasonCode;
import com.crm.exception.CodedException;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Qo'lda ko'chirishning oldini olish (bloklamaydi): guruhga qo'shishda va TRANSFERRED sabab bilan chiqarishda
 * {@code USE_TRANSFER} ogohlantirishi. Summa — {@link EnrollmentLifecycleService#planProspective} /
 * {@link EnrollmentLifecycleService#planMerge} (transfer qoidasi): transfer'siz qilinsa ikki marta hisoblanadigan
 * yoki eski yozilmada ko'chmay qoladigan summa.
 */
@Service
@RequiredArgsConstructor
public class TransferAdvisor {

    public static final String USE_TRANSFER = "USE_TRANSFER";
    public static final int RECENT_EXIT_DAYS = 7;

    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final EnrollmentLifecycleService lifecycle;
    private final BillingStatusService statusService;

    /**
     * Guruhga qo'shishdan oldin: o'quvchi hozir boshqa faol guruhda (har biri — ogohlantirish) yoki oxirgi 7 kunda boshqa
     * guruhdan chiqqan (keyin boshqa joyga yozilmagan) va transfer'siz qo'shilsa kredit qoladi. Yozmaydi.
     *
     * @param paymentStart yangi yozilma hisob boshlanishi (berilmasa — bugun)
     */
    @Transactional(readOnly = true)
    public List<TransferWarning> addToGroupWarnings(Long studentId, Long groupId, LocalDate paymentStart) {
        Group target = groupRepository.findById(groupId)
            .orElseThrow(() -> CodedException.notFound("error.group.notFound", groupId));
        LocalDate today = statusService.today();
        LocalDate anchor = paymentStart != null ? paymentStart : today;
        List<StudentGroup> all = studentGroupRepository.findByStudentId(studentId).stream()
            .sorted(Comparator.comparing(StudentGroup::getId)).toList();
        List<TransferWarning> out = new ArrayList<>();
        for (StudentGroup sg : all) {
            if (sg.getGroup() == null || sg.getGroup().getId().equals(groupId)) {
                continue;
            }
            if (Boolean.TRUE.equals(sg.getIsActive()) && sg.getFrozenFrom() == null) {
                EnrollmentLifecycleService.MergePlan plan = lifecycle.planProspective(sg, target, anchor);
                out.add(warning(sg, null, plan, true, "O'quvchi hozir \"" + groupName(sg) + "\" guruhida. Boshqa guruhga "
                    + "o'tkazish bo'lsa — \"Guruhga ko'chirish\" dan foydalaning (to'langan davr va balans ko'chadi)"));
                continue;
            }
            if (Boolean.TRUE.equals(sg.getIsActive()) || EnrollmentLifecycleService.isFrozen(sg)) {
                continue;
            }
            LocalDate exit = ManualTransferDetectionService.exitDate(sg);
            if (exit == null || exit.isBefore(today.minusDays(RECENT_EXIT_DAYS)) || hasSuccessor(sg, exit, all, groupId)) {
                continue;
            }
            EnrollmentLifecycleService.MergePlan plan = lifecycle.planProspective(sg, target, anchor);
            if (plan.credit().signum() > 0) {
                out.add(warning(sg, null, plan, false, "O'quvchi " + exit + " da \"" + groupName(sg) + "\" guruhidan "
                    + "chiqqan va u yerda ishlatilmagan summa qolgan. Qo'shish o'rniga \"Guruhga ko'chirish\" ishlating"));
            }
        }
        return out;
    }

    /**
     * Chiqarishdan keyin (bir tranzaksiyada): sabab TRANSFERRED va o'quvchining boshqa faol yozilmasi bor. Summa — faqat
     * ±7 kun ichida qo'shilgan (ko'chirishga o'xshagan) yozilma uchun; eski parallel guruhga — summasiz.
     */
    public List<TransferWarning> exitWarnings(StudentGroup closed, String reason, ExitReasonCode reasonCode) {
        ExitReasonCode code = reasonCode != null ? reasonCode : ExitReasonCode.fromLegacy(reason);
        List<TransferWarning> out = new ArrayList<>();
        if (code != ExitReasonCode.TRANSFERRED || closed.getStudent() == null) {
            return out;
        }
        LocalDate exit = ManualTransferDetectionService.exitDate(closed);
        for (StudentGroup n : studentGroupRepository.findByStudentId(closed.getStudent().getId())) {
            if (n.getId().equals(closed.getId()) || !Boolean.TRUE.equals(n.getIsActive()) || n.getFrozenFrom() != null) {
                continue;
            }
            boolean recent = exit != null && n.getJoinDate() != null
                && Math.abs(ChronoUnit.DAYS.between(exit, n.getJoinDate())) <= RECENT_EXIT_DAYS;
            EnrollmentLifecycleService.MergePlan plan = recent && lifecycle.mergeBlocker(closed, n) == null
                ? lifecycle.planMerge(closed, n) : null;
            out.add(warning(closed, n, plan, false, "Sabab \"Boshqa guruhga o'tdi\": keyingi safar \"Guruhga ko'chirish\" "
                + "dan foydalaning — aks holda eski guruhda to'langan davr ko'chmaydi va yangi guruhda qayta hisoblanadi"));
        }
        return out;
    }

    /** Yopilgan yozilmadan keyin (±7 kun) boshqa guruhga yozilma bor — u allaqachon ko'chgan (transfer yoki qo'lda). */
    private static boolean hasSuccessor(StudentGroup sg, LocalDate exit, List<StudentGroup> all, Long targetGroupId) {
        return all.stream().anyMatch(n -> n.getId() > sg.getId() && n.getGroup() != null
            && !n.getGroup().getId().equals(targetGroupId) && n.getJoinDate() != null
            && Math.abs(ChronoUnit.DAYS.between(exit, n.getJoinDate())) <= RECENT_EXIT_DAYS);
    }

    private static TransferWarning warning(StudentGroup from, StudentGroup to, EnrollmentLifecycleService.MergePlan plan,
                                           boolean fromActive, String message) {
        return new TransferWarning(USE_TRANSFER, from.getId(),
            from.getGroup() != null ? from.getGroup().getId() : null, groupName(from),
            to != null ? to.getId() : null, to != null && to.getGroup() != null ? to.getGroup().getId() : null,
            to != null ? groupName(to) : null,
            plan != null ? Money.normalize(plan.credit().max(BigDecimal.ZERO)) : null,
            plan != null ? plan.continuedAnchor() : null, fromActive, message);
    }

    private static String groupName(StudentGroup sg) {
        return sg.getGroup() != null ? sg.getGroup().getGroupName() : "";
    }
}
