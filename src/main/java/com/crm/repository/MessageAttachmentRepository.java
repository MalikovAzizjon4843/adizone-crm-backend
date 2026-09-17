package com.crm.repository;

import com.crm.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {

    /**
     * Bir sahifadagi barcha xabarlarning biriktirmalari — bitta so'rovda.
     *
     * <p>Shu sababli {@code Message} da {@code @OneToMany} yo'q: kolleksiya
     * bilan {@code JOIN FETCH} sahifalashni buzadi, kolleksiyasiz esa har
     * bir xabar alohida so'rov yuborardi.
     */
    @Query("""
        SELECT a FROM MessageAttachment a
        WHERE a.message.id IN :messageIds
        ORDER BY a.message.id ASC, a.sortOrder ASC, a.id ASC
        """)
    List<MessageAttachment> findByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
