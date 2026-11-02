package com.itheima.qa.web;

import com.itheima.qa.model.KnowledgeBase;
import com.itheima.qa.model.User;
import com.itheima.qa.repo.DocumentRepository;
import com.itheima.qa.repo.KbRepository;
import com.itheima.qa.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理：GET 对所有登录用户开放（用于选择提问范围），写操作仅 ADMIN（拦截器控制）。
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    public record KbReq(@NotBlank String name, String description, String visibility) {}

    private final KbRepository kbRepository;
    private final DocumentRepository documentRepository;

    public KbController(KbRepository kbRepository, DocumentRepository documentRepository) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        User user = (User) request.getSession().getAttribute(UserService.SESSION_USER);
        return kbRepository.findAll().stream()
                .filter(kb -> user.isAdmin() || !"ADMIN_ONLY".equals(kb.getVisibility()))
                .map(kb -> Map.<String, Object>of(
                        "id", kb.getId(), "name", kb.getName(),
                        "description", kb.getDescription() == null ? "" : kb.getDescription(),
                        "visibility", kb.getVisibility(),
                        "docCount", documentRepository.countByKnowledgeBaseIdAndEnabledTrue(kb.getId())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody @Valid KbReq req) {
        if (kbRepository.findByName(req.name()).isPresent()) {
            throw new AuthController.HttpStatusException(HttpStatus.CONFLICT, "知识库名称已存在");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.name().trim());
        kb.setDescription(req.description());
        kb.setVisibility("ADMIN_ONLY".equals(req.visibility()) ? "ADMIN_ONLY" : "ALL");
        kbRepository.save(kb);
        return Map.of("id", kb.getId(), "name", kb.getName());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody @Valid KbReq req) {
        KnowledgeBase kb = kbRepository.findById(id)
                .orElseThrow(() -> new AuthController.HttpStatusException(HttpStatus.NOT_FOUND, "知识库不存在"));
        kb.setName(req.name().trim());
        kb.setDescription(req.description());
        if (req.visibility() != null) {
            kb.setVisibility("ADMIN_ONLY".equals(req.visibility()) ? "ADMIN_ONLY" : "ALL");
        }
        kbRepository.save(kb);
        return Map.of("id", kb.getId(), "name", kb.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        kbRepository.deleteById(id);
    }
}
