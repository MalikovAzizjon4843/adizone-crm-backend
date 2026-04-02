package com.crm.service;

import com.crm.entity.RefreshToken;
import com.crm.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        List<RefreshToken> expired =
            refreshTokenRepository.findExpiredTokens(LocalDateTime.now());
        refreshTokenRepository.deleteAll(expired);
        log.info("Cleaned up {} expired refresh tokens", expired.size());
    }
}
