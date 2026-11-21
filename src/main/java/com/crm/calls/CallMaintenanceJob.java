package com.crm.calls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Har kecha ({@code app.calls.retention-cron}, standart 04:10): qo'ng'iroqdan {@code app.calls.recording-retention-days}
 * kun o'tgan yozuv fayllari o'chiriladi (metama'lumot qoladi) va muddati o'tgan qo'ng'iroq so'rovlari EXPIRED bo'ladi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallMaintenanceJob {

    private final CallRecordingService recordingService;
    private final CallRequestService requestService;

    @Scheduled(cron = "${app.calls.retention-cron:0 10 4 * * *}", zone = "Asia/Tashkent")
    public void run() {
        try {
            int purged = recordingService.purgeExpired();
            int expired = requestService.expireDue();
            if (purged > 0 || expired > 0) {
                log.info("Qo'ng'iroqlar: {} ta yozuv fayli o'chirildi (retention), {} ta so'rov eskirdi", purged, expired);
            }
        } catch (Exception e) {
            log.error("Qo'ng'iroqlar retention ishi xato bilan tugadi: {}", e.getMessage(), e);
        }
    }
}
