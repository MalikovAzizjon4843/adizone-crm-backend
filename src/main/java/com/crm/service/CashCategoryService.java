package com.crm.service;

import com.crm.dto.request.CashCategoryRequest;
import com.crm.dto.response.CashCategoryDto;
import com.crm.entity.CashCategory;
import com.crm.entity.CashRegister;
import com.crm.entity.enums.CashCategoryScope;
import com.crm.entity.enums.CashDirection;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.repository.CashCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Kassa kategoriyalari (docs/design/cash-and-books.md §1.2.2): ro'yxat, SA tomonidan sozlash va qo'lda
 * kiritiladigan yozuvlar uchun guruh qoidasi — DAILY: admin o'z kassasidan; STRATEGIC: faqat SA, Direktor
 * kassasidan (yoki kassasiz); SYSTEM: qo'lda tanlanmaydi.
 */
@Service
@RequiredArgsConstructor
public class CashCategoryService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final CashCategoryRepository repository;
    private final CashAccessService access;

    /** SA — hammasi; boshqalar — faqat faol DAILY (qo'lda tanlay oladiganlari). */
    @Transactional(readOnly = true)
    public List<CashCategoryDto> list(String direction) {
        CashDirection dir = parseDirection(direction);
        boolean director = access.isUnrestricted();
        return repository.findAllByOrderByDirectionAscSortOrderAscCodeAsc().stream()
            .filter(c -> dir == null || c.getDirection() == dir)
            .filter(c -> director || (c.isActive() && c.getScope() == CashCategoryScope.DAILY))
            .map(CashCategoryService::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, CashCategory> all() {
        return repository.findAll().stream()
            .collect(Collectors.toMap(CashCategory::getCode, Function.identity()));
    }

    @Transactional
    public CashCategoryDto create(CashCategoryRequest r) {
        access.requireDirector();
        String code = r.getCode() != null ? r.getCode().trim().toUpperCase(Locale.ROOT) : "";
        if (!CODE.matcher(code).matches()) {
            throw CodedException.badRequest("cash.category.codeInvalid");
        }
        if (repository.existsById(code)) {
            throw new ConflictException("cash.category.exists", code);
        }
        CashDirection dir = parseDirection(r.getDirection());
        if (dir == null) {
            throw CodedException.badRequest("cash.category.directionRequired");
        }
        CashCategoryScope scope = parseScope(r.getScope());
        if (scope == null || scope == CashCategoryScope.SYSTEM) {
            throw CodedException.badRequest("cash.category.scopeInvalid");
        }
        CashCategory c = CashCategory.builder()
            .code(code)
            .direction(dir)
            .scope(scope)
            .name(requireName(r.getName()))
            .active(r.getActive() == null || r.getActive())
            .sortOrder(r.getSortOrder() != null ? r.getSortOrder() : 500)
            .build();
        return toDto(repository.save(c));
    }

    /** Nomi, tartibi va faolligi o'zgaradi; kod, yo'nalish va guruh — yo'q (tarixiy hisobotlar buzilmasin). */
    @Transactional
    public CashCategoryDto update(String code, CashCategoryRequest r) {
        access.requireDirector();
        CashCategory c = repository.findById(code)
            .orElseThrow(() -> CodedException.notFound("cash.category.notFound", code));
        if (r.getName() != null) {
            c.setName(requireName(r.getName()));
        }
        if (r.getSortOrder() != null) {
            c.setSortOrder(r.getSortOrder());
        }
        if (r.getActive() != null) {
            if (c.getScope() == CashCategoryScope.SYSTEM && !r.getActive()) {
                throw CodedException.badRequest("cash.category.system");
            }
            c.setActive(r.getActive());
        }
        return toDto(repository.save(c));
    }

    /** Ichki oqim uchun: kod mavjud va yo'nalishi to'g'ri. */
    public CashCategory require(String code, CashDirection direction) {
        CashCategory c = code != null ? repository.findById(code).orElse(null) : null;
        if (c == null || c.getDirection() != direction) {
            throw CodedException.badRequest("cash.category.invalid", code);
        }
        return c;
    }

    /**
     * Qo'lda kiritiladigan kirim/chiqim kategoriyasi qoidasi. {@code register} — yozuv tushadigan kassa
     * (null — kassasiz xarajat). Kassaning o'ziga kirish — {@link CashAccessService} da (yozuv paytida).
     */
    public CashCategory checkManual(String code, CashDirection direction, CashRegister register) {
        CashCategory c = require(code, direction);
        if (!c.isActive()) {
            throw CodedException.badRequest("cash.category.inactive", code);
        }
        if (c.getScope() == CashCategoryScope.SYSTEM) {
            throw CodedException.badRequest("cash.category.system", code);
        }
        if (c.getScope() == CashCategoryScope.STRATEGIC) {
            if (!access.isUnrestricted()) {
                throw CodedException.forbidden("cash.category.directorOnly", code);
            }
            if (register != null) {
                access.requireDirectorRegister(register);
            }
        } else if (register == null && !access.isUnrestricted()) {
            throw CodedException.badRequest("expense.cashRegister.required");
        }
        return c;
    }

    public static String normalize(String code) {
        return code != null && !code.isBlank() ? code.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String requireName(String name) {
        String n = name != null ? name.trim() : "";
        if (n.isEmpty() || n.length() > 100) {
            throw CodedException.badRequest("cash.category.nameRequired");
        }
        return n;
    }

    private static CashDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CashDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("cash.category.directionRequired");
        }
    }

    private static CashCategoryScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CashCategoryScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static CashCategoryDto toDto(CashCategory c) {
        return CashCategoryDto.builder()
            .code(c.getCode())
            .direction(c.getDirection())
            .scope(c.getScope())
            .name(c.getName())
            .active(c.isActive())
            .sortOrder(c.getSortOrder())
            .build();
    }
}
