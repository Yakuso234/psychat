package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final EmbeddingService embeddingService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    private static final String SUMMARY_PROMPT = """
        请用1-2句话总结以下对话的关键信息。

        对话：
        %s

        返回格式（严格JSON，不要其他文字）：
        {"summary":"用户因考试压力失眠，AI共情倾听并引导放松技巧"}

        注意：summary不超过60字。""";

    public SummaryService(EmbeddingService embeddingService,
                          MemoryService memoryService,
                          ObjectMapper objectMapper,
                          ChatClient chatClient) {
        this.embeddingService = embeddingService;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    @Async
    public void generateAndStore(Long userId, String userMessage, String aiReply) {
        try {
            String prompt = String.format(SUMMARY_PROMPT,
                    "用户：" + userMessage + "\nAI：" + aiReply);

            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (content == null) return;

            content = content.trim();
            if (content.startsWith("```")) {
                content = content.substring(content.indexOf('\n') + 1);
                if (content.endsWith("```")) {
                    content = content.substring(0, content.lastIndexOf("```")).trim();
                }
            }

            JsonNode node = objectMapper.readTree(content);
            String summary = node.has("summary") ? node.get("summary").asText().trim() : "";

            if (summary.isEmpty()) return;

            List<Float> embedding = embeddingService.embed(summary);
            if (embedding != null) {
                memoryService.store(userId, summary, embedding);
                log.debug("Summary stored to Milvus for user {}", userId);
            }
        } catch (Exception e) {
            log.warn("Summary generation failed: {}", e.getMessage());
        }
    }
}
