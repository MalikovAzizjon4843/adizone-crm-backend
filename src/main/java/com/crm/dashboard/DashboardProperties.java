package com.crm.dashboard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/** {@code app.dashboard.*} — director-dashboard §1, §5, §6, §7. */
@Component
@ConfigurationProperties(prefix = "app.dashboard")
@Getter
@Setter
public class DashboardProperties {

    /** §7 #10: sinovdan keyin shuncha kun to'lamasa — NOT_PAID. */
    private int trialDecisionDays = 14;

    /** §1.5 stayed30. */
    private int trialStayDays = 30;

    /** §7 #11: oxirgi yozilma yopilib, shuncha kun ichida yangisi ochilmasa — churn. */
    private int churnGraceDays = 30;

    /** §7 #13: ish vaqti (Asia/Tashkent). */
    private LocalTime workStart = LocalTime.of(9, 0);
    private LocalTime workEnd = LocalTime.of(20, 0);
    private Set<DayOfWeek> workDays = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);

    /** §1.7: shuncha ish soatida javob bo'lmasa — noResponse. */
    private int noResponseWorkHours = 24;

    /** §6.3: bugungi jonli hisob keshi, soniya. */
    private int cacheSeconds = 60;

    /** §5: kunlik Telegram xulosasi. */
    private Digest digest = new Digest();

    @Getter
    @Setter
    public static class Digest {
        private boolean enabled = false;
        private String cron = "0 0 20 * * *";
        /** Vergul bilan; env DIRECTOR_DIGEST_CHAT_IDS. */
        private String chatIds = "";
        private String dashboardUrl = "";
    }

    /** §3.6: kunlik snapshot. */
    private Snapshot snapshot = new Snapshot();

    @Getter
    @Setter
    public static class Snapshot {
        private boolean enabled = true;
        private String finalCron = "0 55 23 * * *";
        /** §7 #19: oxirgi shuncha kun har kecha qayta hisoblanadi. */
        private int recomputeDays = 7;
    }
}
