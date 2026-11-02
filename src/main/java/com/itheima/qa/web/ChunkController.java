package com.itheima.qa.web;

import com.itheima.qa.repo.ChunkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 切片预览：问答页引用卡片展开原文，登录用户均可访问。
 */
@RestController
@RequestMapping("/api/chunks")
public class ChunkController {

    private final ChunkRepository chunkRepository;

    public ChunkController(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @GetMapping("/{chunkId}")
    @Transactional(readOnly = true)
    public Map<String, Object> chunk(@PathVariable Long chunkId) {
        var c = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new AuthController.HttpStatusException(HttpStatus.NOT_FOUND, "切片不存在"));
        return Map.of("docId", c.getDocument().getId(), "fileName", c.getDocument().getFileName(),
                "location", c.getLocation() == null ? "" : c.getLocation(), "content", c.getContent());
    }
}
