package com.crm.service;

import com.crm.dto.request.CashRegisterCreateDto;
import com.crm.dto.request.ExpenseCreateDto;
import com.crm.dto.request.IncomeCreateDto;
import com.crm.dto.request.TransferDto;
import com.crm.dto.response.CashRegisterDto;
import com.crm.dto.response.CashTransactionDto;
import com.crm.entity.CashRegister;
import com.crm.entity.CashTransaction;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.CashPaymentMethod;
import com.crm.entity.enums.CashRegisterStatus;
import com.crm.entity.enums.CashTransactionStatus;
import com.crm.entity.enums.CashTransactionType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.CashTransactionRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CashRegisterService {

    private final CashRegisterRepository cashRegisterRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;

    @Transactional(readOnly = true)
    public List<CashRegisterDto> getAll(String status) {
        List<CashRegister> registers;
        if (status != null && !status.isBlank()) {
            CashRegisterStatus registerStatus = parseRegisterStatus(status);
            registers = cashRegisterRepository.findByStatus(registerStatus);
        } else {
            registers = cashRegisterRepository.findAll();
        }
        return registers.stream().map(this::toRegisterDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CashRegisterDto getById(Long id) {
        return toRegisterDto(findRegisterById(id));
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

    @Transactional(readOnly = true)
    public Page<CashTransactionDto> getTransactions(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            String type,
            String paymentMethod,
            Pageable pageable) {

        findRegisterById(cashRegisterId);

        CashTransactionType typeFilter = parseTransactionType(type);
        CashPaymentMethod methodFilter = parsePaymentMethod(paymentMethod);

        log.debug(
            "getTransactions registerId={}, from={}, to={}, studentId={}, type={}, paymentMethod={}, page={}, size={}",
            cashRegisterId, from, to, studentId, typeFilter, methodFilter,
            pageable.getPageNumber(), pageable.getPageSize());

        Specification<CashTransaction> spec = buildTransactionSpec(
            cashRegisterId, from, to, studentId, typeFilter, methodFilter);

        Page<CashTransaction> page = cashTransactionRepository.findAll(spec, pageable);

        log.debug("getTransactions registerId={} matched {} of {} total",
            cashRegisterId, page.getNumberOfElements(), page.getTotalElements());

        return page.map(this::toTransactionDto);
    }

    private static Specification<CashTransaction> buildTransactionSpec(
            Long cashRegisterId,
            LocalDate from,
            LocalDate to,
            Long studentId,
            CashTransactionType type,
            CashPaymentMethod paymentMethod) {

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
            CashPaymentMethod method,
            Student student,
            String transactionName,
            String note,
            LocalDate transactionDate) {

        CashRegister register = findRegisterById(cashRegisterId);
        BigDecimal positiveAmount = requirePositiveAmount(amount);
        CashPaymentMethod cashMethod = requirePaymentMethod(method);

        if (cashMethod == CashPaymentMethod.ONLINE && !register.isAcceptOnlinePayment()) {
            throw new BadRequestException("Bu kassa onlayn to'lovlarni qabul qilmaydi");
        }

        addToBalance(register, cashMethod, positiveAmount);
        cashRegisterRepository.save(register);

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.INCOME);
        tx.setPaymentMethod(cashMethod);
        tx.setAmount(positiveAmount);
        tx.setTransactionName(transactionName);
        tx.setNote(note);
        tx.setTransactionDate(transactionDate != null ? transactionDate : LocalDate.now());
        tx.setStatus(CashTransactionStatus.COMPLETED);
        tx.setCreatedBy(currentUser());
        if (student != null) {
            tx.setStudent(student);
        }
        return cashTransactionRepository.save(tx);
    }

    @Transactional
    public CashTransactionDto addIncome(Long cashRegisterId, IncomeCreateDto dto) {
        Student student = null;
        if (dto.getStudentId() != null) {
            student = findStudentById(dto.getStudentId());
        }

        CashTransaction tx = recordIncome(
            cashRegisterId,
            dto.getAmount(),
            dto.getPaymentMethod(),
            student,
            dto.getTransactionType(),
            dto.getNote(),
            dto.getTransactionDate());

        return toTransactionDto(tx);
    }

    /**
     * Records expense in a cash register: deducts balance (may go negative) and persists a transaction.
     */
    @Transactional
    public CashTransaction recordExpense(
            Long registerId,
            BigDecimal amount,
            CashPaymentMethod method,
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
            CashPaymentMethod method,
            String transactionName,
            String note,
            LocalDate date,
            User createdBy,
            Student student,
            Teacher teacher,
            LocalDate periodMonth,
            BigDecimal totalAmount) {

        CashRegister register = findRegisterById(registerId);
        BigDecimal positiveAmount = requirePositiveAmount(amount);
        CashPaymentMethod cashMethod = requirePaymentMethod(method);

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.EXPENSE);
        tx.setPaymentMethod(cashMethod);
        tx.setAmount(positiveAmount);
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

        subtractFromBalanceAllowNegative(register, cashMethod, positiveAmount);
        cashRegisterRepository.save(register);
        return tx;
    }

    @Transactional
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
            dto.getTotalAmount());

        return toTransactionDto(tx);
    }

    @Transactional
    public List<CashTransactionDto> transfer(TransferDto dto) {
        if (dto.getFromCashRegisterId() == null || dto.getToCashRegisterId() == null) {
            throw new BadRequestException("Manba va maqsad kassalari ko'rsatilishi shart");
        }
        if (dto.getFromCashRegisterId().equals(dto.getToCashRegisterId())) {
            throw new BadRequestException("Kassalar bir xil bo'lishi mumkin emas");
        }

        CashRegister from = findRegisterById(dto.getFromCashRegisterId());
        CashRegister to = findRegisterById(dto.getToCashRegisterId());
        BigDecimal amount = requirePositiveAmount(dto.getAmount());
        CashPaymentMethod method = requirePaymentMethod(dto.getPaymentMethod());

        subtractFromBalance(from, method, amount);
        addToBalance(to, method, amount);
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
        inTx.setTransactionName("Ko'chirish (kirim)");
        inTx.setNote(dto.getNote());
        inTx.setTransactionDate(txDate);
        inTx.setCreatedBy(creator);

        return List.of(
            toTransactionDto(cashTransactionRepository.save(outTx)),
            toTransactionDto(cashTransactionRepository.save(inTx))
        );
    }

    private void addToBalance(CashRegister register, CashPaymentMethod method, BigDecimal amount) {
        if (method == CashPaymentMethod.PLASTIC) {
            register.setPlasticBalance(register.getPlasticBalance().add(amount));
        } else {
            register.setCashBalance(register.getCashBalance().add(amount));
        }
        recomputeBalance(register);
    }

    private void subtractFromBalance(CashRegister register, CashPaymentMethod method, BigDecimal amount) {
        if (method == CashPaymentMethod.PLASTIC) {
            if (register.getPlasticBalance().compareTo(amount) < 0) {
                throw new BadRequestException("Plastik balans yetarli emas");
            }
            register.setPlasticBalance(register.getPlasticBalance().subtract(amount));
        } else {
            if (register.getCashBalance().compareTo(amount) < 0) {
                throw new BadRequestException("Naqd balans yetarli emas");
            }
            register.setCashBalance(register.getCashBalance().subtract(amount));
        }
        recomputeBalance(register);
    }

    private void subtractFromBalanceAllowNegative(
            CashRegister register, CashPaymentMethod method, BigDecimal amount) {
        if (method == CashPaymentMethod.PLASTIC) {
            register.setPlasticBalance(register.getPlasticBalance().subtract(amount));
        } else {
            register.setCashBalance(register.getCashBalance().subtract(amount));
        }
        recomputeBalance(register);
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
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Summa musbat bo'lishi kerak");
        }
        return amount;
    }

    private static CashPaymentMethod requirePaymentMethod(CashPaymentMethod method) {
        if (method == null) {
            throw new BadRequestException("To'lov usuli ko'rsatilishi shart");
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

    private static CashPaymentMethod parsePaymentMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        try {
            return CashPaymentMethod.valueOf(method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
