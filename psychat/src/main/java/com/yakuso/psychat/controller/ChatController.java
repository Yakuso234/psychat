package com.yakuso.psychat.controller;

import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.dto.ChatRequest;
import com.yakuso.psychat.entity.ChatMessage;
import com.yakuso.psychat.mapper.ChatMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yakuso.psychat.service.ChatService;
import com.yakuso.psychat.service.EmbeddingService;
import com.yakuso.psychat.service.KnowledgeSeeder;
import com.yakuso.psychat.service.KnowledgeService;
import com.yakuso.psychat.service.MemoryService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatMessageMapper messageMapper;
    private final MemoryService memoryService;
    private final KnowledgeService knowledgeService;
    private final KnowledgeSeeder knowledgeSeeder;
    private final EmbeddingService embeddingService;

    public ChatController(ChatService chatService, ChatMessageMapper messageMapper,
                          MemoryService memoryService, KnowledgeService knowledgeService,
                          KnowledgeSeeder knowledgeSeeder, EmbeddingService embeddingService) {
        this.chatService = chatService;
        this.messageMapper = messageMapper;
        this.memoryService = memoryService;
        this.knowledgeService = knowledgeService;
        this.knowledgeSeeder = knowledgeSeeder;
        this.embeddingService = embeddingService;
    }

    @PostMapping("/send")
    public SseEmitter sendMessage(@RequestBody ChatRequest req) {
        Long userId = AuthContext.getUserId();

        if (userId == null) {
            SseEmitter emitter = new SseEmitter(5000L);
            try {
                emitter.send(SseEmitter.event().data("[ERROR] 请先登录"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        try {
            String sessionId = req.getSessionId() != null ? req.getSessionId() : "default";
            return chatService.chat(userId, sessionId, req.getMessage());
        } catch (Exception e) {
            SseEmitter emitter = new SseEmitter(5000L);
            try {
                emitter.send(SseEmitter.event().data("[ERROR] " + e.getMessage()));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
    }

    @GetMapping("/history")
    public Result<List<ChatMessage>> history(@RequestParam(defaultValue = "default") String sessionId) {
        Long userId = AuthContext.getUserId();
        List<ChatMessage> list = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
        return Result.ok(list);
    }

    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions() {
        Long userId = AuthContext.getUserId();
        List<ChatMessage> all = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
                        .orderByDesc(ChatMessage::getCreatedAt)
        );

        // dedupe by session_id, keep first message as preview
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (ChatMessage msg : all) {
            map.putIfAbsent(msg.getSessionId(), new HashMap<>());
            Map<String, Object> entry = map.get(msg.getSessionId());
            entry.putIfAbsent("sessionId", msg.getSessionId());
            entry.putIfAbsent("createdAt", msg.getCreatedAt() != null
                    ? msg.getCreatedAt().toString().substring(0, 16) : "");
            if (msg.getRole().equals("USER") && !entry.containsKey("preview")) {
                String preview = msg.getContent();
                if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
                entry.put("preview", preview);
            }
        }

        return Result.ok(new ArrayList<>(map.values()));
    }

    @DeleteMapping("/session/{sessionId}")
    public Result<String> deleteSession(@PathVariable String sessionId) {
        Long userId = AuthContext.getUserId();
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getSessionId, sessionId));
        return Result.ok("会话已删除");
    }

    @GetMapping("/memory/list")
    public Result<List<String>> listMemories(@RequestParam(defaultValue = "50") int limit) {
        Long userId = AuthContext.getUserId();
        return Result.ok(memoryService.listAll(userId, limit));
    }

    @DeleteMapping("/memory")
    public Result<String> clearMemory() {
        Long userId = AuthContext.getUserId();
        chatService.clearUserMemory(userId);
        return Result.ok("记忆已清除");
    }

    @DeleteMapping("/knowledge")
    public Result<String> clearKnowledge() {
        knowledgeService.clearAll();
        knowledgeSeeder.forceReSeed();
        return Result.ok("知识库已清除并重新播种，查看启动日志确认条数");
    }

    @PostMapping("/seed-test-memories")
    public Result<String> seedTestMemories() {
        Long userId = AuthContext.getUserId();
        long now = System.currentTimeMillis() / 1000;
        long day = 86400;

        String[] memories = {
                "用户提到最近工作压力很大，经常加班到很晚，感觉身心疲惫",
                "用户分享了一次与家人的争吵，感到委屈和不被理解",
                "用户说最近睡眠质量不好，入睡困难，脑子里总是想很多事情",
                "用户回忆起大学时期的一段经历，当时觉得很迷茫不知道该怎么选择",
                "用户提到童年时养的宠物去世的经历，那是第一次面对失去",
                "用户说今天被领导表扬了，但自己觉得不配，怀疑是运气好",
                "用户觉得在社交场合总是紧张，不知道说什么，结束后会反复回想",
                "用户说想学吉他但一直拖延没开始，觉得自己做什么都坚持不下来"
        };
        long[] ages = { 2, 5, 9, 18, 24, 35, 65, 120 }; // days ago

        int stored = 0;
        for (int i = 0; i < memories.length; i++) {
            List<Float> emb = embeddingService.embed(memories[i]);
            if (emb == null) continue;
            long ts = now - ages[i] * day;
            memoryService.storeWithTimestamp(userId, memories[i], emb, ts);
            stored++;
        }

        return Result.ok("已播种 " + stored + "/" + memories.length + " 条测试记忆，时间范围 2-120 天前");
    }
}
