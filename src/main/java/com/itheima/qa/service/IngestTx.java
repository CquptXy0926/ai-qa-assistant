package com.itheima.qa.service;

import com.itheima.qa.model.DocChunk;
import com.itheima.qa.model.Document;
import com.itheima.qa.rag.Bm25Index;
import com.itheima.qa.repo.ChunkRepository;
import com.itheima.qa.repo.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 摄取流水线的事务步骤（独立 Bean，避免 IngestService 内部自调用导致 @Transactional 失效）。
 */
@Service
public class IngestTx {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final Bm25Index index;

    public IngestTx(ChunkRepository chunkRepository, DocumentRepository documentRepository, Bm25Index index) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.index = index;
    }

    @Transactional
    public void saveChunksAndReindex(Document doc, List<DocChunk> chunks) {
        chunkRepository.deleteByDocumentId(doc.getId());
        chunkRepository.saveAll(chunks);
        doc.setStatus(Document.Status.READY);
        doc.setErrorMsg(null);
        documentRepository.save(doc);
        List<DocChunk> all = chunkRepository.findEnabledByKbIds(List.of(doc.getKnowledgeBase().getId()));
        index.rebuild(doc.getKnowledgeBase().getId(), all);
    }
}
