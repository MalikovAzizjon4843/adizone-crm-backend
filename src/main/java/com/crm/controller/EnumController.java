package com.crm.controller;

import com.crm.dto.response.ApiResponse;
import com.crm.dto.response.EnumOptionDto;
import com.crm.entity.enums.PaymentMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Enum ro'yxatlarini frontend'ga beradi — ro'yxat qo'lda takrorlanmasligi uchun.
 * Ruxsat: SecurityConfig dagi {@code anyRequest().authenticated()} — barcha
 * autentifikatsiyadan o'tgan foydalanuvchilar.
 */
@RestController
@RequestMapping("/api/enums")
@RequiredArgsConstructor
public class EnumController {

    @GetMapping("/payment-methods")
    public ResponseEntity<ApiResponse<List<EnumOptionDto>>> getPaymentMethods() {
        List<EnumOptionDto> options = Arrays.stream(PaymentMethod.values())
            .map(m -> EnumOptionDto.builder()
                .value(m.name())
                .label(m.getLabel())
                .icon(m.getIcon())
                .build())
            .toList();
        return ResponseEntity.ok(ApiResponse.success(options));
    }
}
