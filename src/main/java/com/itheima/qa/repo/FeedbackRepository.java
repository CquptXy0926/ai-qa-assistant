package com.itheima.qa.repo;

import com.itheima.qa.model.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    Optional<Feedback> findByMessageId(Long messageId);
}
