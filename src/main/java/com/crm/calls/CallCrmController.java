package com.crm.calls;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Qo'ng'iroqlar — CRM (docs/design/calls.md §6–§7). SA, ADMIN, SALES_HEAD — hammasi; SALES_MANAGER — faqat o'zi.
 * Imzolangan yozuv havolasi ({@code /api/calls/recordings/stream}) — tokensiz, {@code <audio src>} uchun.
 */
@RestController
@RequiredArgsConstructor
public class CallCrmController {

    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','ADMIN','SALES_HEAD','SALES_MANAGER')";

    private final CallQueryService queryService;
    private final CallRecordingService recordingService;
    private final CallRequestService requestService;

    @GetMapping(value = "/api/calls")
    @PreAuthorize(value = STAFF)
    public ApiResponse<PageResponse<CallDtos.CallItem>> list(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "contactType", required = false) String contactType,
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "recorded", required = false) Boolean recorded,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.success(queryService.list(
            new CallQueryService.Filter(userId, from, to, direction, contactType, phone, null, null, recorded), page,
            size));
    }

    /** "Xodim" filtri: faol qurilmasi yoki qo'ng'irog'i bor xodimlar; ruxsat — ro'yxat bilan bir xil (SM — faqat o'zi). */
    @GetMapping(value = "/api/calls/users")
    @PreAuthorize(value = STAFF)
    public ApiResponse<java.util.List<CallDtos.CallUserItem>> users() {
        return ApiResponse.success(queryService.users());
    }

    @GetMapping(value = "/api/calls/stats")
    @PreAuthorize(value = STAFF)
    public ApiResponse<CallDtos.StatsResponse> stats(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(queryService.stats(from, to));
    }

    @GetMapping(value = "/api/calls/{id}")
    @PreAuthorize(value = STAFF)
    public ApiResponse<CallDtos.CallItem> get(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(queryService.toItem(queryService.requireVisible(id)));
    }

    @GetMapping(value = "/api/leads/{id}/calls")
    @PreAuthorize(value = STAFF)
    public ApiResponse<PageResponse<CallDtos.CallItem>> leadCalls(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.success(queryService.forLead(id, page, size));
    }

    @GetMapping(value = "/api/students/{id}/calls")
    @PreAuthorize(value = STAFF)
    public ApiResponse<PageResponse<CallDtos.CallItem>> studentCalls(
            @PathVariable(name = "id") Long id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.success(queryService.forStudent(id, page, size));
    }

    // ── Yozuv ────────────────────────────────────────────────────────────

    /** JWT bilan oqim; {@code Range} — 206 (Spring ResourceRegion). */
    @GetMapping(value = "/api/calls/{id}/recording")
    @PreAuthorize(value = STAFF)
    public ResponseEntity<Resource> recording(@PathVariable(name = "id") Long id) {
        return playable(recordingService.playable(id));
    }

    /** {@code <audio src>} uchun imzolangan qisqa havola ({@code app.calls.recording-link-ttl-seconds}). */
    @GetMapping(value = "/api/calls/{id}/recording/link")
    @PreAuthorize(value = STAFF)
    public ApiResponse<CallDtos.RecordingLink> recordingLink(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(recordingService.link(id));
    }

    /** Tokensiz — imzo va muddat tekshiriladi (SecurityConfig: permitAll). */
    @GetMapping(value = CallRecordingService.STREAM_PATH)
    public ResponseEntity<Resource> signedRecording(@RequestParam(name = "callId") Long callId,
                                                    @RequestParam(name = "exp") long exp,
                                                    @RequestParam(name = "sig") String sig) {
        return playable(recordingService.playableSigned(callId, exp, sig));
    }

    static ResponseEntity<Resource> playable(CallRecordingService.Playable p) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(p.mime()))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.filename()).build().toString())
            .cacheControl(CacheControl.noStore())
            .body(p.resource());
    }

    // ── CRM'dan qo'ng'iroq so'rovi ───────────────────────────────────────

    @PostMapping(value = "/api/calls/requests")
    @PreAuthorize(value = STAFF)
    public ResponseEntity<ApiResponse<CallDtos.RequestItem>> createRequest(@RequestBody CallDtos.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(requestService.create(request)));
    }

    @GetMapping(value = "/api/calls/requests/{id}")
    @PreAuthorize(value = STAFF)
    public ApiResponse<CallDtos.RequestItem> getRequest(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(requestService.get(id));
    }

    @PostMapping(value = "/api/calls/requests/{id}/cancel")
    @PreAuthorize(value = STAFF)
    public ApiResponse<CallDtos.RequestItem> cancelRequest(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(requestService.cancel(id));
    }
}
