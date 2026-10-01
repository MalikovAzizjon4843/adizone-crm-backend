package com.crm.billing;

import com.crm.CrmApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Billing "bugun"i — Asia/Tashkent. Billing servislari {@code LocalDate.now()}
 * o'rniga shu {@link Clock} dan foydalanadi: testda {@code Clock.fixed(...)} bilan
 * almashtiriladi (docs/design/billing-v2.md §12.1).
 */
@Configuration
public class BillingClockConfig {

    public static final ZoneId ZONE = ZoneId.of(CrmApplication.TIME_ZONE);

    @Bean
    public Clock billingClock() {
        return Clock.system(ZONE);
    }
}
