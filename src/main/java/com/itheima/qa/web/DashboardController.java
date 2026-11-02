package com.itheima.qa.web;

import com.itheima.qa.model.Feedback;
import com.itheima.qa.model.Message;
import com.itheima.qa.repo.FeedbackRepository;
import com.itheima.qa.repo.MessageRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营看板（仅 ADMIN）：提问量、命中率、采纳率、点踩 BadCase、近 7 日趋势。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final MessageRepository msgRepository;
    private final FeedbackRepository fbRepository;

    public DashboardController(MessageRepository msgRepository, FeedbackRepository fbRepository) {
        this.msgRepository = msgRepository;
        this.fbRepository = fbRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        LocalDateTime since7d = LocalDate.now().minusDays(6).atStartOfDay();
        List<Message> recent = msgRepository.findAssistantSince(since7d);
        long total7d = recent.size();
        long hit7d = recent.stream().filter(m -> Boolean.TRUE.equals(m.getHit())).count();

        List<Feedback> allFb = fbRepository.findAll();
        long up = allFb.stream().filter(f -> f.getRating() == 1).count();
        long down = allFb.stream().filter(f -> f.getRating() == -1).count();
        long rated = up + down;

        // 近 7 日每日提问量
        Map<String, Long> daily = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            daily.put(LocalDate.now().minusDays(i).toString(), 0L);
        }
        for (Message m : recent) {
            String day = m.getCreatedAt().toLocalDate().toString();
            daily.merge(day, 1L, Long::sum);
        }

        // BadCase：未命中知识库 + 被点踩的答案
        List<Message> missed = msgRepository.findMissed();
        List<Map<String, Object>> badCases = new ArrayList<>();
        for (Message m : missed.stream().limit(20).toList()) {
            badCases.add(caseOf(m, "知识库未命中"));
        }
        for (Feedback f : allFb.stream().filter(x -> x.getRating() == -1).limit(20).toList()) {
            msgRepository.findById(f.getMessageId()).ifPresent(m -> badCases.add(caseOf(m,
                    "用户点踩" + (f.getReasons() == null || f.getReasons().isBlank() ? "" : "：" + f.getReasons()))));
        }

        return Map.of(
                "total7d", total7d,
                "hitRate7d", total7d == 0 ? 0 : Math.round(hit7d * 1000.0 / total7d) / 10.0,
                "adoptRate", rated == 0 ? 0 : Math.round(up * 1000.0 / rated) / 10.0,
                "upCount", up, "downCount", down,
                "daily", daily,
                "badCases", badCases);
    }

    private Map<String, Object> caseOf(Message answer, String reason) {
        String question = msgRepository.findByConversationIdOrderByIdAsc(answer.getConversationId())
                .stream().filter(m -> m.getId() < answer.getId() && "user".equals(m.getRole()))
                .reduce((a, b) -> b).map(Message::getContent).orElse("");
        return Map.of("answerId", answer.getId(), "question", question,
                "answer", answer.getContent().length() > 200 ? answer.getContent().substring(0, 200) + "..." : answer.getContent(),
                "reason", reason, "createdAt", answer.getCreatedAt().toString());
    }
}
