package com.crm.repository;

import com.crm.entity.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * E'lon o'chirilishidan oldin uning o'qilganlik yozuvlarini tozalaydi.
     * notice_reads.notice_id NOT NULL FK — avval bularsiz e'lonni o'chirib bo'lmaydi.
     */
    @Modifying
    @Query("DELETE FROM NoticeRead nr WHERE nr.notice.id = :noticeId")
    int deleteByNoticeId(@Param("noticeId") Long noticeId);
}
