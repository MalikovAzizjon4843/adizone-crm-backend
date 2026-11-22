package com.crm.calls;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * "Adizone Qo'ng'iroqlar" Android ilovasi API si (docs/design/calls.md §4–§5). Autentifikatsiya — {@code X-Device-Token}
 * ({@link CallsSecurityConfig}); {@code /version} — ochiq.
 */
@RestController
@RequestMapping(value = "/api/calls/app")
@RequiredArgsConstructor
public class CallAppController {

    private final CallSyncService syncService;
    private final CallRecordingService recordingService;
    private final CallLookupService lookupService;
    private final CallResultService resultService;
    private final CallRequestService requestService;
    private final CallExclusionService exclusionService;
    private final CallDeviceService deviceService;
    private final CallsProperties properties;
    private final com.crm.service.LeadStageService leadStageService;
    private final CallSearchService searchService;
    private final CallQueryService queryService;
    private final CallDeleteService deleteService;

    @PostMapping(value = "/sync")
    public ApiResponse<CallDtos.SyncResponse> sync(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                   @RequestBody CallDtos.SyncRequest request) {
        return ApiResponse.success(syncService.sync(principal, request));
    }

    @PostMapping(value = "/calls/{id}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CallDtos.RecordingInfo> uploadRecording(
            @AuthenticationPrincipal CallDevicePrincipal principal,
            @PathVariable(name = "id") Long id,
            @RequestPart(name = "file") MultipartFile file,
            @RequestParam(name = "sha256") String sha256,
            @RequestParam(name = "durationSec", required = false) Integer durationSec) {
        return ApiResponse.success(recordingService.upload(principal, id, file, sha256, durationSec));
    }

    // ── Qurilma sinxron sozlamasi (§3.3) ─────────────────────────────────

    @GetMapping(value = "/settings")
    public ApiResponse<CallDtos.DeviceSettings> settings(@AuthenticationPrincipal CallDevicePrincipal principal) {
        return ApiResponse.success(deviceService.getSettings(principal));
    }

    @PutMapping(value = "/settings")
    public ApiResponse<CallDtos.DeviceSettings> updateSettings(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                               @RequestBody CallDtos.DeviceSettingsRequest request) {
        return ApiResponse.success(deviceService.updateSettings(principal, request));
    }

    // ── Terish ekrani qidiruvi (§4.7) ────────────────────────────────────

    @GetMapping(value = "/search")
    public ApiResponse<List<CallDtos.SearchItem>> search(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                         @RequestParam(name = "q") String q) {
        return ApiResponse.success(searchService.search(principal, q));
    }

    // ── O'z qo'ng'iroqlari, yozuvi, o'chirish (§4.8) ─────────────────────

    @GetMapping(value = "/calls")
    public ApiResponse<PageResponse<CallDtos.CallItem>> myCalls(
            @AuthenticationPrincipal CallDevicePrincipal principal,
            @RequestParam(name = "recorded", required = false) Boolean recorded,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.success(queryService.listForOwner(principal.userId(), recorded, from, to, page, size));
    }

    /** Oqim; {@code Range} — 206. Faqat o'z qo'ng'irog'i (boshqasiniki — 404). */
    @GetMapping(value = "/calls/{id}/recording")
    public ResponseEntity<Resource> myRecording(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                @PathVariable(name = "id") Long id) {
        return CallCrmController.playable(recordingService.playableOwn(principal, id));
    }

    /** Noto'g'ri yuborilgan (shaxsiy) qo'ng'iroq — yozuvi bilan; o'ziniki, 7 kun ichida; bo'sh avto-lid ham. */
    @DeleteMapping(value = "/calls/{id}")
    public ApiResponse<CallDtos.DeleteCallResponse> deleteCall(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                               @PathVariable(name = "id") Long id) {
        return ApiResponse.success(deleteService.delete(principal, id));
    }

    @GetMapping(value = "/lookup")
    public ApiResponse<CallDtos.LookupResponse> lookup(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                       @RequestParam(name = "phone") String phone) {
        return ApiResponse.success(lookupService.lookup(principal, phone));
    }

    @PatchMapping(value = "/calls/{id}/result")
    public ApiResponse<CallDtos.ResultResponse> result(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                       @PathVariable(name = "id") Long id,
                                                       @RequestBody CallDtos.ResultRequest request) {
        return ApiResponse.success(resultService.patch(principal, id, request));
    }

    @GetMapping(value = "/requests/pending")
    public ApiResponse<List<CallDtos.RequestItem>> pending(@AuthenticationPrincipal CallDevicePrincipal principal) {
        return ApiResponse.success(requestService.pending(principal));
    }

    @PostMapping(value = "/requests/{id}/ack")
    public ApiResponse<CallDtos.RequestItem> ack(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                 @PathVariable(name = "id") Long id,
                                                 @RequestBody CallDtos.AckRequest request) {
        return ApiResponse.success(requestService.ack(principal, id, request));
    }

    @GetMapping(value = "/exclusions")
    public ApiResponse<List<CallDtos.ExclusionItem>> exclusions(@AuthenticationPrincipal CallDevicePrincipal principal) {
        return ApiResponse.success(exclusionService.list(principal));
    }

    @PutMapping(value = "/exclusions")
    public ApiResponse<List<CallDtos.ExclusionItem>> replaceExclusions(
            @AuthenticationPrincipal CallDevicePrincipal principal,
            @RequestBody CallDtos.ExclusionsRequest request) {
        return ApiResponse.success(exclusionService.replace(principal, request));
    }

    /**
     * Lid bosqichlari — natija ekrani uchun ({@code GET /api/lead-stages} bilan aynan bir xil shakl; ilova
     * {@code isActive = false} larini ko'rsatmaydi).
     */
    @GetMapping(value = "/lead-stages")
    public ApiResponse<List<com.crm.dto.response.LeadStageResponse>> leadStages() {
        return ApiResponse.success(leadStageService.getAll());
    }

    @GetMapping(value = "/version")
    public ApiResponse<CallDtos.VersionResponse> version() {
        CallsProperties.App app = properties.getApp();
        return ApiResponse.success(new CallDtos.VersionResponse(app.getLatestVersion(), app.getMinSupportedVersion(),
            app.getApkUrl()));
    }

    @PostMapping(value = "/heartbeat")
    public ApiResponse<CallDtos.HeartbeatResponse> heartbeat(@AuthenticationPrincipal CallDevicePrincipal principal,
                                                             @RequestBody(required = false) CallDtos.HeartbeatRequest request) {
        return ApiResponse.success(deviceService.heartbeat(principal, request));
    }

    /** Ilovadan chiqish — qurilma tokeni darhol yaroqsiz. */
    @PostMapping(value = "/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CallDevicePrincipal principal) {
        deviceService.logout(principal);
        return ApiResponse.success(null);
    }
}
