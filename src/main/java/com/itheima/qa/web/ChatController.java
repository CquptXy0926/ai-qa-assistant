package com.itheima.qa.web;

import com.itheima.qa.model.Conversation;
import com.itheima.qa.model.Feedback;
import com.itheima.qa.model.Message;
import com.itheima.qa.model.User;
import com.itheima.qa.repo.ConversationRepository;
import com.itheima.qa.repo.FeedbackRepository;
import com.itheima.qa.repo.MessageRepository;
import com.itheima.qa.service.ChatService;
import com.itheima.qa.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 问答与会话管理。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    public record ChatReq(Long conversationId, List<Long> kbIds, String question) {}

    public record CreateConvReq(List<Long> kbIds) {}

    public record FeedbackReq(int rating, List<String> reasons) {}

    private final ChatService chatService;
    private final ConversationRepository convRepository;
    private final MessageRepository msgRepository;
    private final FeedbackRepository fbRepository;

    public ChatController(ChatService chatService, ConversationRepository convRepository,
                          MessageRepository msgRepository, FeedbackRepository fbRepository) {
        this.chatService = chatService;
        this.convRepository = convRepository;
        this.msgRepository = msgRepository;
        this.fbRepository = fbRepository;
    }

    private User user(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(UserService.SESSION_USER);
    }

    /** 问答（SSE 流） */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_STREAM_JSON_VALUE)
    public SseEmitter chat(@RequestBody ChatReq req, HttpServletRequest request) {
        if (req.question() == null || req.question().isBlank()) {
            throw new AuthController.HttpStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        return chatService.chat(user(request), req.conversationId(), req.kbIds(), req.question().trim());
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(HttpServletRequest request) {
        return convRepository.findByUserIdOrderByCreatedAtDesc(user(request).getId()).stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "title", c.getTitle(),
                        "kbIds", c.getKbIds() == null ? "" : c.getKbIds(),
                        "createdAt", c.getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/conversations")
    public Map<String, Object> create(@RequestBody(required = false) CreateConvReq req,
                                      HttpServletRequest request) {
        Conversation conv = chatService.ensureConversation(user(request), null,
                req == null ? null : req.kbIds());
        return Map.of("id", conv.getId(), "title", conv.getTitle());
    }

    @GetMapping("/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> messages(@PathVariable Long id, HttpServletRequest request) {
        Conversation conv = ownConv(id, request);
        List<Feedback> fbs = fbRepository.findAll();
        return msgRepository.findByConversationIdOrderByIdAsc(conv.getId()).stream()
                .map(m -> {
                    var fb = fbs.stream().filter(f -> f.getMessageId().equals(m.getId())).findFirst();
                    Object cites;
                    try {
                        cites = m.getCitations() == null ? List.of()
                                : new com.fasterxml.jackson.databind.ObjectMapper().readValue(m.getCitations(), java.util.List.class);
                    } catch (Exception e) {
                        cites = List.of();
                    }
                    return Map.<String, Object>of(
                            "id", m.getId(), "role", m.getRole(), "content", m.getContent(),
                            "citations", cites,
                            "hit", m.getHit() == null ? true : m.getHit(),
                            "rating", fb.map(Feedback::getRating).orElse(0));
                })
                .toList();
    }

    @PutMapping("/conversations/{id}")
    public Map<String, Object> rename(@PathVariable Long id, @RequestBody Map<String, String> body,
                                      HttpServletRequest request) throws Exception {
        Conversation conv = ownConv(id, request);
        String title = body.get("title");
        if (title != null && !title.isBlank()) {
            conv.setTitle(title.length() > 50 ? title.substring(0, 50) : title.trim());
        }
        if (body.containsKey("kbIds")) {
            conv.setKbIds(body.get("kbIds"));
        }
        convRepository.save(conv);
        return Map.of("ok", true);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id, HttpServletRequest request) throws Exception {
        Conversation conv = ownConv(id, request);
        msgRepository.findByConversationIdOrderByIdAsc(conv.getId())
                .forEach(m -> fbRepository.findByMessageId(m.getId()).ifPresent(fbRepository::delete));
        msgRepository.deleteByConversationId(conv.getId());
        convRepository.delete(conv);
    }

    /** 答案反馈：rating=1 有用 / -1 没用（可带原因）；重复提交覆盖 */
    @PostMapping("/messages/{msgId}/feedback")
    public Map<String, Object> feedback(@PathVariable Long msgId, @RequestBody FeedbackReq req,
                                        HttpServletRequest request) throws Exception {
        Message msg = msgRepository.findById(msgId)
                .orElseThrow(() -> new AuthController.HttpStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
        ownConv(msg.getConversationId(), request);
        if (req.rating() != 1 && req.rating() != -1) {
            throw new AuthController.HttpStatusException(HttpStatus.BAD_REQUEST, "rating 只能为 1 或 -1");
        }
        Feedback fb = fbRepository.findByMessageId(msgId).orElseGet(Feedback::new);
        fb.setMessageId(msgId);
        fb.setRating(req.rating());
        fb.setReasons(req.reasons() == null ? "" : String.join(",", req.reasons()));
        fbRepository.save(fb);
        return Map.of("ok", true);
    }

    private Conversation ownConv(Long id, HttpServletRequest request) {
        Conversation conv = convRepository.findById(id)
                .orElseThrow(() -> new AuthController.HttpStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!conv.getUserId().equals(user(request).getId())) {
            throw new AuthController.HttpStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        return conv;
    }
}
