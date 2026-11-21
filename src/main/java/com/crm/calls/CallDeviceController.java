package com.crm.calls;

import com.crm.dto.response.ApiResponse;
import com.crm.service.LeadAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Qurilmalar (docs/design/calls.md §3). Ro'yxatdan o'tkazish — oddiy xodim JWT bilan ({@code POST /api/auth/login}
 * dan keyin); ro'yxat va revoke — faqat SUPER_ADMIN.
 */
@RestController
@RequestMapping(value = "/api/calls/devices")
@RequiredArgsConstructor
public class CallDeviceController {

    private final CallDeviceService deviceService;
    private final LeadAccessService leadAccessService;

    @PostMapping(value = "/register")
    @PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','SALES_HEAD','SALES_MANAGER')")
    public ResponseEntity<ApiResponse<CallDtos.RegisterResponse>> register(
            @RequestBody CallDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            deviceService.register(leadAccessService.getCurrentUserOrThrow(), request)));
    }

    @GetMapping
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ApiResponse<List<CallDtos.DeviceItem>> list() {
        return ApiResponse.success(deviceService.list());
    }

    /** Revoke — qurilma tokeni darhol yaroqsiz (ilova 401 {@code calls.device.revoked} oladi). Qator o'chirilmaydi. */
    @DeleteMapping(value = "/{id}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ApiResponse<CallDtos.DeviceItem> revoke(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(deviceService.revokeById(id, leadAccessService.getCurrentUserOrThrow()));
    }
}
