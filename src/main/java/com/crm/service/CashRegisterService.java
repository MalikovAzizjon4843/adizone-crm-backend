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
import lombok.RequiredArgsConstructor;
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
        register.setArchived(Boolean.TRUE.equals(dto.getArchived()));
        register.setStatus(register.isArchived()
            ? CashRegisterStatus.ARCHIVED : CashRegisterStatus.ACTIVE);
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
    public void delete(Long id) {
        CashRegister register = findRegisterById(id);
        register.setArchived(true);
        register.setStatus(CashRegisterStatus.ARCHIVED);
        cashRegisterRepository.save(register);
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

        final Long registerId = cashRegisterId;
        final LocalDate fromDate = from;
        final LocalDate toDate = to;
        final Long studentFilter = studentId;
        final CashTransactionType txType = typeFilter;
        final CashPaymentMethod txMethod = methodFilter;

        Specification<CashTransaction> spec = Specification.where(
            (root, q, cb) -> cb.equal(root.get("cashRegister").get("id"), registerId));

        if (fromDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("transactionDate"), fromDate));
        }
        if (toDate != null) {
            spec = spec.and((root, q, cb) ->
                cb.lessThanOrEqualTo(root.get("transactionDate"), toDate));
        }
        if (studentFilter != null) {
            spec = spec.and((root, q, cb) ->
                cb.equal(root.get("student").get("id"), studentFilter));
        }
        if (txType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), txType));
        }
        if (txMethod != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("paymentMethod"), txMethod));
        }

        return cashTransactionRepository.findAll(spec, pageable).map(this::toTransactionDto);
    }

    @Transactional
    public CashTransactionDto addIncome(Long cashRegisterId, IncomeCreateDto dto) {
        CashRegister register = findRegisterById(cashRegisterId);
        BigDecimal amount = requirePositiveAmount(dto.getAmount());
        CashPaymentMethod method = requirePaymentMethod(dto.getPaymentMethod());

        if (method == CashPaymentMethod.ONLINE && !register.isAcceptOnlinePayment()) {
            throw new BadRequestException("Bu kassa onlayn to'lovlarni qabul qilmaydi");
        }

        addToBalance(register, method, amount);
        cashRegisterRepository.save(register);

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.INCOME);
        tx.setPaymentMethod(method);
        tx.setAmount(amount);
        tx.setTransactionName(dto.getTransactionType());
        tx.setNote(dto.getNote());
        tx.setTransactionDate(dto.getTransactionDate() != null
            ? dto.getTransactionDate() : LocalDate.now());
        tx.setCreatedBy(currentUser());

        if (dto.getStudentId() != null) {
            Student student = findStudentById(dto.getStudentId());
            tx.setStudent(student);
        }

        return toTransactionDto(cashTransactionRepository.save(tx));
    }

    @Transactional
    public CashTransactionDto addExpense(Long cashRegisterId, ExpenseCreateDto dto) {
        CashRegister register = findRegisterById(cashRegisterId);
        BigDecimal amount = requirePositiveAmount(dto.getAmount());
        CashPaymentMethod method = requirePaymentMethod(dto.getPaymentMethod());

        subtractFromBalance(register, method, amount);
        cashRegisterRepository.save(register);

        CashTransaction tx = new CashTransaction();
        tx.setCashRegister(register);
        tx.setType(CashTransactionType.EXPENSE);
        tx.setPaymentMethod(method);
        tx.setAmount(amount);
        tx.setPeriodMonth(dto.getPeriodMonth());
        tx.setTotalAmount(dto.getTotalAmount());
        tx.setNote(dto.getNote());
        tx.setTransactionDate(dto.getTransactionDate() != null
            ? dto.getTransactionDate() : LocalDate.now());
        tx.setCreatedBy(currentUser());

        if (dto.getStudentId() != null) {
            tx.setStudent(findStudentById(dto.getStudentId()));
        }

        return toTransactionDto(cashTransactionRepository.save(tx));
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
