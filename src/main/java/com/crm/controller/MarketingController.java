package com.crm.controller;
import com.crm.dto.response.ApiResponse;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Manba statistikasi — /api/analytics/marketing/sources bilan bir xil ruxsat. */
@RestController
@RequestMapping("/api/marketing")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
public class MarketingController {
    private final StudentRepository studentRepository;

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getMarketingSources() {
        Map<String, Long> sources = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> sources.put(row[0].toString(), (Long) row[1]));
        return ResponseEntity.ok(ApiResponse.success(sources));
    }
}
