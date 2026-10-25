package com.crm.controller;

import com.crm.books.BookCatalogService;
import com.crm.books.BookDtos.LevelDto;
import com.crm.books.BookDtos.LevelRequest;
import com.crm.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * O'quv darajalari (docs/design/cash-and-books.md §2.1, V87): til + kod + nom ("Turk A1"), har darajaga bepul kitob.
 * Ko'rish — xodimlar (guruh formasi, o'qituvchi ham); yaratish va tahrirlash — faqat SA.
 */
@RestController
@RequestMapping(value = "/api/study-levels")
@RequiredArgsConstructor
@PreAuthorize(value = "hasAnyRole('SUPER_ADMIN','ADMIN','ACCOUNTANT','TEACHER')")
public class StudyLevelController {

    private final BookCatalogService catalog;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LevelDto>>> list() {
        return ResponseEntity.ok(ApiResponse.success(catalog.levels()));
    }

    @PostMapping
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<LevelDto>> create(@RequestBody LevelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(catalog.createLevel(request)));
    }

    /** {@code giftBookItemId}: kitob id — shu darajadagi o'quv kitobi; 0 — olib tashlash; null — o'zgarmaydi. */
    @PutMapping(value = "/{id}")
    @PreAuthorize(value = "hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<LevelDto>> update(@PathVariable(name = "id") Long id,
                                                        @RequestBody LevelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(catalog.updateLevel(id, request)));
    }
}
