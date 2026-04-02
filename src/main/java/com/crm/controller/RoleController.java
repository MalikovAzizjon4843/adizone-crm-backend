package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.entity.enums.UserRole;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getRoles() {
        List<Map<String, String>> roles = Arrays.stream(UserRole.values())
            .map(role -> {
                Map<String, String> map = new LinkedHashMap<>();
                map.put("value", role.name());
                map.put("label", getRoleLabel(role));
                return map;
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(roles));
    }

    private String getRoleLabel(UserRole role) {
        return switch (role) {
            case SUPER_ADMIN -> "Super Admin";
            case ADMIN -> "Admin";
            case TEACHER -> "O'qituvchi";
            case ACCOUNTANT -> "Buxgalter";
            case STUDENT -> "O'quvchi";
            case PARENT -> "Ota-ona";
        };
    }
}
