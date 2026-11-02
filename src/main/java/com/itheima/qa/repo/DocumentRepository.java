package com.itheima.qa.repo;

import com.itheima.qa.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKnowledgeBaseIdOrderByCreatedAtDesc(Long kbId);

    Optional<Document> findFirstByKnowledgeBaseIdAndFileNameAndEnabledTrue(Long kbId, String fileName);

    List<Document> findByStatusIn(List<Document.Status> statuses);

    long countByKnowledgeBaseIdAndEnabledTrue(Long kbId);
}
