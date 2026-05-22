package com.yakuso.psychat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yakuso.psychat.entity.ChatMessage;
import com.yakuso.psychat.entity.EmotionEvent;

import com.yakuso.psychat.entity.UserFact;
import com.yakuso.psychat.entity.UserPreference;
import com.yakuso.psychat.mapper.ChatMessageMapper;
import com.yakuso.psychat.mapper.EmotionEventMapper;

import com.yakuso.psychat.mapper.UserFactMapper;
import com.yakuso.psychat.config.ToolRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String CONTEXT_KEY_PREFIX = "chat:context:";
    private static final int MAX_HISTORY = 20;
    private static final Duration CONTEXT_TTL = Duration.ofHours(24);

    private final ChatMessageMapper messageMapper;
    private final EmbeddingService embeddingService;
    private final MemoryService memoryService;
    private final FactExtractionService factExtractionService;
    private final SummaryService summaryService;
    private final EmotionAnalysisService emotionAnalysisService;
    private final UserPreferenceService preferenceService;
    private final UserFactMapper userFactMapper;
    private final EmotionEventMapper emotionEventMapper;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final KnowledgeService knowledgeService;
    private final LocalEmotionLexicon emotionLexicon;
    private final java.util.concurrent.Executor taskExecutor;
    private final String apiKey;
    private final String baseUrl;
    private final String modelChat;
    private final String systemPrompt;

    public ChatService(ChatMessageMapper messageMapper,
                       EmbeddingService embeddingService,
                       MemoryService memoryService,
                       FactExtractionService factExtractionService,
                       SummaryService summaryService,
                       EmotionAnalysisService emotionAnalysisService,
                       UserPreferenceService preferenceService,
                       com.yakuso.psychat.mapper.UserFactMapper userFactMapper,
                       EmotionEventMapper emotionEventMapper,

                       StringRedisTemplate redis,
                       KnowledgeService knowledgeService,
                       LocalEmotionLexicon emotionLexicon,
                       @Value("${spring.ai.openai.api-key}") String apiKey,
                       @Value("${spring.ai.openai.base-url}") String baseUrl,
                       @Value("${spring.ai.openai.chat.options.model}") String modelChat,
                       @Value("${app.ai-persona}") String systemPrompt,
                       ObjectMapper objectMapper,
                       ToolRegistry toolRegistry,
                       @org.springframework.beans.factory.annotation.Qualifier("taskExecutor") java.util.concurrent.Executor taskExecutor) {
        this.messageMapper = messageMapper;
        this.embeddingService = embeddingService;
        this.memoryService = memoryService;
        this.factExtractionService = factExtractionService;
        this.summaryService = summaryService;
        this.emotionAnalysisService = emotionAnalysisService;
        this.preferenceService = preferenceService;
        this.userFactMapper = userFactMapper;
        this.emotionEventMapper = emotionEventMapper;

        this.redis = redis;
        this.knowledgeService = knowledgeService;
        this.emotionLexicon = emotionLexicon;
        this.taskExecutor = taskExecutor;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelChat = modelChat;
        this.systemPrompt = systemPrompt;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    public SseEmitter chat(Long userId, String sessionId, String userMessage) {
        SseEmitter emitter = new SseEmitter(300_000L);

        // load short-term context from Redis
        List<Map<String, String>> history = loadContext(userId);

        // save user message to MySQL
        saveMessage(userId, sessionId, "USER", userMessage);

        CompletableFuture.runAsync(() -> {
            StringBuilder fullResponse = new StringBuilder();
            try {
                // recall similar memories from Milvus
                String memoryContext = recallMemories(userId, userMessage);

                // build messages with memory + short-term context
                List<Map<String, Object>> messages = buildMessages(history, userMessage, memoryContext, userId, sessionId);
                log.info("Calling DeepSeek API, model={}, contextSize={}, memories={}",
                        modelChat, history.size(), memoryContext.isEmpty() ? "none" : "found");

                String reply = callModelWithTools(messages, emitter, userId);
                if (!reply.isEmpty()) {
                    saveMessage(userId, sessionId, "ASSISTANT", reply);

                    // update short-term context in Redis
                    history.add(Map.of("role", "user", "content", userMessage));
                    history.add(Map.of("role", "assistant", "content", reply));
                    if (history.size() > MAX_HISTORY) {
                        history.subList(0, 2).clear();
                    }
                    saveContext(userId, history);

                    // generate summary & store to vector memory (async)
                    summaryService.generateAndStore(userId, userMessage, reply);

                    // extract structured facts asynchronously
                    factExtractionService.extractAndSave(userId, userMessage, reply);

                    // analyze emotions asynchronously
                    emotionAnalysisService.analyzeAndSave(userId, sessionId, userMessage, reply);
                }
                emitter.complete();
                log.info("Chat completed, reply length={}", reply.length());
            } catch (Exception e) {
                log.error("Chat error", e);
                try {
                    emitter.send(SseEmitter.event().data("[ERROR] " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ex) {
                    emitter.completeWithError(ex);
                }
            }
        }, taskExecutor);

        return emitter;
    }

    public void clearUserMemory(Long userId) {
        memoryService.clearMemories(userId);

        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getUserId, userId));
        userFactMapper.delete(new LambdaQueryWrapper<UserFact>()
                .eq(UserFact::getUserId, userId));
        emotionEventMapper.delete(new LambdaQueryWrapper<EmotionEvent>()
                .eq(EmotionEvent::getUserId, userId));
        redis.delete(CONTEXT_KEY_PREFIX + userId);
        log.info("User {} messages + memories + facts + emotions + context cleared", userId);
    }

    // --- Redis short-term context ---

    private List<Map<String, String>> loadContext(Long userId) {
        try {
            String key = CONTEXT_KEY_PREFIX + userId;
            String json = redis.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json,
                        new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to load context from Redis: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private void saveContext(Long userId, List<Map<String, String>> context) {
        try {
            String key = CONTEXT_KEY_PREFIX + userId;
            String json = objectMapper.writeValueAsString(context);
            redis.opsForValue().set(key, json, CONTEXT_TTL);
        } catch (Exception e) {
            log.warn("Failed to save context to Redis: {}", e.getMessage());
        }
    }

    // --- Memory ---

    private String recallMemories(Long userId, String message) {
        try {
            List<Float> embedding = embeddingService.embed(message);
            if (embedding == null) return "";

            List<String> memories = memoryService.recall(userId, embedding, 3);
            if (memories.isEmpty()) return "";

            log.info("Memory recall: {} results → {}", memories.size(),
                    memories.stream().map(m -> m.length() > 50 ? m.substring(0, 50) + "..." : m)
                            .collect(java.util.stream.Collectors.joining(" | ")));

            StringBuilder sb = new StringBuilder();
            sb.append("以下是你过去与这位用户的对话回忆（摘要），请在回复中自然地引用它们，让用户感到你记得ta：\n");
            for (int i = 0; i < memories.size(); i++) {
                sb.append("- ").append(memories.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Memory recall failed: {}", e.getMessage());
            return "";
        }
    }

    private java.util.List<java.util.Map<String, Object>> buildMessages(java.util.List<Map<String, String>> history,
                                                     String currentMsg,
                                                     String memoryContext,
                                                     Long userId,
                                                     String sessionId) {
        java.util.List<java.util.Map<String, Object>> messages = new java.util.ArrayList<>();

        String prompt = systemPrompt;

        // inject user preferences as behavior override
        String prefInstructions = buildPreferenceInstructions(userId);
        if (!prefInstructions.isEmpty()) {
            prompt = prompt + "\n\n[回复要求，优先级高于上述风格]\n" + prefInstructions;
        }

        if (!memoryContext.isEmpty()) {
            prompt = prompt + "\n\n" + memoryContext;
        }

        // inject structured facts
        String factsText = loadFactsText(userId);
        if (!factsText.isEmpty()) {
            prompt = prompt + "\n\n[以下是关于用户的已知信息，请在合适时自然引用]\n" + factsText;
        }

        // inject recent emotion context
        String emotions = loadRecentEmotions(userId, sessionId);
        if (!emotions.isEmpty()) {
            prompt = prompt + "\n\n" + emotions;
        }

        // inject session rhythm (time of day + round count)
        String rhythm = buildSessionRhythm(history.size());
        if (!rhythm.isEmpty()) {
            prompt = prompt + "\n\n" + rhythm;
        }

        // inject knowledge RAG (empathy scripts + intervention techniques + forbidden patterns)
        String knowledgeContext = buildKnowledgeContext(userId, currentMsg, sessionId);
        if (!knowledgeContext.isEmpty()) {
            prompt = prompt + "\n\n" + knowledgeContext;
        }

        messages.add((java.util.Map) Map.of("role", "system", "content", prompt));

        for (var h : history) {
            messages.add(new java.util.LinkedHashMap<>(h));
        }
        messages.add((java.util.Map) Map.of("role", "user", "content", currentMsg));
        return messages;
    }

    private String buildPreferenceInstructions(Long userId) {
        UserPreference pref = preferenceService.getByUserId(userId);
        if (pref == null) return "";

        StringBuilder sb = new StringBuilder();

        // tone style → concrete behavior
        sb.append(switch (pref.getToneStyle()) {
            case "casual" -> "回复语气轻松随意，像朋友聊天一样自然。";
            case "professional" -> "回复语气专业理性，保持客观分析。";
            case "concise" -> "回复语气简洁直接，开门见山，不铺垫。";
            default -> "回复语气温柔、富有共情，像深夜电台里的知心朋友。";
        });

        // response length → concrete constraint
        sb.append(switch (pref.getResponseLength()) {
            case "short" -> "回复控制在1-2句，不超过50字。";
            case "long" -> "回复可以适当展开，3-5句为宜。";
            default -> "回复控制在3-4句，自然分段。";
        });

        // proactive check-in
        if (pref.getAllowProactive()) {
            sb.append("可以在回复末尾关心用户或开启新话题。");
        } else {
            sb.append("不要在回复末尾追问，保持倾听者角色。");
        }

        return sb.toString();
    }

    // --- Tool calling ---

    private static final int MAX_TOOL_ROUNDS = 3;

    private String callModelWithTools(java.util.List<Map<String, Object>> messages,
                                       SseEmitter emitter, Long userId) throws Exception {
        StringBuilder fullReply = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", modelChat);
            body.put("messages", messages);
            body.put("stream", true);
            if (!toolRegistry.isEmpty()) {
                body.put("tools", toolRegistry.getDefinitions());
            }

            String requestBody = objectMapper.writeValueAsString(body);

            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            StringBuilder roundText = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<java.io.InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("DeepSeek API error: {} - {}", response.statusCode(), errorBody);
                    emitter.send(SseEmitter.event().data("[ERROR] API返回" + response.statusCode()));
                    emitter.complete();
                    return fullReply.toString();
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        JsonNode node = parseSseChunk(line);
                        if (node == null) continue;

                        String content = extractDeltaContent(node);
                        if (content != null) {
                            roundText.append(content);
                            emitter.send(SseEmitter.event().data(content));
                        }

                        String reasoning = extractDeltaReasoning(node);
                        if (reasoning != null) {
                            reasoningContent.append(reasoning);
                        }

                        accumulator.feed(node);

                        String finishReason = extractFinishReason(node);
                        if (finishReason != null) {
                            accumulator.setFinishReason(finishReason);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Streaming error: {}", e.getMessage());
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
                return fullReply.toString();
            }

            fullReply.append(roundText);
            log.info("SSE round {}: roundText={}, reasoning={}, toolCalls={}",
                    round, roundText.length(), reasoningContent.length(),
                    accumulator.toolCalls.size());

            // no tool calls → normal response, done
            if (!accumulator.hasToolCalls()) {
                break;
            }

            // execute tools and append results for next round
            log.info("Tool calls detected: {}", accumulator.toolCalls.size());
            messages.add(accumulator.buildAssistantMessage(roundText.toString(), reasoningContent.toString()));

            for (ToolCallAccumulator.ToolCall tc : accumulator.toolCalls) {
                try {
                    java.util.Map<String, Object> args = objectMapper.readValue(
                            tc.arguments.toString(), new TypeReference<java.util.Map<String, Object>>() {});
                    String result = toolRegistry.execute(tc.name, userId, args);
                    // auto-popup: strip report prefix and emit as SSE event
                    if (result.startsWith("__REPORT__:")) {
                        String reportJson = result.substring(11);
                        try {
                            emitter.send(SseEmitter.event().name("report").data(reportJson));
                        } catch (Exception ignored) {}
                        // only tool role needs the signal; AI gets a clean message
                        result = "{\"shown\":true,\"message\":\"报告已展示给用户\"}";
                    }
                    messages.add((java.util.Map) Map.of("role", "tool", "tool_call_id", tc.id, "content", result));
                    log.info("Tool executed: {} → {}", tc.name,
                            result.length() > 80 ? result.substring(0, 80) + "..." : result);
                } catch (Exception e) {
                    log.error("Tool execution failed: {}", tc.name, e);
                    messages.add((java.util.Map) Map.of("role", "tool", "tool_call_id", tc.id,
                            "content", "{\"error\":\"" + e.getMessage() + "\"}"));
                }
            }
        }

        return fullReply.toString();
    }

    private static class ToolCallAccumulator {
        final java.util.List<ToolCall> toolCalls = new java.util.ArrayList<>();
        final java.util.Map<Integer, ToolCall> byIndex = new java.util.LinkedHashMap<>();
        String finishReason;
        boolean seenToolCalls; // track if we've encountered any tool_call deltas

        static class ToolCall {
            String id;
            String name;
            StringBuilder arguments = new StringBuilder();
        }

        void feed(JsonNode chunk) {
            JsonNode delta = chunk.get("delta");
            if (delta == null) return;
            JsonNode tcs = delta.get("tool_calls");
            if (tcs == null) return;

            seenToolCalls = true;
            for (JsonNode tc : tcs) {
                int idx = tc.get("index").asInt();
                ToolCall part = byIndex.computeIfAbsent(idx, k -> {
                    ToolCall t = new ToolCall();
                    toolCalls.add(t);
                    return t;
                });
                if (tc.has("id") && !tc.get("id").isNull()) {
                    part.id = tc.get("id").asText();
                }
                JsonNode fn = tc.get("function");
                if (fn != null) {
                    if (fn.has("name") && !fn.get("name").isNull()) {
                        part.name = fn.get("name").asText();
                    }
                    if (fn.has("arguments")) {
                        part.arguments.append(fn.get("arguments").asText());
                    }
                }
            }
        }

        boolean hasToolCalls() {
            return seenToolCalls && !toolCalls.isEmpty() && "tool_calls".equals(finishReason);
        }

        void setFinishReason(String reason) {
            this.finishReason = reason;
        }

        java.util.Map<String, Object> buildAssistantMessage(String content, String reasoningContent) {
            java.util.List<java.util.Map<String, Object>> tcList = new java.util.ArrayList<>();
            for (ToolCall tc : toolCalls) {
                java.util.Map<String, Object> fn = new java.util.LinkedHashMap<>();
                fn.put("name", tc.name);
                fn.put("arguments", tc.arguments.toString());
                java.util.Map<String, Object> t = new java.util.LinkedHashMap<>();
                t.put("id", tc.id);
                t.put("type", "function");
                t.put("function", fn);
                tcList.add(t);
            }
            java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("role", "assistant");
            msg.put("content", content.isEmpty() ? null : content);
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                msg.put("reasoning_content", reasoningContent);
            }
            msg.put("tool_calls", tcList);
            return msg;
        }
    }

    private JsonNode parseSseChunk(String line) {
        String json;
        if (line.startsWith("data: ")) {
            json = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
            json = line.substring(5).trim();
        } else {
            return null;
        }
        if (json.equals("[DONE]")) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.size() > 0) {
                return choices.get(0);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDeltaContent(JsonNode choice) {
        JsonNode delta = choice.get("delta");
        if (delta == null) return null;
        JsonNode content = delta.get("content");
        if (content == null || content.isNull()) return null;
        return content.asText();
    }

    private String extractDeltaReasoning(JsonNode choice) {
        JsonNode delta = choice.get("delta");
        if (delta == null) return null;
        JsonNode reasoning = delta.get("reasoning_content");
        if (reasoning == null || reasoning.isNull()) return null;
        return reasoning.asText();
    }

    private String extractFinishReason(JsonNode choice) {
        JsonNode fr = choice.get("finish_reason");
        if (fr == null || fr.isNull()) return null;
        return fr.asText();
    }

    private String buildSessionRhythm(int historySize) {
        java.time.LocalTime now = java.time.LocalTime.now();
        int hour = now.getHour();
        String timeDesc;
        if (hour >= 23 || hour < 5) {
            timeDesc = "现在是深夜" + now.toString().substring(0, 5);
        } else if (hour < 9) {
            timeDesc = "现在是清晨" + now.toString().substring(0, 5);
        } else if (hour < 12) {
            timeDesc = "现在是上午" + now.toString().substring(0, 5);
        } else if (hour < 14) {
            timeDesc = "现在是中午" + now.toString().substring(0, 5);
        } else if (hour < 18) {
            timeDesc = "现在是下午" + now.toString().substring(0, 5);
        } else {
            timeDesc = "现在是晚上" + now.toString().substring(0, 5);
        }
        int rounds = historySize / 2 + 1;
        if (rounds <= 1) {
            return "[当前时间] " + timeDesc + "，这是对话第一轮";
        }
        return "[当前时间] " + timeDesc + "，已连续对话" + rounds + "轮";
    }

    /**
     * Analyzes the recent emotion trajectory to determine the retrieval strategy.
     * Returns: "crisis" | "escalating" | "deescalating" | "stable"
     */
    private String analyzeEmotionTrajectory(List<EmotionEvent> events) {
        if (events.isEmpty()) return "stable";

        if (events.size() == 1) {
            String label = events.get(0).getEmotionLabel();
            double intensity = events.get(0).getIntensity();
            if ("绝望".equals(label) || intensity > 0.85) return "crisis";
            if (intensity > 0.65) return "escalating";
            return "stable";
        }

        // look at last 3 events
        EmotionEvent first = events.get(0);
        EmotionEvent last = events.get(events.size() - 1);

        if ("绝望".equals(last.getEmotionLabel()) || last.getIntensity() > 0.85) {
            return "crisis";
        }

        double delta = last.getIntensity() - first.getIntensity();
        if (delta > 0.2) return "escalating";
        if (delta < -0.15) return "deescalating";
        return "stable";
    }

    private String buildKnowledgeContext(Long userId, String userMessage, String sessionId) {
        try {
            // Step 1: local lexicon pre-scan (zero latency)
            var lexiconResult = emotionLexicon.analyze(userMessage);

            // Step 2: load recent emotions for trajectory analysis
            List<EmotionEvent> recentEmotions = emotionEventMapper.selectList(
                    new LambdaQueryWrapper<EmotionEvent>()
                            .eq(EmotionEvent::getUserId, userId)
                            .eq(EmotionEvent::getSessionId, sessionId)
                            .orderByAsc(EmotionEvent::getCreatedAt)
                            .last("LIMIT 3")
            );

            // Step 3: determine retrieval mode
            String mode;
            List<String> activeTags;

            if (lexiconResult.isCrisis()) {
                mode = "crisis";
                activeTags = List.of("绝望");
            } else {
                mode = analyzeEmotionTrajectory(recentEmotions);
                // merge local lexicon tags + recent emotion labels
                var tags = new java.util.LinkedHashSet<>(lexiconResult.emotionTags());
                for (var e : recentEmotions) {
                    if (e.getEmotionLabel() != null) tags.add(e.getEmotionLabel());
                }
                activeTags = new ArrayList<>(tags);
            }

            log.info("RAG retrieval: mode={}, tags={}", mode, activeTags);

            // Step 4: retrieve from Milvus
            String categoryFilter = "crisis".equals(mode) ? "crisis" : null;
            List<Float> queryEmbedding = embeddingService.embed(userMessage);
            if (queryEmbedding == null) return "";

            // fetch more candidates for richer re-ranking
            int topK = "crisis".equals(mode) ? 2 : ("escalating".equals(mode) ? 3 : 2);
            var results = knowledgeService.retrieve(queryEmbedding, userMessage, activeTags, categoryFilter, topK + 1, mode);
            if (results.isEmpty()) return "";

            // Step 5: always ensure 1 forbidden entry
            boolean hasForbidden = results.stream()
                    .anyMatch(r -> "forbidden".equals(r.entry().category()));
            if (!hasForbidden) {
                var forbiddenResults = knowledgeService.retrieve(
                        queryEmbedding, userMessage, List.of(), "forbidden", 1, "stable");
                if (!forbiddenResults.isEmpty()) {
                    results = new ArrayList<>(results);
                    results.add(forbiddenResults.get(0));
                }
            }

            // Step 6: format into structured prompt injection
            StringBuilder sb = new StringBuilder();
            sb.append("[本轮知识指引 — 请内化以下指令，不要逐字照搬]\n");

            if ("crisis".equals(mode)) {
                sb.append("⚠️ 危机模式：用户可能有极端情绪，请严格按照以下安全协议回应。\n\n");
            }

            for (var r : results) {
                String cat = r.entry().category();
                String label = switch (cat) {
                    case "crisis" -> "🔴 安全协议";
                    case "intervention" -> "🟡 干预技巧";
                    case "empathy" -> "🟢 共情指引";
                    case "forbidden" -> "🚫 绝对禁止";
                    default -> "📋 参考";
                };
                sb.append("[").append(label).append("] ").append(r.entry().title()).append("\n");
                sb.append(r.entry().content()).append("\n\n");
            }

            log.info("RAG injected: mode={}, entries={}", mode,
                    results.stream().map(r -> String.format("[%s] %s (%.3f)",
                            r.entry().category(), r.entry().title(), r.score()))
                            .collect(java.util.stream.Collectors.joining(" | ")));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Knowledge context build failed: {}", e.getMessage());
            return "";
        }
    }

    private String loadRecentEmotions(Long userId, String sessionId) {
        List<EmotionEvent> events = emotionEventMapper.selectList(
                new LambdaQueryWrapper<EmotionEvent>()
                        .eq(EmotionEvent::getUserId, userId)
                        .eq(EmotionEvent::getSessionId, sessionId)
                        .orderByAsc(EmotionEvent::getCreatedAt)
                        .last("LIMIT 3")
        );
        if (events.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("[用户本轮情绪轨迹]\n");
        for (var e : events) {
            sb.append("- ").append(e.getEmotionLabel())
                    .append("·").append(EmotionAnalysisService.intensityToLevel(e.getIntensity()));
            if (e.getSummary() != null && !e.getSummary().isBlank()) {
                sb.append("：").append(e.getSummary());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static final java.util.Set<String> FACT_CATEGORY_WHITELIST =
            java.util.Set.of("基本信息", "情绪状态", "关注问题", "经历事件");
    private static final int FACT_PER_CATEGORY_MAX = 2;
    private static final int FACT_TOTAL_MAX = 8;
    private static final double FACT_RECENCY_HALF_LIFE_DAYS = 7.0;
    private static final double FACT_RECENCY_CLIFF_DAYS = 30.0;
    private static final double RECENCY_WEIGHT = 0.7;
    private static final double CONFIDENCE_WEIGHT = 0.3;

    public String loadFactsText(Long userId) {
        List<UserFact> facts = userFactMapper.selectList(
                new LambdaQueryWrapper<UserFact>()
                        .eq(UserFact::getUserId, userId)
                        .eq(UserFact::getStatus, "active")
        );

        if (facts.isEmpty()) return "";

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        record ScoredFact(UserFact fact, double score) {}

        List<UserFact> scored = facts.stream()
                .filter(f -> FACT_CATEGORY_WHITELIST.contains(f.getCategory()))
                .map(f -> new ScoredFact(f, computeScore(f, now)))
                .filter(sf -> sf.score > 0)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .map(ScoredFact::fact)
                .collect(java.util.stream.Collectors.toList());

        // per-category cap
        java.util.Map<String, Integer> catCounts = new java.util.LinkedHashMap<>();
        java.util.List<UserFact> result = new java.util.ArrayList<>();
        for (var f : scored) {
            int count = catCounts.getOrDefault(f.getCategory(), 0);
            if (count >= FACT_PER_CATEGORY_MAX) continue;
            catCounts.put(f.getCategory(), count + 1);
            result.add(f);
            if (result.size() >= FACT_TOTAL_MAX) break;
        }

        StringBuilder sb = new StringBuilder();
        for (var f : result) {
            sb.append("- [").append(f.getCategory()).append("] ").append(f.getFactContent()).append("\n");
        }
        return sb.toString();
    }

    private double computeScore(UserFact fact, java.time.LocalDateTime now) {
        long daysAgo = java.time.Duration.between(fact.getCreatedAt(), now).toDays();
        if (daysAgo < 0) daysAgo = 0;
        double recency;
        if (daysAgo >= FACT_RECENCY_CLIFF_DAYS) {
            recency = 0;
        } else {
            recency = Math.exp(-Math.log(2) * daysAgo / FACT_RECENCY_HALF_LIFE_DAYS);
        }
        double confidence = fact.getConfidence() != null ? fact.getConfidence() : 0.7;
        return RECENCY_WEIGHT * recency + CONFIDENCE_WEIGHT * confidence;
    }



    private void saveMessage(Long userId, String sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setSessionId(sessionId != null ? sessionId : "default");
        msg.setRole(role);
        msg.setContent(content);
        messageMapper.insert(msg);
    }
}
