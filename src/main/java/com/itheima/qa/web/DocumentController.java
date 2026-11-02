package com.itheima.qa.web;

import com.itheima.qa.model.Document;
import com.itheima.qa.service.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档管理（仅 ADMIN，由拦截器控制）：上传、列表、启停、重索引、删除、切片预览。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;

    public DocumentController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam Long kbId,
                                                      @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new AuthController.HttpStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        Document doc = ingestService.upload(kbId, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(doc));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam Long kbId) {
        // 列表接口在 /api/documents 下，同样仅管理员使用；DTO 映射在事务内完成，避免懒加载异常
        return ingestService.listDocsDto(kbId);
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        ingestService.toggle(id, enabled);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable Long id) {
        ingestService.reindex(id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        ingestService.delete(id);
    }

    static Map<String, Object> toDto(Document d) {
        return Map.of(
                "id", d.getId(),
                "fileName", d.getFileName(),
                "fileType", d.getFileType(),
                "version", d.getVersion(),
                "status", d.getStatus().name(),
                "enabled", d.isEnabled(),
                "errorMsg", d.getErrorMsg() == null ? "" : d.getErrorMsg(),
                "createdAt", d.getCreatedAt().toString(),
                "kbId", d.getKnowledgeBase().getId(),
                "kbName", d.getKnowledgeBase().getName());
    }
}
