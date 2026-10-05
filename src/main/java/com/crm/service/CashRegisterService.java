package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.dto.request.CashRegisterCreateDto;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.CashBalanceDto;
import com.crm.dto.response.CashChannelReportDto;
import com.crm.dto.response.CashRegisterDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.PaymentChannel;
import com.crm.entity.enums.PaymentMethod;
import com.crm.entity.enums.CashDirection;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.CashTransactionStatus;
import com.crm.entity.enums.CashTransactionType;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashRegisterService {

    private final CashRegisterRepository cashRegisterRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final com.crm.billing.BillingLocks billingLocks;
    private final CashChannelService cashChannelService;

    @Transactional(readOnly = true)
    public List<CashRegisterDto> getAll(String status) {
        List<CashRegister> registers;
        if (status != null && !status.isBlank()) {
            CashRegisterStatus registerStatus = parseRegisterStatus(status);
            registers = cashRegisterRepository.findByStatus(registerStatus);
        } else {
            registers = cashRegisterRepository.findAll();
        }
        Map<Long, Map<PaymentChannel, CashChannelService.Flow>> flows = cashChannelService.flowsByRegister();
        return registers.stream()
            .map(r -> withChannels(toRegisterDto(r), flows.get(r.getId())))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CashRegisterDto getById(Long id) {
        return withChannels(toRegisterDto(findRegisterById(id)), cashChannelService.flows(id, null, null));
    }

    /** {@code GET /{id}/by-method}: davr oqimi to'lov usuli guruhlari bo'yicha. */
    @Transactional(readOnly = true)
    public CashChannelReportDto getChannelReport(Long id, LocalDate from, LocalDate to) {
        findRegisterById(id);
        Map<PaymentChannel, CashChannelService.Flow> flows = cashChannelService.flows(id, from, to);
        return CashChannelReportDto.builder()
            .cashRegisterId(id)
            .from(from)
            .to(to)
            .channels(CashChannelService.toDtos(flows))
            .total(CashChannelService.total(flows))
            .build();
    }

    private static CashRegisterDto withChannels(CashRegisterDto dto,
                                                Map<PaymentChannel, CashChannelService.Flow> flows) {
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<PaymentChannel, CashChannelService.Flow> f = flows != null ? flows : CashChannelService.emptyFlows();
        for (PaymentChannel c : PaymentChannel.values()) {
            CashChannelService.Flow one = f.get(c);
            byMethod.put(c.name(), one != null ? one.net() : BigDecimal.ZERO);
        }
        dto.setBalanceByMethod(byMethod);
        return dto;
    }

    @Transactional
    public CashRegisterDto create(CashRegisterCreateDto dto) {
        CashRegister register = new CashRegister();
        register.setName(dto.getName());
        register.setAcceptOnlinePayment(Boolean.TRUE.equals(dto.getAcceptOnlinePayment()));
        register.setPlasticBalance(BigDecimal.ZERO);
        register.setCashBalance(BigDecimal.ZERO);
        register.setBalance(BigDecimal.ZERO);
        if (Boolean.TRUE.equals(dto.getArchived())) {
            register.setArchived(true);
            register.setStatus(CashRegisterStatus.ARCHIVED);
        } else {
            register.setArchived(false);
            register.setStatus(CashRegisterStatus.ACTIVE);
        }
        if (dto.getModeratorId() != null) {
            register.setModerator(findUserById(dto.getModeratorId()));
        }
        return toRegisterDto(cashRegisterRepository.save(register));
    }

    @Transactional
    public CashRegisterDto update(Long id, CashRegisterCreateDto dto) {
        CashRegister register = findRegisterById(id);
        if (dto.getName() != null) {
            register.setName(dto.getName());
        }
        if (dto.getAcceptOnlinePayment() != null) {
            register.setAcceptOnlinePayment(dto.getAcceptOnlinePayment());
        }
        if (dto.getArchived() != null) {
            register.setArchived(dto.getArchived());
            register.setStatus(dto.getArchived()
                ? CashRegisterStatus.ARCHIVED : CashRegisterStatus.ACTIVE);
        }
        if (dto.getModeratorId() != null) {
            register.setModerator(findUserById(dto.getModeratorId()));
        }
        return toRegisterDto(cashRegisterRepository.save(register));
    }

    @Transactional
    public String delete(Long id) {
        CashRegister register = findRegisterById(id);
        if (cashTransactionRepository.countByCashRegister_Id(id) > 0) {
            register.setStatus(CashRegisterStatus.ARCHIVED);
            register.setArchived(true);
            cashRegisterRepository.save(register);
            return "Tranzaksiyalari bor, arxivlandi";
        }
        cashRegisterRepository.deleteById(id);
        return "O'chirildi";
    }

    @Transactional
    public CashRegisterDto updateStatus(Long id, String status) {
        CashRegister register = findRegisterById(id);
        CashRegisterStatus registerStatus = parseRegisterStatus(status);
        register.setStatus(registerStatus);
        register.setArchived(registerStatus == CashRegisterStatus.ARCHIVED);
        return toRegisterDto(cashRegisterRepository.save(register));
    }

    /**
     * Saqlangan chelaklar ({@code cash_balance}, {@code plastic_balance}) + tranzaksiyalardan
     * to'lov usuli guruhlari bo'yicha qoldiq. Farq ({@code unattributed*}) — tranzaksiyasiz
     * o'zgarish; u guruhlarga taqsimlanmaydi.
     */
    @Transactional(readOnly = true)
    public CashBalanceDto getBalance(Long id) {
        CashRegister register = findRegisterById(id);
        Map<PaymentChannel, CashChannelService.Flow> flows = cashChannelService.flows(id, null, null);
        BigDecimal derivedCash = BigDecimal.ZERO;
        BigDecimal derivedNonCash = BigDecimal.ZERO;
        for (Map.Entry<PaymentChannel, CashChannelService.Flow> e : flows.entrySet()) {
            if (e.getKey().isCash()) {
                derivedCash = derivedCash.add(e.getValue().net());
            } else {
                derivedNonCash = derivedNonCash.add(e.getValue().net());
            }
        }
        BigDecimal cashDiff = nz(register.getCashBalance()).subtract(derivedCash);
        BigDecimal nonCashDiff = nz(register.getPlasticBalance()).subtract(derivedNonCash);
        return CashBalanceDto.builder()
            .cashRegisterId(register.getId())
            .balance(register.getBalance())
            .cashBalance(register.getCashBalance())
            .plasticBalance(register.getPlasticBalance())
            .byMethod(CashChannelService.toDtos(flows))
            .unattributedCash(cashDiff)
            .unattributedNonCash(nonCashDiff)
            .reconciled(cashDiff.signum() == 0 && nonCashDiff.signum() == 0)
            .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public Page<CashTransactionDto> getTransactions(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            Long teacherId,
            String type,
            String paymentMethod,
            Pageable pageable) {
        return getTransactions(cashRegisterId, from, to, studentId, teacherId, type, paymentMethod, null, pageable);
    }

    /** {@code channel} — to'lov usuli guruhi (CASH, CARD, TERMINAL, ONLINE, OTHER). */
    @Transactional(readOnly = true)
    public Page<CashTransactionDto> getTransactions(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            Long teacherId,
            String type,
            String paymentMethod,
            String channel,
            Pageable pageable) {

        findRegisterById(cashRegisterId);

        CashTransactionType typeFilter = parseTransactionType(type);
        PaymentMethod methodFilter = parsePaymentMethod(paymentMethod);
        PaymentChannel channelFilter = parseChannel(channel);

        log.debug(
            "getTransactions registerId={}, from={}, to={}, studentId={}, teacherId={}, type={}, paymentMethod={}, channel={}, page={}, size={}",
            cashRegisterId, from, to, studentId, teacherId, typeFilter, methodFilter, channelFilter,
            pageable.getPageNumber(), pageable.getPageSize());

        Specification<CashTransaction> spec = buildTransactionSpec(
            cashRegisterId, from, to, studentId, teacherId, typeFilter, methodFilter)
            .and(channelSpec(channelFilter));

        Page<CashTransaction> page = cashTransactionRepository.findAll(spec, pageable);

        log.debug("getTransactions registerId={} matched {} of {} total",
            cashRegisterId, page.getNumberOfElements(), page.getTotalElements());

        return page.map(this::toTransactionDto);
    }

    @Transactional(readOnly = true)
    public byte[] exportTransactions(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            Long teacherId,
            String type,
            String paymentMethod) {
        return exportTransactions(cashRegisterId, from, to, studentId, teacherId, type, paymentMethod, null);
    }

    @Transactional(readOnly = true)
    public byte[] exportTransactions(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            Long teacherId,
            String type,
            String paymentMethod,
            String channel) {

        findRegisterById(cashRegisterId);
        CashTransactionType typeFilter = parseTransactionType(type);
        PaymentMethod methodFilter = parsePaymentMethod(paymentMethod);
        Specification<CashTransaction> spec = buildTransactionSpec(
            cashRegisterId, from, to, studentId, teacherId, typeFilter, methodFilter)
            .and(channelSpec(parseChannel(channel)));
        List<CashTransaction> transactions = cashTransactionRepository.findAll(spec);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Tranzaksiyalar");

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            String[] headers = {
                "ID", "Sana", "Turi", "Yo'nalish", "Usul", "O'quvchi", "O'qituvchi",
                "Nomi", "Summa (±)", "Izoh", "Holat", "Yaratuvchi", "Usul guruhi"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (CashTransaction t : transactions) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(t.getId() != null ? t.getId() : 0);
                row.createCell(1).setCellValue(
                    t.getTransactionDate() != null ? t.getTransactionDate().toString() : "");
                CashDirection direction = direction(t);
                row.createCell(2).setCellValue(
                    t.getType() != null ? t.getType().name() : "");
                row.createCell(3).setCellValue(direction.name());
                row.createCell(4).setCellValue(
                    t.getPaymentMethod() != null ? t.getPaymentMethod().name() : "");
                row.createCell(5).setCellValue(t.getStudent() != null
                    ? t.getStudent().getFirstName() + " " + t.getStudent().getLastName() : "");
                row.createCell(6).setCellValue(t.getTeacher() != null
                    ? t.getTeacher().getFirstName() + " " + t.getTeacher().getLastName() : "");
                row.createCell(7).setCellValue(
                    t.getTransactionName() != null ? t.getTransactionName() : "");
                // Ishorali summa: kirim +, chiqim − (DTO dagi signedAmount bilan bir xil)
                row.createCell(8).setCellValue(
                    t.getAmount() != null ? signed(t.getAmount(), direction).doubleValue() : 0);
                row.createCell(9).setCellValue(t.getNote() != null ? t.getNote() : "");
                row.createCell(10).setCellValue(
                    t.getStatus() != null ? t.getStatus().name() : "");
                row.createCell(11).setCellValue(t.getCreatedBy() != null
                    ? t.getCreatedBy().getFirstName() + " " + t.getCreatedBy().getLastName() : "");
                // Bitta guruh — nomi; CASH_AND_CARD — "CASH 300000 + CARD 200000"
                Map<String, BigDecimal> channels = channelAmounts(t);
                row.createCell(12).setCellValue(channels.size() == 1
                    ? channels.keySet().iterator().next()
                    : channels.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue().stripTrailingZeros().toPlainString())
                        .collect(Collectors.joining(" + ")));
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BadRequestException("Eksport yaratib bo'lmadi: " + e.getMessage());
        }
    }

    private static Specification<CashTransaction> buildTransactionSpec(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            Long teacherId,
            CashTransactionType type,
            PaymentMethod paymentMethod) {

        Specification<CashTransaction> spec = (root, query, cb) -> {
            Join<CashTransaction, CashRegister> registerJoin =
                root.join("cashRegister", JoinType.INNER);
            return cb.equal(registerJoin.get("id"), cashRegisterId);
        };

        if (from != null) {
            spec = spec.and((root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("transactionDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("transactionDate"), to));
        }
        if (studentId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("student").get("id"), studentId));
        }
        if (teacherId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("teacher").get("id"), teacherId));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (paymentMethod != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("paymentMethod"), paymentMethod));
        }
        return spec;
    }

    /**
     * Records income into a cash register: updates balance and persists a transaction.
     */
    @Transactional
    public CashTransaction recordIncome(
            Long cashRegisterId,
            BigDecimal amount,
            PaymentMethod method,
            Student student,
            String transactionName,
            String note,
            LocalDate transactionDate) {
        return recordIncome(cashRegisterId, amount, method, student, transactionName, note,
            transactionDate, null, null);
    }

    @Transactional
    public CashTransaction recordIncome(
            Long cashRegisterId,
            BigDecimal amount,
            PaymentMethod method,
            Student student,
            String transactionName,
            String note,
            LocalDate transactionDate,
            BigDecimal cashPart,
            BigDecimal cardPart) {
        return recordIncome(cashRegisterId, amount, method, student, transactionName, note,
            transactionDate, cashPart, cardPart, null);
    }

    /**
     * Kirim. Kassa qatori qulf ostida o'zgaradi (§7, I6) — parallel ikki kirim
     * bir-birini yo'qotmaydi. {@code paymentId} — o'quvchi to'lovi (I5).
     */
    @Transactional
    public CashTransaction recordIncome(
            Long cashRegisterId,
            BigDecimal amount,
            PaymentMethod method,
            Student student,
            String transactionName,
            String note,
            LocalDate transactionDate,
            BigDecimal cashPart,
            BigDecimal cardPart,
            Long paymentId) {

        CashRegister register = billingLocks.lockCashRegister(cashRegisterId);
        BigDecimal positiveAmount = requirePositiveAmount(amount);
        PaymentMethod cashMethod = requirePaymentMethod(method);
        SplitParts parts = validateParts(cashMethod, positiveAmount, cashPart, cardPart);

        if (cashMethod.isOnline() && !register.isAcceptOnlinePayment()) {
            throw new BadRequestException("Bu kassa onlayn to'lovlarni qabul qilmaydi");
        }

        addToBalance(register, bucketAmounts(
            cashMethod, positiveAmount, parts.cashPart(), parts.cardPart(), null));
        cashRegisterRepository.save(register);

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.INCOME);
        tx.setPaymentMethod(cashMethod);
        tx.setAmount(positiveAmount);
        tx.setCashPart(parts.cashPart());
        tx.setCardPart(parts.cardPart());
        tx.setTransactionName(transactionName);
        tx.setNote(note);
        tx.setTransactionDate(transactionDate != null ? transactionDate : LocalDate.now());
        tx.setStatus(CashTransactionStatus.COMPLETED);
        tx.setCreatedBy(currentUser());
        tx.setPaymentId(paymentId);
        if (student != null) {
            tx.setStudent(student);
        }
        return cashTransactionRepository.save(tx);
    }

    /** Teskari kassa yozuvi natijasi: manfiy chelak — ogohlantirish (§13 #22). */
    public record ReversalResult(CashTransaction transaction, boolean negativeBalance) {
    }

    /**
     * Kassa yozuvining teskarisi: {@code type = REVERSAL}, asl yozuv o'chirilmaydi, chelaklar ASL
     * taqsimot bo'yicha o'zgaradi.
     * <ul>
     *   <li>INCOME (to'lov bekor qilindi, billing-v2 §6.4) — chelaklar kamayadi, manfiyga tushishi mumkin;</li>
     *   <li>EXPENSE (oylik to'lovi bekor qilindi, payroll-v2 §5.3) — pul kassaga qaytadi.</li>
     * </ul>
     */
    @Transactional
    public ReversalResult recordReversal(CashTransaction original, String note) {
        if (original.getType() != CashTransactionType.INCOME && original.getType() != CashTransactionType.EXPENSE) {
            throw new IllegalStateException("Faqat kirim yoki chiqim teskari yoziladi: " + original.getType());
        }
        CashRegister register = billingLocks.lockCashRegister(original.getCashRegister().getId());
        BucketAmounts parts = bucketAmounts(original);
        boolean income = original.getType() == CashTransactionType.INCOME;

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.REVERSAL);
        tx.setPaymentMethod(original.getPaymentMethod());
        tx.setAmount(original.getAmount());
        tx.setCashPart(original.getCashPart());
        tx.setCardPart(original.getCardPart());
        tx.setTransactionName("Bekor qilindi: " + (original.getTransactionName() != null
            ? original.getTransactionName() : (income ? "kirim" : "chiqim")));
        tx.setNote(note);
        tx.setTransactionDate(LocalDate.now());
        tx.setStatus(CashTransactionStatus.COMPLETED);
        tx.setCreatedBy(currentUser());
        tx.setStudent(original.getStudent());
        tx.setTeacher(original.getTeacher());
        tx.setPaymentId(original.getPaymentId());
        tx.setPayrollId(original.getPayrollId());
        tx.setExamRegistrationId(original.getExamRegistrationId());
        tx.setRelatedTxId(original.getId());
        CashTransaction saved = cashTransactionRepository.save(tx);

        if (income) {
            subtractFromBalanceAllowNegative(register, parts);
        } else {
            addToBalance(register, parts);
        }
        cashRegisterRepository.save(register);
        boolean negative = register.getCashBalance().signum() < 0 || register.getPlasticBalance().signum() < 0;
        return new ReversalResult(saved, negative);
    }

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "CashRegister",
        summary = "'Kassaga kirim: ' + #dto.amount",
        entityId = "#cashRegisterId")
    public CashTransactionDto addIncome(Long cashRegisterId, IncomeCreateDto dto) {
        // Billing v2 (I2): o'quvchi pulini faqat /api/payments qabul qiladi — u
        // ledger, chek va kassani bitta tranzaksiyada yozadi. Bu yerda
        // student.balance ledgersiz o'zgarardi va keyingi sync uni yo'q qilardi.
        if (dto.getStudentId() != null) {
            throw CodedException.badRequest("cash.income.studentPaymentViaPayments");
        }

        CashTransaction tx = recordIncome(
            cashRegisterId,
            dto.getAmount(),
            dto.getPaymentMethod(),
            null,
            dto.getTransactionType(),
            dto.getNote(),
            dto.getTransactionDate(),
            dto.getCashPart(),
            dto.getCardPart());

        return toTransactionDto(tx);
    }

    /**
     * Records expense in a cash register: deducts balance (may go negative) and persists a transaction.
     */
    @Transactional
    public CashTransaction recordExpense(
            Long registerId,
            BigDecimal amount,
            PaymentMethod method,
            String transactionName,
            String note,
            LocalDate date,
            User createdBy) {
        return recordExpense(registerId, amount, method, transactionName, note, date, createdBy,
            null, null, null, null);
    }

    @Transactional
    public CashTransaction recordExpense(
            Long registerId,
            BigDecimal amount,
            PaymentMethod method,
            String transactionName,
            String note,
            LocalDate date,
            User createdBy,
            Student student,
            Teacher teacher,
            LocalDate periodMonth,
            BigDecimal totalAmount) {
        return recordExpense(registerId, amount, method, transactionName, note, date, createdBy,
            student, teacher, periodMonth, totalAmount, null, null);
    }

    @Transactional
    public CashTransaction recordExpense(
            Long registerId,
            BigDecimal amount,
            PaymentMethod method,
            String transactionName,
            String note,
            LocalDate date,
            User createdBy,
            Student student,
            Teacher teacher,
            LocalDate periodMonth,
            BigDecimal totalAmount,
            BigDecimal cashPart,
            BigDecimal cardPart) {
        return recordExpense(registerId, amount, method, transactionName, note, date, createdBy,
            student, teacher, periodMonth, totalAmount, cashPart, cardPart, null);
    }

    /** Chiqim. {@code payrollId} — oylik to'lovi (payroll-v2 §5.2): bekor qilishda shu yozuv teskari yoziladi. */
    @Transactional
    public CashTransaction recordExpense(
            Long registerId,
            BigDecimal amount,
            PaymentMethod method,
            String transactionName,
            String note,
            LocalDate date,
            User createdBy,
            Student student,
            Teacher teacher,
            LocalDate periodMonth,
            BigDecimal totalAmount,
            BigDecimal cashPart,
            BigDecimal cardPart,
            Long payrollId) {

        CashRegister register = billingLocks.lockCashRegister(registerId);
        BigDecimal positiveAmount = requirePositiveAmount(amount);
        PaymentMethod cashMethod = requirePaymentMethod(method);
        SplitParts parts = validateParts(cashMethod, positiveAmount, cashPart, cardPart);

        CashTransaction tx = new CashTransaction();
        tx.setPayrollId(payrollId);
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.EXPENSE);
        tx.setPaymentMethod(cashMethod);
        tx.setAmount(positiveAmount);
        tx.setCashPart(parts.cashPart());
        tx.setCardPart(parts.cardPart());
        tx.setTransactionName(transactionName);
        tx.setNote(note);
        tx.setTransactionDate(date != null ? date : LocalDate.now());
        tx.setStatus(CashTransactionStatus.COMPLETED);
        tx.setCreatedBy(createdBy != null ? createdBy : currentUser());
        if (student != null) {
            tx.setStudent(student);
        }
        if (teacher != null) {
            tx.setTeacher(teacher);
        }
        tx.setPeriodMonth(periodMonth);
        tx.setTotalAmount(totalAmount);
        cashTransactionRepository.save(tx);

        subtractFromBalanceAllowNegative(register, bucketAmounts(
            cashMethod, positiveAmount, parts.cashPart(), parts.cardPart(), tx.getId()));
        cashRegisterRepository.save(register);
        return tx;
    }

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "CashRegister",
        summary = "'Kassadan chiqim: ' + #dto.amount",
        entityId = "#cashRegisterId")
    public CashTransactionDto addExpense(Long cashRegisterId, ExpenseCreateDto dto) {
        Student student = null;
        if (dto.getStudentId() != null) {
            student = findStudentById(dto.getStudentId());
        }

        CashTransaction tx = recordExpense(
            cashRegisterId,
            dto.getAmount(),
            dto.getPaymentMethod(),
            null,
            dto.getNote(),
            dto.getTransactionDate(),
            currentUser(),
            student,
            null,
            dto.getPeriodMonth(),
            dto.getTotalAmount(),
            dto.getCashPart(),
            dto.getCardPart());

        return toTransactionDto(tx);
    }

    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "CashTransaction",
        summary = "'Kassa chiqimi o''chirildi'",
        entityId = "#transactionId")
    public void deleteExpense(Long transactionId) {
        CashTransaction tx = cashTransactionRepository.findById(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("CashTransaction", transactionId));

        CashRegister register = billingLocks.lockCashRegister(tx.getCashRegister().getId());

        // Chiqim o'chirilyapti — summa chelaklarga qanday yozilgan bo'lsa, shunday qaytariladi.
        addToBalance(register, bucketAmounts(tx));
        cashRegisterRepository.save(register);

        cashTransactionRepository.delete(tx);
    }

    @Transactional
    @Audited(action = AuditAction.PAYMENT, entity = "CashRegister",
        summary = "'Kassalar o''rtasida o''tkazma: ' + #dto.amount")
    public List<CashTransactionDto> transfer(TransferDto dto) {
        if (dto.getFromCashRegisterId() == null || dto.getToCashRegisterId() == null) {
            throw new BadRequestException("Manba va maqsad kassalari ko'rsatilishi shart");
        }
        if (dto.getFromCashRegisterId().equals(dto.getToCashRegisterId())) {
            throw new BadRequestException("Kassalar bir xil bo'lishi mumkin emas");
        }

        // Qulf tartibi (§7.2): kichik id avval — teskari yo'nalishdagi o'tkazma bilan deadlock yo'q
        Long firstId = Math.min(dto.getFromCashRegisterId(), dto.getToCashRegisterId());
        Long secondId = Math.max(dto.getFromCashRegisterId(), dto.getToCashRegisterId());
        CashRegister first = billingLocks.lockCashRegister(firstId);
        CashRegister second = billingLocks.lockCashRegister(secondId);
        CashRegister from = first.getId().equals(dto.getFromCashRegisterId()) ? first : second;
        CashRegister to = from == first ? second : first;
        BigDecimal amount = requirePositiveAmount(dto.getAmount());
        PaymentMethod method = requirePaymentMethod(dto.getPaymentMethod());
        SplitParts parts = validateParts(method, amount, dto.getCashPart(), dto.getCardPart());
        BucketAmounts buckets =
            bucketAmounts(method, amount, parts.cashPart(), parts.cardPart(), null);

        subtractFromBalance(from, buckets);
        addToBalance(to, buckets);
        cashRegisterRepository.save(from);
        cashRegisterRepository.save(to);

        User creator = currentUser();
        LocalDate txDate = LocalDate.now();

        CashTransaction outTx = new CashTransaction();
        outTx.setCashRegister(from);
        outTx.setTargetCashRegister(to);
        outTx.setType(CashTransactionType.TRANSFER);
        outTx.setPaymentMethod(method);
        outTx.setAmount(amount);
        outTx.setCashPart(parts.cashPart());
        outTx.setCardPart(parts.cardPart());
        outTx.setTransactionName("Ko'chirish (chiqim)");
        outTx.setNote(dto.getNote());
        outTx.setTransactionDate(txDate);
        outTx.setCreatedBy(creator);

        CashTransaction inTx = new CashTransaction();
        inTx.setCashRegister(to);
        inTx.setTargetCashRegister(from);
        inTx.setType(CashTransactionType.TRANSFER);
        inTx.setPaymentMethod(method);
        inTx.setAmount(amount);
        inTx.setCashPart(parts.cashPart());
        inTx.setCardPart(parts.cardPart());
        inTx.setTransactionName("Ko'chirish (kirim)");
        inTx.setNote(dto.getNote());
        inTx.setTransactionDate(txDate);
        inTx.setCreatedBy(creator);

        CashTransaction savedOut = cashTransactionRepository.save(outTx);
        // Kirim qatori chiqim qatoriga bog'lanadi — yo'nalish (IN) nomga emas, bog'lanishga tayanadi
        inTx.setRelatedTxId(savedOut.getId());
        return List.of(
            toTransactionDto(savedOut),
            toTransactionDto(cashTransactionRepository.save(inTx))
        );
    }

    // ------------------------------------------------------------------
    // Chelaklar (bucket): taqsimot faqat PaymentMethod.getCashBucket() dan olinadi
    // ------------------------------------------------------------------

    /** Bitta tranzaksiyaning naqd/naqdsiz balanslar bo'yicha taqsimoti. */
    private record BucketAmounts(BigDecimal cash, BigDecimal nonCash) {}

    /** CASH_AND_CARD uchun tekshirilgan qismlar; boshqa usullarda ikkalasi ham null. */
    private record SplitParts(BigDecimal cashPart, BigDecimal cardPart) {
        private static SplitParts none() {
            return new SplitParts(null, null);
        }
    }

    /**
     * CASH_AND_CARD uchun summa taqsimotini tekshiradi.
     * Boshqa usullar uchun yuborilgan qismlar e'tiborsiz qoldiriladi (null saqlanadi).
     */
    private static SplitParts validateParts(PaymentMethod method, BigDecimal amount,
                                            BigDecimal cashPart, BigDecimal cardPart) {
        if (method.getCashBucket() != PaymentMethod.CashBucket.SPLIT) {
            return SplitParts.none();
        }
        if (cashPart == null || cardPart == null) {
            throw new IllegalArgumentException(
                "CASH_AND_CARD uchun naqd va karta summalari ko'rsatilishi shart");
        }
        if (cashPart.signum() < 0 || cardPart.signum() < 0) {
            throw new IllegalArgumentException(
                "Naqd va karta summalari manfiy bo'lishi mumkin emas");
        }
        if (cashPart.add(cardPart).compareTo(amount) != 0) {
            throw new IllegalArgumentException(
                "Naqd va karta summalari yig'indisi kassaga tushadigan summaga teng "
                    + "bo'lishi kerak: " + formatAmount(cashPart) + " + " + formatAmount(cardPart)
                    + " ≠ " + formatAmount(amount));
        }
        return new SplitParts(cashPart, cardPart);
    }

    /**
     * Summani chelaklarga taqsimlaydi. Eski yozuvlarda CASH_AND_CARD bo'lib
     * taqsimot saqlanmagan bo'lishi mumkin — bunday holda butun summa naqdga yoziladi.
     */
    private static BucketAmounts bucketAmounts(PaymentMethod method, BigDecimal amount,
                                               BigDecimal cashPart, BigDecimal cardPart,
                                               Long txId) {
        if (method == null) {
            return new BucketAmounts(amount, BigDecimal.ZERO);
        }
        return switch (method.getCashBucket()) {
            case CASH -> new BucketAmounts(amount, BigDecimal.ZERO);
            case NON_CASH -> new BucketAmounts(BigDecimal.ZERO, amount);
            case SPLIT -> {
                if (cashPart == null || cardPart == null) {
                    log.warn("CASH_AND_CARD tranzaksiyasida summa taqsimoti yo'q (id={}), "
                        + "butun summa naqd chelakka yozildi", txId);
                    yield new BucketAmounts(amount, BigDecimal.ZERO);
                }
                yield new BucketAmounts(cashPart, cardPart);
            }
        };
    }

    /** Saqlangan tranzaksiya bo'yicha taqsimot (o'chirish/qaytarish uchun). */
    private static BucketAmounts bucketAmounts(CashTransaction tx) {
        return bucketAmounts(tx.getPaymentMethod(), tx.getAmount(),
            tx.getCashPart(), tx.getCardPart(), tx.getId());
    }

    private void addToBalance(CashRegister register, BucketAmounts parts) {
        register.setCashBalance(register.getCashBalance().add(parts.cash()));
        register.setPlasticBalance(register.getPlasticBalance().add(parts.nonCash()));
        recomputeBalance(register);
    }

    private void subtractFromBalance(CashRegister register, BucketAmounts parts) {
        if (parts.cash().signum() > 0
                && register.getCashBalance().compareTo(parts.cash()) < 0) {
            throw new BadRequestException("Naqd balans yetarli emas");
        }
        if (parts.nonCash().signum() > 0
                && register.getPlasticBalance().compareTo(parts.nonCash()) < 0) {
            throw new BadRequestException("Plastik balans yetarli emas");
        }
        register.setCashBalance(register.getCashBalance().subtract(parts.cash()));
        register.setPlasticBalance(register.getPlasticBalance().subtract(parts.nonCash()));
        recomputeBalance(register);
    }

    private void subtractFromBalanceAllowNegative(CashRegister register, BucketAmounts parts) {
        register.setCashBalance(register.getCashBalance().subtract(parts.cash()));
        register.setPlasticBalance(register.getPlasticBalance().subtract(parts.nonCash()));
        recomputeBalance(register);
    }

    /** 2000000 -> "2 000 000" */
    private static String formatAmount(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        return new DecimalFormat("#,##0.##", symbols).format(value);
    }

    private static void recomputeBalance(CashRegister register) {
        register.setBalance(register.getPlasticBalance().add(register.getCashBalance()));
    }

    private CashRegister findRegisterById(Long id) {
        return cashRegisterRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("CashRegister", id));
    }

    private User findUserById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private Student findStudentById(Long id) {
        return studentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Student", id));
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Summa musbat bo'lishi kerak");
        }
        return amount;
    }

    private static PaymentMethod requirePaymentMethod(PaymentMethod method) {
        if (method == null) {
            throw new BadRequestException("To'lov usuli ko'rsatilishi shart");
        }
        return requireAcceptedForNew(method);
    }

    /** Yangi to'lov / xarajat / kassa yozuvi: BANK qabul qilinmaydi — 400 (TERMINAL tanlanadi). Null o'tadi. */
    public static PaymentMethod requireAcceptedForNew(PaymentMethod method) {
        if (method != null && !method.isAcceptedForNew()) {
            throw CodedException.badRequest("payment.method.bankNotAccepted");
        }
        return method;
    }

    private static CashRegisterStatus parseRegisterStatus(String status) {
        try {
            return CashRegisterStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noto'g'ri kassa holati: " + status);
        }
    }

    private static CashTransactionType parseTransactionType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return CashTransactionType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Filtr uchun: eski nomlar (PLASTIC, ONLINE) ham tushuniladi. */
    private static PaymentMethod parsePaymentMethod(String method) {
        return PaymentMethod.parseOrNull(method);
    }

    /** Berilgan, lekin tanilmagan guruh — 400 (jim e'tiborsiz qoldirilsa butun ro'yxat qaytardi). */
    private static PaymentChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        PaymentChannel parsed = PaymentChannel.parseOrNull(channel);
        if (parsed == null) {
            throw new BadRequestException("Noto'g'ri to'lov usuli guruhi: " + channel
                + " (CASH, CARD, TERMINAL, ONLINE, OTHER; bank to'lovlari — TERMINAL)");
        }
        return parsed;
    }

    /**
     * Guruh filtri {@link PaymentChannel#split} bilan bir xil: CASH_AND_CARD qatori CASH da (naqd
     * qismi bor yoki qismsiz eski yozuv) va CARD da (karta qismi bor) ko'rinadi; usulsiz — CASH.
     */
    private static Specification<CashTransaction> channelSpec(PaymentChannel channel) {
        if (channel == null) {
            return null;
        }
        return (root, query, cb) -> {
            var method = root.<PaymentMethod>get("paymentMethod");
            var cashPart = root.<BigDecimal>get("cashPart");
            var cardPart = root.<BigDecimal>get("cardPart");
            var split = cb.equal(method, PaymentMethod.CASH_AND_CARD);
            var partsKnown = cb.and(cb.isNotNull(cashPart), cb.isNotNull(cardPart));
            return switch (channel) {
                case CASH -> cb.or(
                    cb.isNull(method),
                    cb.equal(method, PaymentMethod.CASH),
                    cb.and(split, cb.or(cb.not(partsKnown), cb.greaterThan(cashPart, BigDecimal.ZERO))));
                case CARD -> cb.or(
                    cb.equal(method, PaymentMethod.CARD),
                    cb.and(split, partsKnown, cb.greaterThan(cardPart, BigDecimal.ZERO)));
                default -> method.in(channel.methods());
            };
        };
    }

    /** Yozuv summasi guruhlar bo'yicha (ro'yxat va eksport uchun). */
    private static Map<String, BigDecimal> channelAmounts(CashTransaction t) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        PaymentChannel.split(t.getPaymentMethod(), t.getAmount(), t.getCashPart(), t.getCardPart())
            .forEach((c, v) -> out.put(c.name(), v));
        return out;
    }

    private CashRegisterDto toRegisterDto(CashRegister r) {
        CashRegisterDto dto = new CashRegisterDto();
        dto.setId(r.getId());
        dto.setUuid(r.getUuid());
        dto.setName(r.getName());
        if (r.getModerator() != null) {
            dto.setModeratorId(r.getModerator().getId());
            dto.setModeratorName(r.getModerator().getFirstName() + " "
                + r.getModerator().getLastName());
        }
        dto.setBalance(r.getBalance());
        dto.setPlasticBalance(r.getPlasticBalance());
        dto.setCashBalance(r.getCashBalance());
        dto.setStatus(r.getStatus());
        dto.setAcceptOnlinePayment(r.isAcceptOnlinePayment());
        dto.setArchived(r.isArchived());
        return dto;
    }

    /**
     * Pul yo'nalishi ({@link CashDirection}): INCOME → IN, EXPENSE → OUT; TRANSFER — kirim qatori IN
     * (yangi qatorlarda {@code relatedTxId} = chiqim qatori, eskilarida nom "(kirim)"), chiqim qatori OUT;
     * REVERSAL — asl yozuvga ({@code relatedTxId}) teskari; asl topilmasa: to'lov va imtihon to'lovi teskarisi OUT,
     * boshqasi (oylik/chiqim teskarisi) IN.
     */
    CashDirection direction(CashTransaction t) {
        if (t.getType() == null) {
            return CashDirection.OUT;
        }
        return switch (t.getType()) {
            case INCOME -> CashDirection.IN;
            case EXPENSE -> CashDirection.OUT;
            case TRANSFER -> t.getRelatedTxId() != null
                || (t.getTransactionName() != null && t.getTransactionName().contains("(kirim)"))
                ? CashDirection.IN : CashDirection.OUT;
            case REVERSAL -> {
                CashTransaction original = t.getRelatedTxId() != null
                    ? cashTransactionRepository.findById(t.getRelatedTxId()).orElse(null) : null;
                if (original != null && original.getType() != CashTransactionType.REVERSAL) {
                    yield direction(original).opposite();
                }
                yield t.getPaymentId() != null || t.getExamRegistrationId() != null ? CashDirection.OUT : CashDirection.IN;
            }
        };
    }

    private static BigDecimal signed(BigDecimal amount, CashDirection direction) {
        if (amount == null) {
            return null;
        }
        return direction == CashDirection.IN ? amount : amount.negate();
    }

    private CashTransactionDto toTransactionDto(CashTransaction t) {
        CashTransactionDto dto = new CashTransactionDto();
        dto.setId(t.getId());
        dto.setUuid(t.getUuid());
        dto.setCashRegisterId(t.getCashRegister() != null ? t.getCashRegister().getId() : null);
        dto.setType(t.getType());
        dto.setPaymentMethod(t.getPaymentMethod());
        if (t.getStudent() != null) {
            dto.setStudentId(t.getStudent().getId());
            dto.setStudentName(t.getStudent().getFirstName() + " "
                + t.getStudent().getLastName());
        }
        if (t.getTeacher() != null) {
            dto.setTeacherId(t.getTeacher().getId());
            dto.setTeacherName(t.getTeacher().getFirstName() + " "
                + t.getTeacher().getLastName());
        }
        dto.setTransactionName(t.getTransactionName());
        dto.setAmount(t.getAmount());
        CashDirection direction = direction(t);
        dto.setDirection(direction);
        dto.setSignedAmount(signed(t.getAmount(), direction));
        dto.setPaymentId(t.getPaymentId());
        dto.setPayrollId(t.getPayrollId());
        dto.setExamRegistrationId(t.getExamRegistrationId());
        dto.setRelatedTxId(t.getRelatedTxId());
        dto.setCashPart(t.getCashPart());
        dto.setCardPart(t.getCardPart());
        dto.setChannelAmounts(channelAmounts(t));
        dto.setNote(t.getNote());
        dto.setStatus(t.getStatus());
        dto.setPeriodMonth(t.getPeriodMonth());
        dto.setTotalAmount(t.getTotalAmount());
        dto.setTransactionDate(t.getTransactionDate());
        dto.setCreatedAt(t.getCreatedAt());
        if (t.getCreatedBy() != null) {
            dto.setCreatedByName(t.getCreatedBy().getFirstName() + " "
                + t.getCreatedBy().getLastName());
        }
        return dto;
    }
}
