package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.AppLinkAttempt;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.repository.UserRepository;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppIdentityStudentRepository;
import com.crm.repository.AppLinkAttemptRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import com.crm.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Telefon bo'yicha Mini App identity'sini o'quvchi/ota-onaga bog'lash
 * (docs/design/telegram-platform.md §3.4).
 *
 * <p><b>Moslash</b> (kanonik telefon, ARCHIVED o'quvchilar hisobga olinmaydi):
 * <ul>
 *   <li>faol {@code parents.phone} → {@code student_parents} orqali farzandlar — PARENT;</li>
 *   <li>{@code students.parent_phone} → shu o'quvchilar — PARENT;</li>
 *   <li>{@code students.phone}: bitta o'quvchi — SELF; bir nechta (aka-uka ota-ona raqamini yozgan) —
 *       hammasi PARENT.</li>
 * </ul>
 * Bir o'quvchi ham SELF, ham PARENT bo'lib chiqsa (o'quvchiga ota-ona raqami yozilgan) — PARENT.
 * Telefon ham o'quvchi, ham boshqa o'quvchilarning ota-onasi bo'lsa — hammasi bitta identity'ga
 * bog'lanadi va Mini App'dagi almashtirgichda ko'rinadi (hujjatdagi CHOOSE o'rniga, buyurtmachi qarori).
 * {@code kind} — kamida bitta PARENT bo'lsa PARENT, aks holda STUDENT.
 *
 * <p>Telefon taqqoslash: bazadagi telefonlar {@code PhoneDeserializer} bilan kanonik saqlanadi;
 * eski yozuvlar uchun {@code 998XXXXXXXXX} va {@code XXXXXXXXX} shakllari ham qidiriladi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MiniAppLinkService {

    /** 24 soatda shuncha NOT_FOUND dan keyin bog'lash vaqtincha rad (raqam terib qidirishga qarshi). */
    static final int NOT_FOUND_LIMIT = 5;

    private final AppIdentityRepository identityRepository;
    private final AppIdentityStudentRepository identityStudentRepository;
    private final AppLinkAttemptRepository attemptRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final StudentParentRepository studentParentRepository;
    private final UserRepository userRepository;
    private final Clock billingClock;

    public enum Outcome { LINKED, NOT_FOUND, LIMITED }

    /**
     * O'quvchilar va bog'lanish turi (tartib: barqaror, id bo'yicha) hamda o'qituvchi rejimi uchun xodim useri
     * (§11.4; null — yo'q).
     */
    public record Match(AppIdentity.Kind kind, Map<Long, AppIdentityStudent.Relation> students, Long staffUserId) {
        public boolean isEmpty() {
            return students.isEmpty() && staffUserId == null;
        }
    }

    public record LinkResult(Outcome outcome, AppIdentity identity, List<Student> students) {
    }

    /** Telefon bo'yicha moslik — yozuvsiz. */
    @Transactional(readOnly = true)
    public Match match(String canonicalPhone) {
        Map<Long, AppIdentityStudent.Relation> result = new java.util.TreeMap<>();
        if (canonicalPhone == null) {
            return new Match(AppIdentity.Kind.STUDENT, result, null);
        }
        List<String> variants = variants(canonicalPhone);

        List<Long> parentIds = parentRepository.findByPhoneInAndIsActiveTrue(variants).stream()
            .map(Parent::getId).toList();
        if (!parentIds.isEmpty()) {
            for (Student s : studentParentRepository.findStudentsByParentIds(parentIds, StudentStatus.ARCHIVED)) {
                result.put(s.getId(), AppIdentityStudent.Relation.PARENT);
            }
        }
        for (Student s : studentRepository.findByParentPhoneInAndStatusNot(variants, StudentStatus.ARCHIVED)) {
            result.put(s.getId(), AppIdentityStudent.Relation.PARENT);
        }
        List<Student> self = studentRepository.findByPhoneInAndStatusNot(variants, StudentStatus.ARCHIVED);
        AppIdentityStudent.Relation selfRelation = self.size() > 1
            ? AppIdentityStudent.Relation.PARENT : AppIdentityStudent.Relation.SELF;
        for (Student s : self) {
            result.putIfAbsent(s.getId(), selfRelation);
        }
        Long staffUserId = teacherUserId(variants);
        AppIdentity.Kind kind = result.containsValue(AppIdentityStudent.Relation.PARENT) ? AppIdentity.Kind.PARENT
            : !result.isEmpty() ? AppIdentity.Kind.STUDENT
            : AppIdentity.Kind.TEACHER;
        return new Match(kind, result, staffUserId);
    }

    /**
     * O'qituvchi rejimi (§11.4, D8–D9): faqat TEACHER rolidagi faol xodim, aynan bitta. Bir nechta bo'lsa —
     * rejim berilmaydi (qaysi biri ekanini telefon aniqlamaydi).
     */
    private Long teacherUserId(List<String> variants) {
        List<User> users = userRepository.findActiveByRoleAndPhoneIn(UserRole.TEACHER, variants);
        if (users.size() > 1) {
            log.warn("Mini App: telefon {} ta o'qituvchi useriga mos — o'qituvchi rejimi berilmadi", users.size());
            return null;
        }
        return users.isEmpty() ? null : users.get(0).getId();
    }

    /**
     * Botga kelgan kontakt (chaqiruvchi {@code contact.user_id == from.id} ni tekshirgan) bo'yicha
     * bog'lash. Qayta ulash shu Telegram hisobining qatorini yangilaydi; telefon o'zgarsa yoki avval
     * uzilgan bo'lsa {@code identityVersion} oshadi (eski JWT lar yaroqsiz).
     */
    @Transactional
    public LinkResult linkFromContact(long telegramUserId, Long chatId, String username, String firstName,
                                      String rawPhone) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        String canonical = PhoneUtils.canonical(rawPhone);
        String phoneHash = canonical != null ? sha256(canonical) : null;

        long recentMisses = attemptRepository.countByTelegramUserIdAndResultAndCreatedAtAfter(
            telegramUserId, AppLinkAttempt.Result.NOT_FOUND, now.minusHours(24));
        if (recentMisses >= NOT_FOUND_LIMIT) {
            recordAttempt(telegramUserId, phoneHash, AppLinkAttempt.Result.LIMITED, now);
            return new LinkResult(Outcome.LIMITED, null, List.of());
        }

        Match match = match(canonical);
        if (match.isEmpty()) {
            recordAttempt(telegramUserId, phoneHash, AppLinkAttempt.Result.NOT_FOUND, now);
            log.info("Mini App bog'lash: moslik yo'q (telegramUserId={})", telegramUserId);
            return new LinkResult(Outcome.NOT_FOUND, null, List.of());
        }
        LinkResult result = linkVerified(telegramUserId, chatId, username, firstName, canonical, match);
        recordAttempt(telegramUserId, phoneHash, AppLinkAttempt.Result.LINKED, now);
        return result;
    }

    /**
     * Telefoni tasdiqlangan bog'lash: bot kontakti ({@code contact.user_id == from.id}) yoki xodim tasdiqlagan
     * qo'lda so'rov (§11.1). Qayta ulash shu Telegram hisobining qatorini yangilaydi; telefon o'zgarsa yoki avval
     * uzilgan bo'lsa {@code identityVersion} oshadi. {@code match} bo'sh bo'lmasligi kerak.
     */
    @Transactional
    public LinkResult linkVerified(long telegramUserId, Long chatId, String username, String firstName,
                                   String canonical, Match match) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        AppIdentity identity = identityRepository.findByTelegramUserId(telegramUserId).orElse(null);
        if (identity == null) {
            identity = AppIdentity.builder()
                .telegramUserId(telegramUserId)
                .identityVersion(0)
                .createdAt(now)
                .build();
        } else if (!identity.isActive() || !Objects.equals(identity.getPhoneCanonical(), canonical)) {
            identity.setIdentityVersion(identity.getIdentityVersion() + 1);
        }
        identity.setChatId(chatId);
        identity.setTelegramUsername(username);
        identity.setFirstName(firstName);
        identity.setPhoneCanonical(canonical);
        identity.setKind(match.kind());
        identity.setStatus(AppIdentity.Status.ACTIVE);
        identity.setLinkedAt(now);
        identity.setUnlinkedAt(null);
        identity.setUnlinkReason(null);
        claimStaffUser(identity, telegramUserId, match.staffUserId());
        identity = identityRepository.save(identity);

        replaceStudents(identity.getId(), match, now);
        log.info("Mini App bog'landi: identity={}, kind={}, o'quvchilar={}, o'qituvchi={}",
            identity.getId(), match.kind(), match.students().size(), match.staffUserId() != null);
        return new LinkResult(Outcome.LINKED, identity, studentRepository.findAllById(match.students().keySet()));
    }

    /**
     * Bitta xodim — bitta identity: boshqa Telegram hisobidagi shu xodim bog'lanishi olib qo'yiladi (V68 unikal
     * indeks). Eskisining versiyasi oshadi — o'qituvchi rejimidagi tokeni darhol yaroqsiz.
     */
    private void claimStaffUser(AppIdentity identity, long telegramUserId, Long staffUserId) {
        if (staffUserId != null) {
            for (AppIdentity other : identityRepository.findByStaffUserId(staffUserId)) {
                if (!other.getTelegramUserId().equals(telegramUserId)) {
                    other.setStaffUserId(null);
                    other.setIdentityVersion(other.getIdentityVersion() + 1);
                    identityRepository.saveAndFlush(other);
                    log.info("Mini App: o'qituvchi rejimi identity {} dan olib qo'yildi (yangi ulanish)", other.getId());
                }
            }
        }
        identity.setStaffUserId(staffUserId);
    }

    /** {@code contact.user_id != from.id} (birovning kontakti) — faqat qayd qilinadi. */
    @Transactional
    public void recordRejected(long telegramUserId) {
        recordAttempt(telegramUserId, null, AppLinkAttempt.Result.REJECTED, LocalDateTime.now(billingClock));
    }

    /**
     * Qayta tekshiruv ({@code POST /api/app/auth} da): telefon bo'yicha o'quvchilar ro'yxati va o'qituvchi
     * rejimi qayta quriladi. Hech narsa qolmasa identity uziladi ({@code NO_STUDENTS}) va {@code false} qaytadi.
     */
    @Transactional
    public boolean resync(AppIdentity identity) {
        Match match = match(identity.getPhoneCanonical());
        if (match.isEmpty()) {
            unlink(identity, "NO_STUDENTS");
            return false;
        }
        List<AppIdentityStudent> current = identityStudentRepository.findByIdentityIdOrderByIdAsc(identity.getId());
        Map<Long, AppIdentityStudent.Relation> existing = new java.util.TreeMap<>();
        current.forEach(s -> existing.put(s.getStudentId(), s.getRelation()));
        if (!existing.equals(match.students())) {
            replaceStudents(identity.getId(), match, LocalDateTime.now(billingClock));
        }
        if (!Objects.equals(identity.getStaffUserId(), match.staffUserId())) {
            claimStaffUser(identity, identity.getTelegramUserId(), match.staffUserId());
        }
        identity.setKind(match.kind());
        identity.setLastSeenAt(LocalDateTime.now(billingClock));
        identityRepository.save(identity);
        return true;
    }

    /** Uzish: o'quvchi ro'yxati o'chadi, versiya oshadi — berilgan JWT lar darhol yaroqsiz. */
    @Transactional
    public void unlink(AppIdentity identity, String reason) {
        identityStudentRepository.deleteByIdentityId(identity.getId());
        identity.setStatus(AppIdentity.Status.UNLINKED);
        identity.setStaffUserId(null);
        identity.setIdentityVersion(identity.getIdentityVersion() + 1);
        identity.setUnlinkedAt(LocalDateTime.now(billingClock));
        identity.setUnlinkReason(reason);
        identityRepository.save(identity);
        log.info("Mini App identity uzildi: identity={}, sabab={}", identity.getId(), reason);
    }

    private void replaceStudents(Long identityId, Match match, LocalDateTime now) {
        identityStudentRepository.deleteByIdentityId(identityId);
        identityStudentRepository.flush();
        List<AppIdentityStudent> rows = new ArrayList<>();
        match.students().forEach((studentId, relation) -> rows.add(AppIdentityStudent.builder()
            .identityId(identityId)
            .studentId(studentId)
            .relation(relation)
            .linkedAt(now)
            .build()));
        identityStudentRepository.saveAll(rows);
    }

    private void recordAttempt(long telegramUserId, String phoneHash, AppLinkAttempt.Result result,
                               LocalDateTime now) {
        attemptRepository.save(AppLinkAttempt.builder()
            .telegramUserId(telegramUserId)
            .phoneHash(phoneHash)
            .result(result)
            .createdAt(now)
            .build());
    }

    /** {@code +998901234567} → [+998901234567, 998901234567, 901234567]. */
    static List<String> variants(String canonical) {
        String digits = canonical.substring(1);
        return List.of(canonical, digits, digits.substring(3));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
