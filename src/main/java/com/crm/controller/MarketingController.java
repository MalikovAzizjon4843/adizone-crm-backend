package com.crm.controller;
import com.crm.dto.response.ApiResponse;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/marketing")
@RequiredArgsConstructor
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
