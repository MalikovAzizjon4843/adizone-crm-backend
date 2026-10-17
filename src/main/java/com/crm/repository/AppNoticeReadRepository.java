package com.crm.repository;

import com.crm.entity.AppNoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AppNoticeReadRepository extends JpaRepository<AppNoticeRead, Long> {

    boolean existsByNoticeIdAndIdentityId(Long noticeId, Long identityId);

    @Query(value = """
        SELECT r.noticeId FROM AppNoticeRead r WHERE r.identityId = :identityId AND r.noticeId IN :noticeIds
        """)
    List<Long> findReadNoticeIds(@Param(value = "identityId") Long identityId,
                                 @Param(value = "noticeIds") Collection<Long> noticeIds);

    @Modifying
    @Query(value = "DELETE FROM AppNoticeRead r WHERE r.noticeId = :noticeId")
    int deleteByNoticeId(@Param(value = "noticeId") Long noticeId);
}
