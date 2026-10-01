package com.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EnableAsync  // audit yozuvlari asosiy so'rovni kutmasin
public class CrmApplication {

    /** Biznes vaqt zonasi: "bugun", LocalDate/LocalDateTime.now(), cron — hammasi Toshkent bo'yicha. */
    public static final String TIME_ZONE = "Asia/Tashkent";

    public static void main(String[] args) {
        // Spring konteksti (Hibernate, Jackson, scheduler) ko'tarilishidan OLDIN — server qaysi
        // zonada ishlashidan qat'i nazar (docs/ops/timezone.md).
        TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
        SpringApplication.run(CrmApplication.class, args);
    }
}
