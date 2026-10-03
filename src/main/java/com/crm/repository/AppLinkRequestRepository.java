package com.crm.repository;

import com.crm.entity.AppLinkRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppLinkRequestRepository extends JpaRepository<AppLinkRequest, Long> {

    List<AppLinkRequest> findByStatusOrderByCreatedAtAscIdAsc(AppLinkRequest.Status status);

    List<AppLinkRequest> findAllByOrderByCreatedAtDescIdDesc();

    Optional<AppLinkRequest> findFirstByTelegramUserIdAndStatusOrderByIdDesc(Long telegramUserId,
                                                                            AppLinkRequest.Status status);
}
