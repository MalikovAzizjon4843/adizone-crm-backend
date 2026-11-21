package com.crm.calls;

import com.crm.entity.CallExclusion;
import com.crm.exception.CodedException;
import com.crm.repository.CallExclusionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shaxsiy raqamlar (docs/design/calls.md §5): bu raqamlar bilan qo'ng'iroqlar sinxron qilinmaydi. {@code PUT} — butun
 * ro'yxatni almashtiradi. Retroaktiv emas: avval sinxron qilingan qo'ng'iroqlar o'chirilmaydi.
 */
@Service
@RequiredArgsConstructor
public class CallExclusionService {

    static final int MAX_ITEMS = 500;

    private final CallExclusionRepository repository;
    private final Clock billingClock;

    @Transactional(readOnly = true)
    public List<CallDtos.ExclusionItem> list(CallDevicePrincipal principal) {
        return repository.findByUserIdOrderByIdAsc(principal.userId()).stream()
            .map(e -> new CallDtos.ExclusionItem(e.getPhoneNormalized(), e.getLabel()))
            .toList();
    }

    @Transactional
    public List<CallDtos.ExclusionItem> replace(CallDevicePrincipal principal, CallDtos.ExclusionsRequest request) {
        List<CallDtos.ExclusionItem> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.size() > MAX_ITEMS) {
            throw CodedException.badRequest("calls.exclusions.tooMany", MAX_ITEMS);
        }
        Map<String, String> wanted = new LinkedHashMap<>();
        for (CallDtos.ExclusionItem item : items) {
            String phone = item == null ? null : CallContactMatcher.normalize(item.phone());
            if (phone == null) {
                throw CodedException.badRequest("calls.exclusions.phoneInvalid", item == null ? "" : item.phone());
            }
            wanted.putIfAbsent(phone, CallDeviceService.cut(CallDeviceService.trimToNull(item.label()), 100));
        }
        repository.deleteAll(repository.findByUserIdOrderByIdAsc(principal.userId()));
        repository.flush();
        LocalDateTime now = LocalDateTime.now(billingClock);
        wanted.forEach((phone, label) -> repository.save(CallExclusion.builder()
            .userId(principal.userId())
            .phoneNormalized(phone)
            .label(label)
            .createdAt(now)
            .build()));
        return list(principal);
    }
}
