package com.crm.billing.support;

import com.crm.support.TestProfilesResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Billing integratsiya testlari asosi: H2 (PostgreSQL rejimi), boshqariladigan
 * soat, har test oldidan billing jadvallari tozalanadi.
 *
 * <p>Hujjat (§12.1) Testcontainers PostgreSQL ni taklif qiladi; bu muhitda
 * Docker yo'q, shuning uchun default — H2: u {@code SELECT … FOR UPDATE},
 * UNIQUE constraint va sequence ni qo'llab-quvvatlaydi.
 *
 * <p>H2 PostgreSQL'ning tip xulqini to'liq takrorlamaydi (masalan nullable
 * parametr {@code :p IS NULL OR ...}). Shuning uchun xuddi shu testlar lokal
 * PostgreSQL'da ham yuradi: {@code mvn test -Dspring.profiles.active=pgtest}
 * ({@code application-pgtest.yml}, {@link TestProfilesResolver}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfilesResolver.class)
@Import(BillingTestConfig.class)
public abstract class AbstractBillingIT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected BillingFixtures fixtures;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TransactionTemplate tx;

    @BeforeEach
    void resetBillingState() {
        fixtures.wipe();
        clock.setDate(LocalDate.of(2026, 9, 15));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    protected <T> T inTx(Supplier<T> body) {
        return tx.execute(s -> body.get());
    }

    protected void inTx(Runnable body) {
        tx.executeWithoutResult(s -> body.run());
    }

    protected static LocalDate d(String ddMMyyyy) {
        String[] p = ddMMyyyy.split("\\.");
        return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
    }
}
