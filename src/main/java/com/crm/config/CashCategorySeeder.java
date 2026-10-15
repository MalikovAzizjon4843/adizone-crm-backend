package com.crm.config;

import com.crm.entity.CashCategory;
import com.crm.repository.CashCategoryRepository;
import com.crm.service.CashCategories;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code cash_categories} da yetishmagan standart kategoriyalarni qo'shadi (prodda V86 seed qiladi —
 * bu yerda H2 testlar va V86 dan keyin qo'shilgan kodlar uchun). <b>Mavjud qatorga tegmaydi</b>: SA
 * o'zgartirgan nom, tartib yoki o'chirilgan holat tiklanmaydi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(value = 101)
public class CashCategorySeeder implements ApplicationRunner {

    private final CashCategoryRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<CashCategory> missing = new ArrayList<>();
            for (CashCategory c : CashCategories.defaults()) {
                if (!repository.existsById(c.getCode())) {
                    missing.add(c);
                }
            }
            if (!missing.isEmpty()) {
                repository.saveAll(missing);
                log.info("cash_categories: {} ta standart kategoriya qo'shildi", missing.size());
            }
        } catch (Exception e) {
            log.error("cash_categories seed bajarilmadi — V86 qo'llanganini tekshiring", e);
        }
    }
}
