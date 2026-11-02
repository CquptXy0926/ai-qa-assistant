package com.itheima.qa.repo;

import com.itheima.qa.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KbRepository extends JpaRepository<KnowledgeBase, Long> {
    Optional<KnowledgeBase> findByName(String name);
}
