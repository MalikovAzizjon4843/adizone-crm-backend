package com.crm.repository;

import com.crm.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * DIRECT suhbatni juftlik kaliti bo'yicha topadi — UNIQUE ustun,
     * ya'ni bitta indeks o'qishi. {@code Conversation.directKeyOf} bilan
     * yasalgan kalit kutiladi.
     */
    Optional<Conversation> findByDirectKey(String directKey);
}
