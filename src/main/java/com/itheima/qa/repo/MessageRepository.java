package com.itheima.qa.repo;

import com.itheima.qa.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByIdAsc(Long conversationId);

    @Modifying
    @Query("delete from Message m where m.conversationId = ?1")
    void deleteByConversationId(Long conversationId);

    @Query("select m from Message m where m.role = 'assistant' and m.createdAt >= ?1")
    List<Message> findAssistantSince(LocalDateTime since);

    @Query("select m from Message m where m.role = 'assistant' and m.hit = false order by m.id desc")
    List<Message> findMissed();
}
