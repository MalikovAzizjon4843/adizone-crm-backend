package com.crm.appeal;

import com.crm.dto.response.ApiResponse;
import com.crm.miniapp.AppPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mini App: taklif va shikoyatlar (docs/design/miniapp-api.md §7b). App JWT ({@code ROLE_APP}) —
 * {@code AppSecurityConfig} zanjiri.
 */
@RestController
@RequestMapping(value = "/api/app/appeals")
@RequiredArgsConstructor
public class AppAppealController {

    private final AppAppealService service;

    @GetMapping
    public ApiResponse<AppealDtos.AppList> list(@AuthenticationPrincipal AppPrincipal principal) {
        return ApiResponse.success(service.list(principal));
    }

    @PostMapping
    public ApiResponse<AppealDtos.AppItem> create(@AuthenticationPrincipal AppPrincipal principal,
                                                  @RequestBody AppealDtos.AppCreateRequest request) {
        return ApiResponse.success(service.create(principal, request));
    }

    @PostMapping(value = "/{id}/messages")
    public ApiResponse<AppealDtos.AppItem> addMessage(@AuthenticationPrincipal AppPrincipal principal,
                                                      @PathVariable(name = "id") Long id,
                                                      @RequestBody AppealDtos.AppMessageRequest request) {
        return ApiResponse.success(service.addMessage(principal, id, request));
    }
}
