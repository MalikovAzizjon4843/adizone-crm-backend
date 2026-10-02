package com.crm.dashboard;

import com.crm.entity.Lead;
import com.crm.entity.LeadAssignment;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.repository.LeadAssignmentRepository;
import com.crm.repository.LeadRepository;
import com.crm.service.LeadStageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Direktor dashboardi uchun lid hodisalarini yozadi (director-dashboard §1.1, §1.7, §3.1):
 * <ul>
 *   <li>voronka qadami sanalari ({@code leads.contacted_at/visited_at/converted_at/rejected_at}) —
 *       birinchi kirish, write-once; yuqori qadam pastkilarini ham to'ldiradi;</li>
 *   <li>tayinlash tarixi ({@code lead_assignments});</li>
 *   <li>operatorning birinchi javobi (bosqich o'zgarishi, bajarilgan vazifa, izoh).</li>
 * </ul>
 * Chaqiruvchi tranzaksiyasida ishlaydi; lid entity'si chaqiruvchida saqlanadi.
 */
@Service
@RequiredArgsConstructor
public class LeadFunnelTracker {

    private final LeadStageService leadStageService;
    private final LeadAssignmentRepository assignmentRepository;
    private final LeadRepository leadRepository;
    private final Clock billingClock;

    public LocalDateTime now() {
        return LocalDateTime.now(billingClock);
    }

    /** Yangi lid: boshlang'ich bosqich (NEW emas bo'lsa) va boshlang'ich tayinlash. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCreated(Lead lead, User by) {
        LocalDateTime at = lead.getCreatedAt() != null ? lead.getCreatedAt() : now();
        onStageEntered(lead, lead.getStatus(), at);
        if (lead.getAssignedUser() != null) {
            onAssigned(lead, lead.getAssignedUser(), by,
                lead.getAssignedAt() != null ? lead.getAssignedAt() : at);
        }
    }

    /**
     * Bosqichga kirish: rank bo'yicha qadam sanalari (yo'q bo'lsa) to'ldiriladi.
     *
     * <p>Konvert bosqichi aloqani ham to'ldiradi, lekin <b>tashrifni emas</b>: bu CRM da lid
     * sinovga yozilganda konvert qilinadi, tashrif esa VISITED bosqichi yoki birinchi
     * davomatdan keladi (§7 #1 qarori, {@link #onStudentAttended}).
     */
    public void onStageEntered(Lead lead, String toCode, LocalDateTime at) {
        int rank = leadStageService.funnelRank(toCode);
        if (rank >= 1 && lead.getContactedAt() == null) {
            lead.setContactedAt(at);
        }
        if (rank == 2 && lead.getVisitedAt() == null) {
            lead.setVisitedAt(at);
        }
        if (rank >= 3 && lead.getConvertedAt() == null) {
            lead.setConvertedAt(at);
        }
        if (leadStageService.isRejected(toCode) && lead.getRejectedAt() == null) {
            lead.setRejectedAt(at);
        }
    }

    /** Tayinlash o'zgardi: ochiq qator yopiladi, yangi operatorga yangi qator. Shu operatorga qayta — o'zgarmaydi. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAssigned(Lead lead, User newUser, User by, LocalDateTime at) {
        if (lead.getId() == null) {
            return;
        }
        LeadAssignment open = assignmentRepository
            .findFirstByLeadIdAndUnassignedAtIsNullOrderByAssignedAtDescIdDesc(lead.getId()).orElse(null);
        if (open != null && newUser != null && open.getUserId().equals(newUser.getId())) {
            return;
        }
        if (open != null) {
            open.setUnassignedAt(at);
            assignmentRepository.save(open);
        }
        if (newUser != null) {
            assignmentRepository.save(LeadAssignment.builder()
                .leadId(lead.getId())
                .userId(newUser.getId())
                .assignedAt(at)
                .assignedBy(by != null ? by.getId() : null)
                .source("LIVE")
                .build());
        }
    }

    /**
     * Operator harakati — shu lidning ochiq tayinlashi aynan shu operatorniki bo'lsa va birinchi
     * javob hali yozilmagan bo'lsa, yoziladi (§1.7: faqat tayinlangan operatorning harakati).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onOperatorAction(Long leadId, User actor, String kind, LocalDateTime at) {
        if (leadId == null || actor == null) {
            return;
        }
        assignmentRepository.findFirstByLeadIdAndUnassignedAtIsNullOrderByAssignedAtDescIdDesc(leadId)
            .filter(a -> a.getUserId().equals(actor.getId()))
            .filter(a -> a.getFirstResponseAt() == null)
            .filter(a -> !at.isBefore(a.getAssignedAt()))
            .ifPresent(a -> {
                a.setFirstResponseAt(at);
                a.setFirstResponseKind(kind);
                assignmentRepository.save(a);
            });
    }

    /**
     * §7 #1 qarori: konvert qilingan o'quvchining birinchi PRESENT/LATE davomati ham tashrif —
     * {@code leads.visited_at} bo'sh bo'lsa yoziladi (aloqa sanasi ham).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStudentAttended(Student student, LocalDate lessonDate) {
        if (student == null || student.getConvertedFromLeadId() == null || lessonDate == null) {
            return;
        }
        leadRepository.findById(student.getConvertedFromLeadId()).ifPresent(lead -> {
            if (lead.getVisitedAt() != null) {
                return;
            }
            LocalDateTime at = lessonDate.atStartOfDay();
            lead.setVisitedAt(at);
            if (lead.getContactedAt() == null) {
                lead.setContactedAt(at);
            }
            leadRepository.save(lead);
        });
    }
}
