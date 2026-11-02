package com.itheima.qa.repo;

import com.itheima.qa.model.DocChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChunkRepository extends JpaRepository<DocChunk, Long> {

    /** 检索索引构建用：取指定知识库下所有启用文档的切片 */
    @Query("select c from DocChunk c join c.document d where d.knowledgeBase.id in ?1 and d.enabled = true")
    List<DocChunk> findEnabledByKbIds(List<Long> kbIds);

    List<DocChunk> findByDocumentIdOrderByChunkIndex(Long docId);

    List<DocChunk> findByDocumentIdIn(List<Long> docIds);

    void deleteByDocumentId(Long docId);
}
