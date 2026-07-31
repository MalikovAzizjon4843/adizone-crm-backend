package com.crm.repository;

import com.crm.entity.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoticeReadRepository extends JpaRepository<NoticeRead, Long> {

    boolean existsByNoticeIdAndUserId(Long noticeId, Long userId);

    List<NoticeRead> findByUserId(Long userId);

    @Query("SELECT nr.notice.id FROM NoticeRead nr WHERE nr.user.id = :userId")
    List<Long> findReadNoticeIdsByUser(@Param("userId") Long userId);
}
