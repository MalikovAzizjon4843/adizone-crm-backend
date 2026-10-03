package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.StudentStatus;
import com.crm.billing.BillingStatusService;
import com.crm.exception.CodedException;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.service.CenterSettingsService;
import com.crm.telegram.TelegramInitDataValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mini App kirishi va bog'lanishlar (docs/design/telegram-platform.md §3.1–§3.4, miniapp-api.md §1).
 *
 * <p>Oqim: Mini App {@code initData} bilan {@code POST /api/app/auth} → HMAC + {@code auth_date} →
 * Telegram hisobi bog'langan bo'lsa telefon bo'yicha qayta tekshiruv ({@link MiniAppLinkService#resync})
 * → app JWT + profil. Bog'lanmagan → 403 {@code app.notLinked}: Mini App "Raqamni ulashish"
 * ({@code requestContact}) ni ko'rsatadi, kontakt botga keladi, so'ng auth qayta chaqiriladi.
 *
 * <p>Har {@code studentId} — {@link #requireStudent} (IDOR): identity'ning bog'langan, ARCHIVED
 * bo'lmagan o'quvchisi, aks holda 403 {@code app.student.forbidden}.
 */
@Service
@RequiredArgsConstructor
public class MiniAppAuthService {

    private final TelegramInitDataValidator initDataValidator;
    private final AppJwtService jwtService;
    private final MiniAppLinkService linkService;
    private final AppIdentityRepository identityRepository;
    private final AppIdentityStudentRepository identityStudentRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final CenterSettingsService centerSettingsService;

    /** {@code noRollbackFor}: qayta tekshiruvda o'quvchi qolmasa uzish saqlanadi, keyin 403 qaytadi. */
    @Transactional(noRollbackFor = CodedException.class)
    public AppDtos.AuthResponse authenticate(String initData) {
        if (!jwtService.isConfigured()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "app.auth.notConfigured");
        }
        TelegramInitDataValidator.WebAppUser user = initDataValidator.validate(initData);
        AppIdentity identity = identityRepository.findByTelegramUserId(user.id())
            .filter(AppIdentity::isActive)
            .orElseThrow(this::notLinked);
        List<AppIdentityStudent> links = linkService.resync(identity);
        if (links.isEmpty()) {
            throw notLinked();
        }
        if (user.firstName() != null) {
            identity.setFirstName(user.firstName());
        }
        if (user.username() != null) {
            identity.setTelegramUsername(user.username());
        }
        String token = jwtService.issue(identity);
        return new AppDtos.AuthResponse(token, "Bearer", jwtService.ttlSeconds(), profile(identity, links));
    }

    @Transactional(readOnly = true)
    public AppDtos.Profile me(AppPrincipal principal) {
        AppIdentity identity = identity(principal);
        return profile(identity, links(identity.getId()));
    }

    /** Mini App profilidan uzish — token darhol yaroqsiz, qayta ulash botda. */
    @Transactional
    public void unlink(AppPrincipal principal) {
        linkService.unlink(identity(principal), "APP");
    }

    /**
     * IDOR himoyasi: {@code studentId} shu identity'ga bog'langanmi. {@code null} — standart
     * (birinchi bog'langan) o'quvchi.
     */
    @Transactional(readOnly = true)
    public Student requireStudent(AppPrincipal principal, Long studentId) {
        List<AppIdentityStudent> links = links(principal.identityId());
        Long id = studentId;
        if (id == null) {
            id = links.stream().findFirst().map(AppIdentityStudent::getStudentId).orElseThrow(this::notLinked);
        }
        Long wanted = id;
        if (links.stream().noneMatch(l -> l.getStudentId().equals(wanted))) {
            throw CodedException.forbidden("app.student.forbidden");
        }
        Student student = studentRepository.findById(wanted)
            .orElseThrow(() -> CodedException.forbidden("app.student.forbidden"));
        if (student.getStatus() == StudentStatus.ARCHIVED) {
            throw CodedException.forbidden("app.student.forbidden");
        }
        return student;
    }

    @Transactional(readOnly = true)
    public AppIdentity identity(AppPrincipal principal) {
        return identityRepository.findById(principal.identityId())
            .filter(AppIdentity::isActive)
            .orElseThrow(() -> new CodedException(HttpStatus.UNAUTHORIZED, "app.auth.unauthorized"));
    }

    public List<AppIdentityStudent> links(Long identityId) {
        return identityStudentRepository.findByIdentityIdOrderByIdAsc(identityId);
    }

    AppDtos.Profile profile(AppIdentity identity, List<AppIdentityStudent> links) {
        Map<Long, Student> students = studentRepository.findAllById(
                links.stream().map(AppIdentityStudent::getStudentId).toList()).stream()
            .filter(s -> s.getStatus() != StudentStatus.ARCHIVED)
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<AppDtos.StudentBrief> briefs = new ArrayList<>();
        for (AppIdentityStudent link : links) {
            Student s = students.get(link.getStudentId());
            if (s == null) {
                continue;
            }
            List<AppDtos.GroupBrief> groups = studentGroupRepository.findByStudentIdOrderByJoinDateDesc(s.getId())
                .stream()
                .filter(BillingStatusService::isOpen)
                .map(StudentGroup::getGroup)
                .map(g -> new AppDtos.GroupBrief(g.getId(), g.getGroupName(),
                    g.getCourse() != null ? g.getCourse().getCourseName() : null))
                .toList();
            briefs.add(new AppDtos.StudentBrief(s.getId(), s.getFirstName(), s.getLastName(),
                link.getRelation().name(), groups));
        }
        return new AppDtos.Profile(identity.getId(), identity.getKind().name(), identity.getFirstName(),
            maskPhone(identity.getPhoneCanonical()),
            briefs.isEmpty() ? null : briefs.get(0).id(),
            briefs, support());
    }

    AppDtos.Support support() {
        Map<String, String> center = centerSettingsService.values();
        return new AppDtos.Support(blankToNull(center.get("supportPhone")), blankToNull(center.get("address")));
    }

    private CodedException notLinked() {
        AppDtos.Support support = support();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("supportPhone", support.phone());
        return new CodedException(HttpStatus.FORBIDDEN, "app.notLinked").withData(data);
    }

    /** {@code +998901234567} → {@code +998 90 *** ** 67}. */
    static String maskPhone(String canonical) {
        if (canonical == null || canonical.length() != 13) {
            return null;
        }
        return canonical.substring(0, 4) + " " + canonical.substring(4, 6) + " *** ** " + canonical.substring(11);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
