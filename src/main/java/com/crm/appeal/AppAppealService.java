package com.crm.appeal;

import com.crm.entity.AppIdentity;
import com.crm.entity.Appeal;
import com.crm.entity.AppealMessage;
import com.crm.entity.Teacher;
import com.crm.exception.CodedException;
import com.crm.miniapp.AppPrincipal;
import com.crm.miniapp.MiniAppAuthService;
import com.crm.repository.AppealMessageRepository;
import com.crm.repository.AppealRepository;
import com.crm.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mini App: o'z murojaatlari (miniapp-api.md §7b). "O'zi" — Telegram chat (shaxsiy chat id = Telegram user id)
 * bo'yicha: botdan yuborilganlari ham, anonimlari ham ko'rinadi (ular faqat shu chatga bog'langan). Ichki
 * (xodimlar) izohlari va xodim ismi chiqmaydi.
 */
@Service
@RequiredArgsConstructor
public class AppAppealService {

    private final MiniAppAuthService authService;
    private final AppealService appealService;
    private final AppealRepository appealRepository;
    private final AppealMessageRepository messageRepository;
    private final TeacherRepository teacherRepository;
    private final AppealProperties properties;

    @Transactional(readOnly = true)
    public AppealDtos.AppList list(AppPrincipal principal) {
        AppIdentity identity = authService.identity(principal);
        long chatId = chatId(identity);
        List<Appeal> appeals = appealRepository.findByTelegramChatIdOrderByCreatedAtDescIdDesc(chatId);
        Map<Long, List<AppealMessage>> messages = messageRepository.findByAppealIdInOrderByIdAsc(
                appeals.stream().map(Appeal::getId).toList()).stream()
            .filter(m -> !m.isInternal())
            .collect(Collectors.groupingBy(AppealMessage::getAppealId));
        Map<Long, Teacher> teachers = teacherRepository.findAllById(appeals.stream().map(Appeal::getTeacherId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(Teacher::getId, Function.identity()));
        List<AppealDtos.AppItem> items = appeals.stream()
            .map(a -> item(a, messages.getOrDefault(a.getId(), List.of()), teachers.get(a.getTeacherId())))
            .toList();
        List<AppealDtos.Ref> teacherOptions = appealService.teacherOptions(identity.getId()).stream()
            .map(o -> new AppealDtos.Ref(o.id(), o.name())).toList();
        return new AppealDtos.AppList(properties.isEnabled(), properties.getDailyLimit(),
            appealService.remainingToday(chatId), appealService.textMax(),
            Arrays.stream(Appeal.Type.values()).map(Enum::name).toList(), teacherOptions, items);
    }

    /**
     * Yangi murojaat. {@code studentId} — ixtiyoriy (IDOR tekshiruvi); {@code teacherId} — faqat TEACHER turida va
     * identity o'quvchilarining o'qituvchilaridan. Anonim bo'lsa yuboruvchi maydonlari saqlanmaydi.
     */
    @Transactional
    public AppealDtos.AppItem create(AppPrincipal principal, AppealDtos.AppCreateRequest request) {
        requireEnabled();
        AppIdentity identity = authService.identity(principal);
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw CodedException.badRequest("appeal.type.required");
        }
        Appeal.Type type = AppealService.parseType(request.type())
            .orElseThrow(() -> CodedException.badRequest("appeal.type.invalid", request.type()));
        boolean anonymous = Boolean.TRUE.equals(request.anonymous());
        Long teacherId = request.teacherId();
        if (teacherId != null) {
            if (type != Appeal.Type.TEACHER) {
                throw CodedException.badRequest("appeal.teacher.typeMismatch");
            }
            boolean allowed = appealService.teacherOptions(identity.getId()).stream()
                .anyMatch(o -> o.id().equals(teacherId));
            if (!allowed) {
                throw CodedException.badRequest("appeal.teacher.notFound");
            }
        }
        AppealService.SenderLink link = anonymous ? new AppealService.SenderLink(null, null)
            : appealService.senderLink(identity, request.studentId());
        Appeal appeal = appealService.create(new AppealService.NewAppeal(type, request.text(), anonymous,
            Appeal.Source.MINIAPP, chatId(identity), identity.getLanguage(),
            anonymous ? null : identity.getId(), link.studentId(), link.parentId(), null, null, teacherId));
        return item(appeal, List.of(), teacherId == null ? null : teacherRepository.findById(teacherId).orElse(null));
    }

    /** Yuboruvchi xabari — yopilgan murojaat qayta ochiladi (IN_PROGRESS). Begona — 404. */
    @Transactional
    public AppealDtos.AppItem addMessage(AppPrincipal principal, Long appealId, AppealDtos.AppMessageRequest request) {
        requireEnabled();
        AppIdentity identity = authService.identity(principal);
        Appeal appeal = appealRepository.findByIdAndTelegramChatId(appealId, chatId(identity))
            .orElseThrow(() -> CodedException.notFound("appeal.notFound"));
        appealService.addSenderMessage(appeal, request == null ? null : request.text());
        List<AppealMessage> messages = messageRepository.findByAppealIdOrderByIdAsc(appeal.getId()).stream()
            .filter(m -> !m.isInternal()).toList();
        return item(appeal, messages,
            appeal.getTeacherId() == null ? null : teacherRepository.findById(appeal.getTeacherId()).orElse(null));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw CodedException.forbidden("appeal.disabled");
        }
    }

    /** Shaxsiy chat id = Telegram user id (chat id hali yozilmagan bo'lsa ham). */
    static long chatId(AppIdentity identity) {
        return identity.getChatId() != null ? identity.getChatId() : identity.getTelegramUserId();
    }

    private static AppealDtos.AppItem item(Appeal a, List<AppealMessage> messages, Teacher teacher) {
        return new AppealDtos.AppItem(a.getId(), a.getNumber(), a.getType().name(), a.getStatus().name(),
            a.isAnonymous(), a.getText(),
            teacher == null ? null : new AppealDtos.Ref(teacher.getId(), AppealService.teacherName(teacher)),
            a.getCreatedAt(), a.getResolvedAt(),
            messages.stream()
                .map(m -> new AppealDtos.AppMessage(m.getId(), m.getAuthorType().name(), m.getText(), m.getCreatedAt()))
                .toList());
    }
}
