package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.billing.BillingLocks;
import com.crm.dto.request.CashHandoverRequests;
import com.crm.dto.response.CashCategoryTotalDto;
import com.crm.dto.response.CashHandoverDto;
import com.crm.dto.response.CashHandoverPreviewDto;
import com.crm.entity.CashCategory;
import com.crm.entity.CashHandover;
import com.crm.entity.CashHandoverLine;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.enums.CashDirection;
import com.crm.entity.enums.CashHandoverStatus;
import com.crm.entity.enums.CashRegisterKind;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.PaymentMethod;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashHandoverLineRepository;
import com.crm.repository.CashHandoverRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Kunlik kassa topshirish (docs/design/cash-and-books.md §1.2.3, V86):
 * <ol>
 *   <li>admin preview'ni ko'radi — topshirilmagan yozuvlar usul (naqd / karta / terminal / Click / Payme ...)
 *       va kategoriya bo'yicha, kutilgan naqd = {@code cash_balance − float_amount};</li>
 *   <li>admin topshiradi (SUBMITTED) — shu paytgacha bo'lgan yozuvlar topshirishga bog'lanadi; keyingi
 *       to'lovlar keyingi topshirishga tushadi, kassa yopilmaydi;</li>
 *   <li>SA qabul qiladi: sanalgan naqd Direktor kassasiga TRANSFER; farq — CASH_SHORTAGE / CASH_SURPLUS
 *       (jarima yo'q); naqdsiz — faqat solishtirish ({@code actual}, {@code diff}), pul ko'chirilmaydi;</li>
 *   <li>yoki rad etadi — yozuvlar bo'shatiladi, admin qayta topshiradi.</li>
 * </ol>
 * Qulf: kassa qatori (submit — admin kassasi; accept — ikkala kassa, id tartibida).
 */
@Service
@RequiredArgsConstructor
public class CashHandoverService {

    private static final int KEY_MAX = 100;

    private final CashHandoverRepository handoverRepository;
    private final CashHandoverLineRepository lineRepository;
    private final CashTransactionRepository transactionRepository;
    private final CashRegisterRepository registerRepository;
    private final UserRepository userRepository;
    private final CashRegisterService cashRegisterService;
    private final CashAccessService access;
    private final CashCategoryService categoryService;
    private final CashCategoryTotals categoryTotals;
    private final BillingLocks locks;
    private final Clock billingClock;

    // ── Preview ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CashHandoverPreviewDto preview(Long registerId) {
        CashRegister register = access.requireAccessible(registerId);
        requireAdminRegister(register);
        Snapshot s = snapshot(register);
        Long pending = handoverRepository.findAll(byRegisterAndStatus(registerId, CashHandoverStatus.SUBMITTED))
            .stream().map(CashHandover::getId).findFirst().orElse(null);
        return CashHandoverPreviewDto.builder()
            .cashRegisterId(register.getId())
            .cashRegisterName(register.getName())
            .cashBalance(register.getCashBalance())
            .floatAmount(register.getFloatAmount())
            .expectedCash(s.expectedCash())
            .transactionCount(s.transactions().size())
            .fromDate(s.transactions().stream().map(CashTransaction::getTransactionDate)
                .min(Comparator.naturalOrder()).orElse(null))
            .toDate(s.transactions().stream().map(CashTransaction::getTransactionDate)
                .max(Comparator.naturalOrder()).orElse(null))
            .previewHash(s.hash())
            .pendingHandoverId(pending)
            .lines(s.lines())
            .byCategory(byCategory(register.getId()))
            .build();
    }

    // ── Topshirish (admin) ──────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "CashRegister",
        summary = "'Kassa topshirildi: naqd ' + #request.declaredCash", entityId = "#registerId")
    public CashHandoverDto submit(Long registerId, CashHandoverRequests.Submit request, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<CashHandover> existing = handoverRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), registerId);
            }
        }
        BigDecimal declared = request != null ? request.getDeclaredCash() : null;
        if (declared == null || declared.signum() < 0) {
            throw CodedException.badRequest("cash.handover.declaredRequired");
        }

        CashRegister register = locks.lockCashRegister(registerId);
        access.assertAccessible(register);
        requireAdminRegister(register);
        if (key != null) {
            Optional<CashHandover> existing = handoverRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), registerId);
            }
        }
        if (handoverRepository.existsByCashRegisterIdAndStatus(registerId, CashHandoverStatus.SUBMITTED)) {
            throw new ConflictException("cash.handover.pending");
        }
        Snapshot s = snapshot(register);
        if (request.getPreviewHash() != null && !request.getPreviewHash().equals(s.hash())) {
            throw new ConflictException("cash.handover.previewChanged");
        }

        CashHandover h = handoverRepository.save(CashHandover.builder()
            .cashRegisterId(registerId)
            .businessDate(LocalDate.now(billingClock))
            .status(CashHandoverStatus.SUBMITTED)
            .floatAmount(nz(register.getFloatAmount()))
            .expectedCash(s.expectedCash())
            .declaredCash(declared)
            .note(trim(request.getNote()))
            .submittedBy(access.currentUserId())
            .submittedAt(LocalDateTime.now(billingClock))
            .idempotencyKey(key)
            .build());
        if (s.maxTxId() != null) {
            transactionRepository.stampHandover(h.getId(), registerId, s.maxTxId());
        }
        lineRepository.saveAll(s.lines().stream().map(l -> CashHandoverLine.builder()
            .handoverId(h.getId())
            .paymentMethod(l.getPaymentMethod())
            .expectedIn(l.getExpectedIn())
            .expectedOut(l.getExpectedOut())
            .expectedNet(l.getExpectedNet())
            .build()).toList());
        return toDto(h);
    }

    // ── Qabul / rad etish (SA) ──────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "CashHandover",
        summary = "'Topshirish qabul qilindi: naqd ' + #result.countedCash + ', farq ' + #result.cashDiff",
        entityId = "#id")
    public CashHandoverDto accept(Long id, CashHandoverRequests.Accept request) {
        access.requireDirector();
        BigDecimal counted = request != null ? request.getCountedCash() : null;
        if (counted == null || counted.signum() < 0) {
            throw CodedException.badRequest("cash.handover.countedRequired");
        }
        CashHandover candidate = find(id);
        CashRegister target = resolveTarget(request.getTargetCashRegisterId());
        BillingLocks.Locked locked = locks.acquire(BillingLocks.Plan.of()
            .cashRegister(candidate.getCashRegisterId())
            .cashRegister(target.getId()));
        CashHandover h = find(id);     // qulf ostida qayta o'qiladi: parallel qabul/rad etish
        if (h.getStatus() != CashHandoverStatus.SUBMITTED) {
            throw new ConflictException("cash.handover.notSubmitted", h.getStatus());
        }
        CashRegister admin = locked.cashRegister(h.getCashRegisterId());
        CashRegister director = locked.cashRegister(target.getId());
        access.requireDirectorRegister(director);
        LocalDate today = LocalDate.now(billingClock);
        String note = "Topshirish #" + h.getId() + " (" + h.getBusinessDate() + ")";

        if (counted.signum() > 0) {
            cashRegisterService.recordHandoverTransfer(admin, director, counted, h.getId(), today, note);
        }
        BigDecimal diff = counted.subtract(h.getExpectedCash());
        if (diff.signum() != 0) {
            String why = trim(request.getDiffNote());
            cashRegisterService.recordHandoverDiff(admin, diff, h.getId(), today,
                note + (why != null ? ": " + why : ""));
        }

        Map<PaymentMethod, BigDecimal> actuals = parseActuals(request.getActuals());
        Map<PaymentMethod, CashHandoverLine> lines = new EnumMap<>(PaymentMethod.class);
        lineRepository.findByHandoverIdOrderByIdAsc(h.getId()).forEach(l -> lines.put(l.getPaymentMethod(), l));
        actuals.keySet().forEach(m -> lines.computeIfAbsent(m, k -> CashHandoverLine.builder()
            .handoverId(h.getId()).paymentMethod(k)
            .expectedIn(BigDecimal.ZERO).expectedOut(BigDecimal.ZERO).expectedNet(BigDecimal.ZERO).build()));
        CashHandoverLine cashLine = lines.computeIfAbsent(PaymentMethod.CASH, k -> CashHandoverLine.builder()
            .handoverId(h.getId()).paymentMethod(k)
            .expectedIn(BigDecimal.ZERO).expectedOut(BigDecimal.ZERO).expectedNet(BigDecimal.ZERO).build());
        cashLine.setActual(counted);
        cashLine.setDiff(diff);
        for (CashHandoverLine l : lines.values()) {
            if (l.getPaymentMethod() != PaymentMethod.CASH && actuals.containsKey(l.getPaymentMethod())) {
                BigDecimal actual = actuals.get(l.getPaymentMethod());
                l.setActual(actual);
                l.setDiff(actual.subtract(l.getExpectedNet()));
            }
        }
        lineRepository.saveAll(lines.values());

        h.setStatus(CashHandoverStatus.ACCEPTED);
        h.setTargetCashRegisterId(director.getId());
        h.setCountedCash(counted);
        h.setCashDiff(diff);
        h.setDiffNote(trim(request.getDiffNote()));
        h.setDecidedBy(access.currentUserId());
        h.setDecidedAt(LocalDateTime.now(billingClock));
        return toDto(handoverRepository.save(h));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "CashHandover",
        summary = "'Topshirish rad etildi: ' + #request.reason", entityId = "#id")
    public CashHandoverDto reject(Long id, CashHandoverRequests.Reject request) {
        access.requireDirector();
        String reason = request != null ? trim(request.getReason()) : null;
        if (reason == null || reason.length() < 3) {
            throw CodedException.badRequest("cash.handover.reasonRequired");
        }
        CashHandover candidate = find(id);
        locks.lockCashRegister(candidate.getCashRegisterId());
        CashHandover h = find(id);
        if (h.getStatus() != CashHandoverStatus.SUBMITTED) {
            throw new ConflictException("cash.handover.notSubmitted", h.getStatus());
        }
        transactionRepository.releaseHandover(h.getId());
        h.setStatus(CashHandoverStatus.REJECTED);
        h.setRejectReason(reason);
        h.setDecidedBy(access.currentUserId());
        h.setDecidedAt(LocalDateTime.now(billingClock));
        return toDto(handoverRepository.save(h));
    }

    // ── O'qish ──────────────────────────────────────────────────────────

    /** SA — hammasi; ADMIN / ACCOUNTANT — faqat o'z kassasi(lari)niki. */
    @Transactional(readOnly = true)
    public Page<CashHandoverDto> list(String status, Long registerId, LocalDate from, LocalDate to,
                                      int page, int size) {
        CashHandoverStatus st = parseStatus(status);
        Specification<CashHandover> spec = Specification.where(null);
        if (st != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), st));
        }
        if (registerId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("cashRegisterId"), registerId));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("businessDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("businessDate"), to));
        }
        List<Long> visible = access.visibleRegisterIds();
        if (visible != null) {
            spec = visible.isEmpty()
                ? spec.and((root, q, cb) -> cb.disjunction())
                : spec.and((root, q, cb) -> root.get("cashRegisterId").in(visible));
        }
        return handoverRepository.findAll(spec, PageRequest.of(page, Math.min(Math.max(size, 1), 200),
            Sort.by(Sort.Direction.DESC, "submittedAt", "id"))).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public CashHandoverDto get(Long id) {
        CashHandover h = find(id);
        access.requireAccessible(h.getCashRegisterId());
        return toDto(h);
    }

    /** SA: davrdagi qabul qilingan topshirishlar kassa bo'yicha — soni, topshirilgan naqd, kamomad, ortiqcha. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(LocalDate from, LocalDate to) {
        access.requireDirector();
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        Specification<CashHandover> spec = (root, q, cb) -> cb.and(
            cb.equal(root.get("status"), CashHandoverStatus.ACCEPTED),
            cb.isFalse(root.get("legacy")));
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("businessDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("businessDate"), to));
        }
        for (CashHandover h : handoverRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "cashRegisterId"))) {
            Map<String, Object> r = rows.computeIfAbsent(h.getCashRegisterId(), regId -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cashRegisterId", regId);
                m.put("cashRegisterName", registerRepository.findById(regId).map(CashRegister::getName).orElse(null));
                m.put("count", 0L);
                m.put("countedCash", BigDecimal.ZERO);
                m.put("shortage", BigDecimal.ZERO);
                m.put("surplus", BigDecimal.ZERO);
                return m;
            });
            r.put("count", (Long) r.get("count") + 1);
            r.put("countedCash", ((BigDecimal) r.get("countedCash")).add(nz(h.getCountedCash())));
            BigDecimal diff = nz(h.getCashDiff());
            if (diff.signum() < 0) {
                r.put("shortage", ((BigDecimal) r.get("shortage")).add(diff.negate()));
            } else if (diff.signum() > 0) {
                r.put("surplus", ((BigDecimal) r.get("surplus")).add(diff));
            }
        }
        return new ArrayList<>(rows.values());
    }

    // ── Ichki ───────────────────────────────────────────────────────────

    private record Snapshot(List<CashTransaction> transactions, List<CashHandoverDto.Line> lines,
                            BigDecimal expectedCash, Long maxTxId, String hash) {
    }

    private Snapshot snapshot(CashRegister register) {
        List<CashTransaction> txs = transactionRepository.findUnhanded(register.getId());
        Map<PaymentMethod, BigDecimal[]> acc = new EnumMap<>(PaymentMethod.class);
        Long maxId = null;
        for (CashTransaction t : txs) {
            maxId = maxId == null || t.getId() > maxId ? t.getId() : maxId;
            boolean in = cashRegisterService.direction(t) == CashDirection.IN;
            for (Map.Entry<PaymentMethod, BigDecimal> part : methodParts(t).entrySet()) {
                BigDecimal[] sums = acc.computeIfAbsent(part.getKey(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                sums[in ? 0 : 1] = sums[in ? 0 : 1].add(part.getValue());
            }
        }
        List<CashHandoverDto.Line> lines = new ArrayList<>();
        acc.forEach((m, sums) -> lines.add(CashHandoverDto.Line.builder()
            .paymentMethod(m)
            .expectedIn(sums[0])
            .expectedOut(sums[1])
            .expectedNet(sums[0].subtract(sums[1]))
            .build()));
        BigDecimal expected = nz(register.getCashBalance()).subtract(nz(register.getFloatAmount()));
        String hash = (maxId != null ? maxId : 0) + "-" + txs.size() + "-"
            + nz(register.getCashBalance()).stripTrailingZeros().toPlainString();
        return new Snapshot(txs, lines, expected, maxId, hash);
    }

    /** Yozuv summasi aniq usullar bo'yicha: CASH_AND_CARD — CASH + CARD, eski BANK — TERMINAL, usulsiz — CASH. */
    private static Map<PaymentMethod, BigDecimal> methodParts(CashTransaction t) {
        Map<PaymentMethod, BigDecimal> out = new EnumMap<>(PaymentMethod.class);
        PaymentMethod m = t.getPaymentMethod();
        BigDecimal amount = nz(t.getAmount());
        if (m == PaymentMethod.CASH_AND_CARD) {
            if (t.getCashPart() != null && t.getCardPart() != null) {
                if (t.getCashPart().signum() != 0) {
                    out.put(PaymentMethod.CASH, t.getCashPart());
                }
                if (t.getCardPart().signum() != 0) {
                    out.put(PaymentMethod.CARD, t.getCardPart());
                }
                return out;
            }
            out.put(PaymentMethod.CASH, amount);
            return out;
        }
        out.put(m == null ? PaymentMethod.CASH : (m == PaymentMethod.BANK ? PaymentMethod.TERMINAL : m), amount);
        return out;
    }

    /**
     * Topshirilmagan yozuvlar kategoriya bo'yicha (kategoriyaning o'z yo'nalishida; o'tkazmalar kirmaydi) —
     * {@link CashCategoryTotals} bilan bir xil qoida (kategoriyasiz eski yozuv ham).
     */
    private List<CashCategoryTotalDto> byCategory(Long registerId) {
        return categoryTotals.view(categoryTotals.unhandedTotals(registerId),
            categoryTotals.bookSaleKinds(registerId, null, null, true), categoryService.all());
    }

    private CashRegister resolveTarget(Long targetId) {
        if (targetId != null) {
            CashRegister r = registerRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("CashRegister", targetId));
            access.requireDirectorRegister(r);
            return r;
        }
        List<CashRegister> directors = registerRepository.findByStatus(CashRegisterStatus.ACTIVE).stream()
            .filter(r -> r.getKind() == CashRegisterKind.DIRECTOR)
            .toList();
        if (directors.size() != 1) {
            throw CodedException.badRequest("cash.handover.targetRequired", directors.size());
        }
        return directors.get(0);
    }

    private static void requireAdminRegister(CashRegister register) {
        if (register.getKind() != CashRegisterKind.ADMIN) {
            throw CodedException.badRequest("cash.handover.adminRegisterOnly");
        }
    }

    private CashHandoverDto replay(CashHandover existing, Long registerId) {
        if (!existing.getCashRegisterId().equals(registerId)) {
            throw new ConflictException("cash.handover.idempotencyMismatch");
        }
        access.requireAccessible(registerId);
        CashHandoverDto dto = toDto(existing);
        dto.setIdempotentReplay(true);
        return dto;
    }

    private CashHandover find(Long id) {
        return handoverRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("CashHandover", id));
    }

    private static Specification<CashHandover> byRegisterAndStatus(Long registerId, CashHandoverStatus status) {
        return (root, q, cb) -> cb.and(
            cb.equal(root.get("cashRegisterId"), registerId),
            cb.equal(root.get("status"), status));
    }

    private static Map<PaymentMethod, BigDecimal> parseActuals(Map<String, BigDecimal> raw) {
        Map<PaymentMethod, BigDecimal> out = new EnumMap<>(PaymentMethod.class);
        if (raw == null) {
            return out;
        }
        raw.forEach((k, v) -> {
            PaymentMethod m = PaymentMethod.parseOrNull(k);
            if (m == null || m == PaymentMethod.CASH_AND_CARD || m == PaymentMethod.BANK) {
                throw CodedException.badRequest("cash.handover.actualMethodInvalid", k);
            }
            if (v == null || v.signum() < 0) {
                throw CodedException.badRequest("cash.handover.actualInvalid", k);
            }
            if (m != PaymentMethod.CASH) {
                out.put(m, v);
            }
        });
        return out;
    }

    private static CashHandoverStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CashHandoverStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("cash.handover.statusInvalid", raw);
        }
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (k.length() > KEY_MAX) {
            throw CodedException.badRequest("payroll.idempotency.keyTooLong", KEY_MAX);
        }
        return k;
    }

    private static String trim(String s) {
        return s != null && !s.isBlank() ? s.trim() : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private String userName(Long id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id).map(u -> u.getFirstName() + " " + u.getLastName()).orElse(null);
    }

    private CashHandoverDto toDto(CashHandover h) {
        List<CashHandoverDto.Line> lines = lineRepository.findByHandoverIdOrderByIdAsc(h.getId()).stream()
            .sorted(Comparator.comparing(CashHandoverLine::getPaymentMethod))
            .map(l -> CashHandoverDto.Line.builder()
                .paymentMethod(l.getPaymentMethod())
                .expectedIn(l.getExpectedIn())
                .expectedOut(l.getExpectedOut())
                .expectedNet(l.getExpectedNet())
                .actual(l.getActual())
                .diff(l.getDiff())
                .build())
            .toList();
        return CashHandoverDto.builder()
            .id(h.getId())
            .uuid(h.getUuid())
            .cashRegisterId(h.getCashRegisterId())
            .cashRegisterName(registerRepository.findById(h.getCashRegisterId()).map(CashRegister::getName).orElse(null))
            .targetCashRegisterId(h.getTargetCashRegisterId())
            .targetCashRegisterName(h.getTargetCashRegisterId() != null
                ? registerRepository.findById(h.getTargetCashRegisterId()).map(CashRegister::getName).orElse(null)
                : null)
            .businessDate(h.getBusinessDate())
            .status(h.getStatus())
            .floatAmount(h.getFloatAmount())
            .expectedCash(h.getExpectedCash())
            .declaredCash(h.getDeclaredCash())
            .countedCash(h.getCountedCash())
            .cashDiff(h.getCashDiff())
            .note(h.getNote())
            .diffNote(h.getDiffNote())
            .rejectReason(h.getRejectReason())
            .submittedBy(h.getSubmittedBy())
            .submittedByName(userName(h.getSubmittedBy()))
            .submittedAt(h.getSubmittedAt())
            .decidedBy(h.getDecidedBy())
            .decidedByName(userName(h.getDecidedBy()))
            .decidedAt(h.getDecidedAt())
            .legacy(h.isLegacy())
            .lines(lines)
            .build();
    }
}
