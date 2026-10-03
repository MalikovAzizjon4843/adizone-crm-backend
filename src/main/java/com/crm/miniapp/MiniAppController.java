package com.crm.miniapp;

import com.crm.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * O'quvchi / ota-ona Telegram Mini App API'si — shartnoma: docs/design/miniapp-api.md.
 * Faqat {@link AppSecurityConfig} zanjirida (app JWT); {@code studentId} har doim
 * {@link MiniAppAuthService#requireStudent} dan o'tadi.
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class MiniAppController {

    private final MiniAppAuthService authService;
    private final MiniAppQueryService queryService;
    private final MiniAppLinkRequestService linkRequestService;
    private final MiniAppAbsenceService absenceService;

    @PostMapping("/auth")
    public ApiResponse<AppDtos.AuthResponse> auth(@Valid @RequestBody AppDtos.AuthRequest request) {
        return ApiResponse.success(authService.authenticate(request.initData()));
    }

    /**
     * Qo'lda raqam (telegram-platform §11.1): bazada bo'lsa — markaz tasdiqlaydigan so'rov; yo'q bo'lsa 404
     * {@code app.phoneNotFound}. Ochiq yo'l — initData bilan himoyalangan.
     */
    @PostMapping("/link/manual")
    public ApiResponse<AppDtos.LinkRequestResult> manualLink(@Valid @RequestBody AppDtos.ManualLinkRequest request) {
        return ApiResponse.success("So'rov yuborildi, markaz tasdiqlaydi",
            linkRequestService.submit(request.initData(), request.phone()));
    }

    @GetMapping("/me")
    public ApiResponse<AppDtos.Profile> me(@AuthenticationPrincipal AppPrincipal principal) {
        return ApiResponse.success(authService.me(principal));
    }

    @GetMapping("/home")
    public ApiResponse<AppDtos.Home> home(@AuthenticationPrincipal AppPrincipal principal,
                                          @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(queryService.home(authService.requireStudent(principal, studentId)));
    }

    @GetMapping("/schedule")
    public ApiResponse<AppDtos.Schedule> schedule(
            @AuthenticationPrincipal AppPrincipal principal,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(queryService.schedule(authService.requireStudent(principal, studentId), from, to));
    }

    @GetMapping("/attendance")
    public ApiResponse<AppDtos.AttendanceMonth> attendance(@AuthenticationPrincipal AppPrincipal principal,
                                                           @RequestParam(required = false) Long studentId,
                                                           @RequestParam(required = false) String month) {
        return ApiResponse.success(queryService.attendance(authService.requireStudent(principal, studentId), month));
    }

    @GetMapping("/payments")
    public ApiResponse<AppDtos.Payments> payments(@AuthenticationPrincipal AppPrincipal principal,
                                                  @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(queryService.payments(authService.requireStudent(principal, studentId)));
    }

    @GetMapping("/profile")
    public ApiResponse<AppDtos.ProfileScreen> profile(@AuthenticationPrincipal AppPrincipal principal,
                                                      @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(queryService.profile(principal, authService.requireStudent(principal, studentId)));
    }

    // ── Sabab bildirish (telegram-platform §11.2) ──

    @PostMapping("/absence-notices")
    public ApiResponse<AppDtos.AbsenceNoticeItem> createAbsenceNotice(
            @AuthenticationPrincipal AppPrincipal principal,
            @Valid @RequestBody AppDtos.AbsenceNoticeRequest request) {
        return ApiResponse.success("Sabab o'qituvchiga yuborildi", absenceService.create(principal, request));
    }

    @GetMapping("/absence-notices")
    public ApiResponse<List<AppDtos.AbsenceNoticeItem>> absenceNotices(@AuthenticationPrincipal AppPrincipal principal,
                                                                     @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(absenceService.list(principal, studentId));
    }

    @PostMapping("/absence-notices/{id}/cancel")
    public ApiResponse<AppDtos.AbsenceNoticeItem> cancelAbsenceNotice(@AuthenticationPrincipal AppPrincipal principal,
                                                                     @PathVariable Long id) {
        return ApiResponse.success(absenceService.cancel(principal, id));
    }

    /** Bog'lanishni uzish — token darhol yaroqsiz; qayta ulash botda (/start). */
    @PostMapping("/profile/unlink")
    public ApiResponse<Void> unlink(@AuthenticationPrincipal AppPrincipal principal) {
        authService.unlink(principal);
        return ApiResponse.success(null);
    }
}
