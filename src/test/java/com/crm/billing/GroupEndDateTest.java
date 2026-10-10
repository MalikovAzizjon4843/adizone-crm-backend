package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.GroupRequest;
import com.crm.dto.request.PaymentRequest;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.dto.response.GroupEndDateDtos;
import com.crm.entity.BillingPeriod;
import com.crm.entity.Group;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.BillingPeriodRepository;
import com.crm.repository.GroupRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.service.GroupService;
import com.crm.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guruh tugash sanasi (billing-v2 R3, §14.3). Prod holati (2026-10-10): guruhlar 07.09.2026 da boshlangan, lekin
 * {@code end_date = 07.01.2026} kiritilgan — R3 sababli davrlar yozilmagan. Buyurtmachi sanalari:
 * TOQ/15:30-ZAMIRA → 14.12.2026, TOQ/09:00-SARVINOZ → 11.10.2026, JUFT/18:00-BOBUR → 14.11.2026,
 * JUFT/14:00-IRODA → 14.11.2026, JUFT/11:00-IRODA → COMPLETED. Narx 700 000.
 */
class GroupEndDateTest extends AbstractBillingIT {

    private static final String TODAY = "10.10.2026";

    @Autowired GroupEndDateService endDates;
    @Autowired GroupService groups;
    @Autowired PaymentService payments;
    @Autowired DebtorService debtors;
    @Autowired BillingJobService job;
    @Autowired GroupRepository groupRepo;
    @Autowired StudentGroupRepository sgRepo;
    @Autowired BillingPeriodRepository periodRepo;

    /** Prod'dagi kabi buzilgan guruh: boshlanish 07.09.2026, tugash 07.01.2026 (validatsiyani chetlab, to'g'ridan bazaga). */
    private Long brokenGroup(String name) {
        Long id = fixtures.group(fixtures.course(700_000));
        inTx(() -> {
            Group g = groupRepo.findById(id).orElseThrow();
            g.setGroupName(name);
            g.setStartDate(d("07.09.2026"));
            g.setEndDate(d("07.01.2026"));
            groupRepo.save(g);
        });
        return id;
    }

    private record Sg(Long student, Long group, Long id) {
    }

    private Sg enroll(Long group, String start) {
        Long student = fixtures.student();
        return new Sg(student, group, fixtures.enrollment(student, group).start(d(start)).save());
    }

    private Sg heldEnroll(Long group, String start) {
        Sg sg = enroll(group, start);
        inTx(() -> {
            StudentGroup e = sgRepo.findById(sg.id()).orElseThrow();
            e.setBillingHold(Boolean.TRUE);       // migratsiyada excluded → hold, 0 davr
            sgRepo.save(e);
        });
        return sg;
    }

    private void pay(Sg sg, long amount, String date) {
        clock.setDate(d(date));
        fixtures.loginAs(UserRole.ACCOUNTANT);
        PaymentRequest r = new PaymentRequest();
        r.setStudentId(sg.student());
        r.setGroupId(sg.group());
        r.setAmount(BigDecimal.valueOf(amount));
        r.setCashRegisterId(fixtures.cashRegister(false));
        r.setPaymentMethod(PaymentMethod.CASH);
        payments.createPayment(r, null);
    }

    private GroupRequest request(Long groupId, String endDate, String status) {
        return inTx(() -> {
            Group g = groupRepo.findById(groupId).orElseThrow();
            GroupRequest r = new GroupRequest();
            r.setGroupName(g.getGroupName());
            r.setCourseId(g.getCourse().getId());
            r.setStartDate(g.getStartDate());
            r.setEndDate(endDate != null ? d(endDate) : null);
            r.setMaxStudents(g.getMaxStudents());
            r.setStatus(status);
            return r;
        });
    }

    private void updateEnd(Long groupId, String endDate) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        groups.updateGroup(groupId, request(groupId, endDate, null));
    }

    private List<LocalDate> periodStarts(Long sgId) {
        return inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(sgId)).stream()
            .map(BillingPeriod::getPeriodStart).toList();
    }

    private StudentGroup sg(Long id) {
        return inTx(() -> sgRepo.findById(id).orElseThrow());
    }

    private static String code(Throwable t) {
        return ((CodedException) t).getCode();
    }

    private static Map<Long, GroupEndDateDtos.ImpactRow> bySg(GroupEndDateDtos.Impact impact) {
        return impact.enrollments().stream()
            .collect(Collectors.toMap(GroupEndDateDtos.ImpactRow::studentGroupId, Function.identity()));
    }

    // ── TASK 1: validatsiya ─────────────────────────────────────────────

    @Test
    void validation_endBeforeStart_andPastForActive() {
        clock.setDate(d(TODAY));
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        Long course = fixtures.course(700_000);
        GroupRequest r = new GroupRequest();
        r.setGroupName("TOQ/15:30-ZAMIRA");
        r.setCourseId(course);
        r.setStartDate(d("07.09.2026"));

        r.setEndDate(d("07.01.2026"));                          // prod'dagi xato
        assertThatThrownBy(() -> groups.createGroup(r))
            .isInstanceOf(CodedException.class).extracting(GroupEndDateTest::code).isEqualTo("group.endDate.beforeStart");
        r.setEndDate(d("07.09.2026"));                          // teng ham emas
        assertThatThrownBy(() -> groups.createGroup(r))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.beforeStart");

        r.setEndDate(d("09.10.2026"));                          // ACTIVE, kecha tugagan
        assertThatThrownBy(() -> groups.createGroup(r))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.past");
        r.setStatus("FORMING");
        assertThatThrownBy(() -> groups.createGroup(r))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.past");

        r.setStatus("COMPLETED");                               // tugagan guruh — o'tgan sana mumkin
        Long completed = groups.createGroup(r).getId();
        assertThatThrownBy(() -> groups.updateStatus(completed, "ACTIVE"))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.past");

        r.setStatus(null);
        r.setEndDate(d(TODAY));                                  // bugun tugaydi — ruxsat
        assertThat(groups.createGroup(r).getEndDate()).isEqualTo(d(TODAY));
        r.setEndDate(null);                                      // sanasiz — ruxsat
        assertThat(groups.createGroup(r).getId()).isNotNull();

        // Tahrirlash: buzilgan guruhni boshqa maydon bilan saqlab bo'lmaydi — sana to'g'rilanishi kerak
        Long broken = brokenGroup("JUFT/18:00-BOBUR");
        assertThatThrownBy(() -> groups.updateGroup(broken, request(broken, "07.01.2026", null)))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.beforeStart");
        assertThatThrownBy(() -> endDates.impact(broken, d("01.10.2026")))
            .extracting(GroupEndDateTest::code).isEqualTo("group.endDate.past");
        // Tugagan deb belgilash (PATCH) noto'g'ri sanada ham mumkin — JUFT/11:00-IRODA holati
        assertThat(groups.updateStatus(broken, "COMPLETED").getStatus()).isEqualTo(GroupStatus.COMPLETED);
    }

    @Test
    void validation_http400WithCode() throws Exception {
        clock.setDate(d(TODAY));
        Long broken = brokenGroup("TOQ/15:30-ZAMIRA");
        mvc.perform(get("/api/groups/{id}/end-date-impact", broken).param("endDate", "2026-09-01")
                .with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("group.endDate.beforeStart"));
    }

    // ── TASK 2: preview — buyurtmachi sanalari ──────────────────────────

    @Test
    void impact_customerDates_readOnly() throws Exception {
        Long zamira = brokenGroup("TOQ/15:30-ZAMIRA");
        Long sarvinoz = brokenGroup("TOQ/09:00-SARVINOZ");
        Long bobur = brokenGroup("JUFT/18:00-BOBUR");
        Long iroda14 = brokenGroup("JUFT/14:00-IRODA");
        Long iroda11 = brokenGroup("JUFT/11:00-IRODA");

        Sg mirjalol = enroll(zamira, "23.09.2026");              // № 0-000043: 300 000 to'lagan
        Sg zamiraB = enroll(zamira, "07.09.2026");               // to'lovsiz, 0 davr
        Sg sarvinozA = enroll(sarvinoz, "07.09.2026");           // 2 oy oldindan to'lagan
        Sg boburA = enroll(bobur, "14.09.2026");
        Sg irodaHeld = heldEnroll(iroda14, "07.09.2026");        // migratsiyada excluded (hold), 0 davr
        Sg irodaNew = enroll(iroda14, "01.10.2026");
        Sg iroda11A = enroll(iroda11, "07.09.2026");

        pay(mirjalol, 300_000, "25.09.2026");
        pay(sarvinozA, 1_400_000, "15.09.2026");
        clock.setDate(d(TODAY));
        job.runDaily(d(TODAY), "TEST");                         // R3: hech narsa yozilmaydi
        assertThat(periodStarts(mirjalol.id())).isEmpty();
        assertThat(sg(mirjalol.id()).getBalance()).isEqualByComparingTo("300000");
        assertThat(sg(mirjalol.id()).getNextPaymentDate()).isNull();          // kartada "—"
        assertThat(sg(mirjalol.id()).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        // TOQ/15:30-ZAMIRA → 14.12.2026
        GroupEndDateDtos.Impact z = endDates.impact(zamira, d("14.12.2026"));
        Map<Long, GroupEndDateDtos.ImpactRow> zr = bySg(z);
        GroupEndDateDtos.ImpactRow m = zr.get(mirjalol.id());
        assertThat(m.skipReason()).isNull();
        assertThat(m.periods()).extracting(GroupEndDateDtos.Period::start).containsExactly(d("23.09.2026"));
        assertThat(m.charge()).isEqualByComparingTo("700000");
        assertThat(m.balanceBefore()).isEqualByComparingTo("300000");
        assertThat(m.balanceAfter()).isEqualByComparingTo("-400000");
        assertThat(m.debtorBefore()).isFalse();
        assertThat(m.debtorAfter()).isTrue();
        assertThat(m.debtSinceAfter()).isEqualTo(d("23.09.2026"));
        assertThat(m.nextPaymentDateBefore()).isNull();
        assertThat(m.nextPaymentDateAfter()).isEqualTo(d("23.09.2026"));
        assertThat(zr.get(zamiraB.id()).periods()).extracting(GroupEndDateDtos.Period::start)
            .containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(zr.get(zamiraB.id()).balanceAfter()).isEqualByComparingTo("-1400000");
        assertThat(z.totals().affected()).isEqualTo(2);
        assertThat(z.totals().periods()).isEqualTo(3);
        assertThat(z.totals().amount()).isEqualByComparingTo("2100000");
        assertThat(z.totals().debtorsBefore()).isZero();
        assertThat(z.totals().debtorsAfter()).isEqualTo(2);

        // TOQ/09:00-SARVINOZ → 11.10.2026: ikkala davr yoziladi, oldindan to'lov yopadi; guruh tugaydi — keyingisi yo'q
        GroupEndDateDtos.ImpactRow s = bySg(endDates.impact(sarvinoz, d("11.10.2026"))).get(sarvinozA.id());
        assertThat(s.periods()).extracting(GroupEndDateDtos.Period::start)
            .containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(s.balanceAfter()).isEqualByComparingTo("0");
        assertThat(s.debtorAfter()).isFalse();
        assertThat(s.nextPaymentDateAfter()).isNull();                       // 07.11 ≥ 11.10 (R3)
        // ... tugash 14.11 bo'lsa keyingi to'lov 07.11
        assertThat(bySg(endDates.impact(sarvinoz, d("14.11.2026"))).get(sarvinozA.id()).nextPaymentDateAfter())
            .isEqualTo(d("07.11.2026"));

        // JUFT/18:00-BOBUR → 14.11.2026: faqat 14.09 (14.10 hali kelmagan)
        GroupEndDateDtos.ImpactRow b = bySg(endDates.impact(bobur, d("14.11.2026"))).get(boburA.id());
        assertThat(b.periods()).extracting(GroupEndDateDtos.Period::start).containsExactly(d("14.09.2026"));
        assertThat(b.balanceAfter()).isEqualByComparingTo("-700000");
        assertThat(b.debtorAfter()).isTrue();

        // JUFT/14:00-IRODA → 14.11.2026: hold'dagi yozilma davrsiz, yangisi 01.10 dan
        GroupEndDateDtos.Impact i = endDates.impact(iroda14, d("14.11.2026"));
        assertThat(bySg(i).get(irodaHeld.id()).skipReason()).isEqualTo("HOLD");
        assertThat(bySg(i).get(irodaHeld.id()).periods()).isEmpty();
        assertThat(bySg(i).get(irodaNew.id()).periods()).extracting(GroupEndDateDtos.Period::start)
            .containsExactly(d("01.10.2026"));
        assertThat(i.totals().held()).isEqualTo(1);

        // JUFT/11:00-IRODA → COMPLETED: tugagan guruhda davr yo'q
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        groups.updateStatus(iroda11, "COMPLETED");
        GroupEndDateDtos.ImpactRow c = bySg(endDates.impact(iroda11, d("30.09.2026"))).get(iroda11A.id());
        assertThat(c.skipReason()).isEqualTo("GROUP_STATUS");
        assertThat(c.periods()).isEmpty();
        assertThat(periodStarts(iroda11A.id())).isEmpty();

        // Preview hech narsa yozmagan
        assertThat(periodStarts(mirjalol.id())).isEmpty();
        assertThat(periodStarts(zamiraB.id())).isEmpty();
        assertThat(sg(mirjalol.id()).getBalance()).isEqualByComparingTo("300000");
        assertThat(inTx(() -> groupRepo.findById(zamira).orElseThrow().getEndDate())).isEqualTo(d("07.01.2026"));

        // HTTP: SA/A — ruxsat, ACCOUNTANT — yo'q
        mvc.perform(get("/api/groups/{id}/end-date-impact", zamira).param("endDate", "2026-12-14")
                .with(user("a").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totals.periods").value(3))
            .andExpect(jsonPath("$.data.newEndDate").value("2026-12-14"));
        mvc.perform(get("/api/groups/{id}/end-date-impact", zamira).param("endDate", "2026-12-14")
                .with(user("acc").roles("ACCOUNTANT")))
            .andExpect(status().isForbidden());
    }

    // ── TASK 3: uzaytirish — shu tranzaksiyada catch-up ─────────────────

    @Test
    void extend_catchUpInSameTransaction_holdSkipped_zeroPeriodFromAnchor() {
        Long zamira = brokenGroup("TOQ/15:30-ZAMIRA");
        Sg mirjalol = enroll(zamira, "23.09.2026");
        Sg zero = enroll(zamira, "07.09.2026");                   // 0 davr (excluded, keyin hold'dan chiqarilgan)
        Sg held = heldEnroll(zamira, "07.09.2026");               // hali hold'da
        pay(mirjalol, 300_000, "25.09.2026");
        clock.setDate(d(TODAY));

        updateEnd(zamira, "14.12.2026");

        assertThat(periodStarts(mirjalol.id())).containsExactly(d("23.09.2026"));
        assertThat(periodStarts(zero.id())).containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(periodStarts(held.id())).isEmpty();

        StudentGroup m = sg(mirjalol.id());
        assertThat(m.getBalance()).isEqualByComparingTo("-400000");
        assertThat(m.getDebtSince()).isEqualTo(d("23.09.2026"));
        assertThat(m.getPaymentStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(m.getNextPaymentDate()).isEqualTo(d("23.09.2026"));
        assertThat(sg(zero.id()).getBalance()).isEqualByComparingTo("-1400000");

        DebtorsListResponse.DebtorStudent row = debtors.debtors(DebtorService.Filter.defaults(), d(TODAY))
            .getStudents().stream().filter(st -> st.getStudentId().equals(mirjalol.student())).findFirst().orElse(null);
        assertThat(row).isNotNull();
        assertThat(row.getDebt()).isEqualByComparingTo("400000");
        assertThat(debtors.expected(null, null, d(TODAY)).getDays().stream()
            .flatMap(day -> day.getStudents().stream())
            .filter(st -> st.getStudentGroupId().equals(mirjalol.id())).findFirst().orElseThrow().getAmount())
            .isEqualByComparingTo("700000");                                  // 23.10 kutilmoqda

        // Qayta saqlash (o'zgarishsiz) — ikkinchi marta yozilmaydi
        updateEnd(zamira, "14.12.2026");
        assertThat(periodStarts(zero.id())).hasSize(2);
        assertThat(endDates.attention().rows()).extracting(GroupEndDateDtos.AttentionRow::groupId).doesNotContain(zamira);
    }

    @Test
    void shorten_pastPeriodsUntouched_nextPaymentCappedByNewEnd() {
        clock.setDate(d("07.09.2026"));
        Long group = fixtures.group(fixtures.course(700_000));
        inTx(() -> {
            Group g = groupRepo.findById(group).orElseThrow();
            g.setStartDate(d("07.09.2026"));
            g.setEndDate(d("14.12.2026"));
            groupRepo.save(g);
        });
        Sg a = enroll(group, "07.09.2026");
        pay(a, 1_400_000, "07.09.2026");
        clock.setDate(d(TODAY));
        job.runDaily(d(TODAY), "TEST");
        assertThat(periodStarts(a.id())).containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(sg(a.id()).getNextPaymentDate()).isEqualTo(d("07.11.2026"));

        updateEnd(group, "20.10.2026");                                      // qisqartirish (hali o'tmagan)

        assertThat(periodStarts(a.id())).containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(sg(a.id()).getBalance()).isEqualByComparingTo("0");
        assertThat(sg(a.id()).getNextPaymentDate()).isNull();                // 07.11 ≥ 20.10 (R3)
        assertThat(inTx(() -> periodRepo.findByStudentGroupIdOrderByPeriodStartAsc(a.id())).get(1).getPeriodEnd())
            .isEqualTo(d("06.11.2026"));
    }

    // ── TASK 3 diagnozi: kunlik job ham o'zi tiklaydi ───────────────────

    @Test
    void dailyJob_afterEndDateFixedDirectly_writesMissingPeriods_exceptHold() {
        Long zamira = brokenGroup("TOQ/15:30-ZAMIRA");
        Sg zero = enroll(zamira, "07.09.2026");
        Sg held = heldEnroll(zamira, "07.09.2026");
        clock.setDate(d(TODAY));
        job.runDaily(d(TODAY), "TEST");
        assertThat(periodStarts(zero.id())).isEmpty();

        inTx(() -> {                                                         // UI'siz, to'g'ridan bazada
            Group g = groupRepo.findById(zamira).orElseThrow();
            g.setEndDate(d("14.12.2026"));
            groupRepo.save(g);
        });
        job.runDaily(d(TODAY), "TEST");

        assertThat(periodStarts(zero.id())).containsExactly(d("07.09.2026"), d("07.10.2026"));
        assertThat(periodStarts(held.id())).isEmpty();
    }

    // ── TASK 4: diqqat talab qiladigan guruhlar ─────────────────────────

    @Test
    void attention_listsBrokenGroups_untilFixed() throws Exception {
        Long zamira = brokenGroup("TOQ/15:30-ZAMIRA");
        Long iroda11 = brokenGroup("JUFT/11:00-IRODA");
        enroll(zamira, "23.09.2026");
        enroll(zamira, "07.09.2026");
        heldEnroll(zamira, "07.09.2026");
        enroll(iroda11, "07.09.2026");
        Long ok = fixtures.group(fixtures.course(700_000));                  // tugash sanasisiz — ro'yxatda yo'q
        enroll(ok, "07.09.2026");
        clock.setDate(d(TODAY));

        GroupEndDateDtos.Attention a = endDates.attention();
        assertThat(a.groups()).isEqualTo(2);
        GroupEndDateDtos.AttentionRow z = a.rows().stream().filter(r -> r.groupId().equals(zamira)).findFirst().orElseThrow();
        assertThat(z.endBeforeStart()).isTrue();
        assertThat(z.openEnrollments()).isEqualTo(3);
        assertThat(z.stoppedEnrollments()).isEqualTo(2);
        assertThat(z.heldEnrollments()).isEqualTo(1);
        assertThat(z.missedPeriods()).isEqualTo(3);
        assertThat(z.missedAmount()).isEqualByComparingTo("2100000");
        assertThat(a.stoppedEnrollments()).isEqualTo(3);

        mvc.perform(get("/api/groups/attention").with(user("sa").roles("SUPER_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.groups").value(2));
        mvc.perform(get("/api/groups/attention").with(user("t").roles("TEACHER")))
            .andExpect(status().isForbidden());

        updateEnd(zamira, "14.12.2026");
        groups.updateStatus(iroda11, "COMPLETED");
        assertThat(endDates.attention().groups()).isZero();
    }
}
