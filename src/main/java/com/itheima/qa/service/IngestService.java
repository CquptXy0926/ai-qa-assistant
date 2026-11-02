package com.itheima.qa.service;

import com.itheima.qa.model.DocChunk;
import com.itheima.qa.model.Document;
import com.itheima.qa.model.KnowledgeBase;
import com.itheima.qa.rag.Bm25Index;
import com.itheima.qa.rag.Chunker;
import com.itheima.qa.rag.DocumentParser;
import com.itheima.qa.repo.ChunkRepository;
import com.itheima.qa.repo.DocumentRepository;
import com.itheima.qa.repo.KbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 文档摄取流水线：上传 → （异步）解析 → 切分 → 入库 → 重建该知识库 BM25 索引。
 * 同名文档重新上传视为新版本：旧版本自动停用（数据保留，可回滚启用）。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final KbRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final FileStorageService fileStorage;
    private final Chunker chunker;
    private final Bm25Index index;
    private final Executor ingestExecutor;
    private final IngestTx ingestTx;
    /** 同一文档同时只允许一个摄取任务，避免并发重入产生重复切片 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public IngestService(KbRepository kbRepository, DocumentRepository documentRepository,
                         ChunkRepository chunkRepository, FileStorageService fileStorage,
                         Chunker chunker, Bm25Index index, Executor ingestExecutor,
                         IngestTx ingestTx) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.fileStorage = fileStorage;
        this.chunker = chunker;
        this.index = index;
        this.ingestExecutor = ingestExecutor;
        this.ingestTx = ingestTx;
    }

    /** 上传文档，立即返回 PENDING 状态，解析在后台线程执行 */
    @Transactional
    public Document upload(Long kbId, String fileName, byte[] data) throws IOException {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + kbId));
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "txt";
        if (List.of("doc", "xls", "ppt").contains(ext)) {
            throw new IOException("暂不支持旧版 Office 格式（." + ext + "），请另存为 docx/xlsx/pptx 后上传");
        }

        // 同名文档 → 旧版本停用，新版本号 +1
        int newVersion = 1;
        var old = documentRepository.findFirstByKnowledgeBaseIdAndFileNameAndEnabledTrue(kbId, fileName);
        if (old.isPresent()) {
            Document o = old.get();
            o.setEnabled(false);
            documentRepository.save(o);
            newVersion = o.getVersion() + 1;
        }

        String storagePath = fileStorage.save(kbId, fileName, data);
        Document doc = new Document();
        doc.setKnowledgeBase(kb);
        doc.setFileName(fileName);
        doc.setStoragePath(storagePath);
        doc.setFileType(ext);
        doc.setVersion(newVersion);
        doc.setStatus(Document.Status.PENDING);
        documentRepository.save(doc);

        Long docId = doc.getId();
        ingestExecutor.execute(() -> ingest(docId));
        return doc;
    }

    /** 后台解析流水线（独立于请求事务） */
    void ingest(Long docId) {
        if (!inFlight.add(docId)) {
            log.info("摄取任务已在运行，跳过: id={}", docId);
            return;
        }
        try {
            doIngest(docId);
        } finally {
            inFlight.remove(docId);
        }
    }

    private void doIngest(Long docId) {
        Document doc = findByIdWithRetry(docId);
        if (doc == null) {
            log.warn("文档不存在（重试后仍未找到）: id={}", docId);
            return;
        }
        doc.setStatus(Document.Status.PROCESSING);
        documentRepository.save(doc);
        try {
            byte[] data = fileStorage.read(doc.getStoragePath());
            var segments = DocumentParser.parse(doc.getFileName(), data);
            List<DocChunk> chunks = chunker.chunk(doc, segments);
            ingestTx.saveChunksAndReindex(doc, chunks);
            log.info("文档解析完成: id={} file={} chunks={}", docId, doc.getFileName(), chunks.size());
        } catch (Exception e) {
            doc.setStatus(Document.Status.FAILED);
            doc.setErrorMsg(truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 500));
            documentRepository.save(doc);
            log.warn("文档解析失败: id={} file={} err={}", docId, doc.getFileName(), e.toString());
        }
    }

    /** 上传事务可能尚未提交，重试等待文档可见 */
    private Document findByIdWithRetry(Long docId) {
        for (int i = 0; i < 20; i++) {
            var found = documentRepository.findById(docId);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 重新向量化（重建该文档切片与索引） */
    public void reindex(Long docId) {
        ingestExecutor.execute(() -> ingest(docId));
    }

    /** 启用/停用文档，停用后不参与检索 */
    @Transactional
    public void toggle(Long docId, boolean enabled) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        doc.setEnabled(enabled);
        documentRepository.save(doc);
        rebuildIndex(doc.getKnowledgeBase().getId());
    }

    @Transactional
    public void delete(Long docId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        Long kbId = doc.getKnowledgeBase().getId();
        chunkRepository.deleteByDocumentId(docId);
        documentRepository.delete(doc);
        rebuildIndex(kbId);
    }

    /** 重建某知识库的内存索引 */
    @Transactional(readOnly = true)
    public void rebuildIndex(Long kbId) {
        List<DocChunk> chunks = chunkRepository.findEnabledByKbIds(List.of(kbId));
        index.rebuild(kbId, chunks);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDocsDto(Long kbId) {
        return documentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId).stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.getId(),
                        "fileName", d.getFileName(),
                        "fileType", d.getFileType(),
                        "version", d.getVersion(),
                        "status", d.getStatus().name(),
                        "enabled", d.isEnabled(),
                        "errorMsg", d.getErrorMsg() == null ? "" : d.getErrorMsg(),
                        "createdAt", d.getCreatedAt().toString(),
                        "kbId", d.getKnowledgeBase().getId(),
                        "kbName", d.getKnowledgeBase().getName()))
                .toList();
    }

    /** 启动时全量重建索引 + 崩溃恢复，由 StartupIndexer 在事务内调用 */
    @Transactional(readOnly = true)
    public void rebuildAllOnStartup() {
        List<Document> pending = documentRepository.findByStatusIn(
                List.of(Document.Status.PENDING, Document.Status.PROCESSING));
        for (KnowledgeBase kb : kbRepository.findAll()) {
            rebuildIndex(kb.getId());
        }
        pending.forEach(d -> ingestExecutor.execute(() -> ingest(d.getId())));
        log.info("BM25 索引重建完成，知识库数={}", kbRepository.count());
    }

    /**
     * 启动时用新 Chunker 重新解析所有已就绪文档（一次性迁移）。
     * 解决场景：Chunker 逻辑变更后，旧 chunk 结构不匹配新检索逻辑，需重新切分。
     */
    @Transactional(readOnly = true)
    public void reparseAllReadyOnStartup() {
        List<Document> ready = documentRepository.findByStatusIn(
                List.of(Document.Status.READY));
        if (ready.isEmpty()) {
            return;
        }
        log.info("检测到 {} 个已就绪文档，开始用新 Chunker 重新解析…", ready.size());
        ready.forEach(d -> ingestExecutor.execute(() -> ingest(d.getId())));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
