package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yakuso.psychat.entity.UserFact;
import com.yakuso.psychat.mapper.UserFactMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FactExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FactExtractionService.class);

    private final UserFactMapper factMapper;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    private static final String EXTRACT_PROMPT = """
        请从以下对话中提取关于用户的结构化个人信息，以JSON格式返回。
        只提取确定的、明确的信息，不要推测。如果本轮没有新的值得记录的信息，返回空数组。

        分类标准：
        - 基本信息：姓名、年龄、身份、职业、学校等
        - 情绪状态：当前情绪、心理状况、压力来源
        - 关注问题：经常提到的问题、困扰、烦恼
        - 经历事件：重要经历、正在经历的事件、重大变故

        返回格式（严格JSON，不要其他文字）：
        {"facts":[{"category":"基本信息","content":"大三学生"},{"category":"情绪状态","content":"近期考试焦虑"}]}

        对话：
        %s""";

    public FactExtractionService(UserFactMapper factMapper,
                                 ObjectMapper objectMapper,
                                 ChatClient chatClient) {
        this.factMapper = factMapper;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    @Async
    public void extractAndSave(Long userId, String userMessage, String aiReply) {
        try {
            String prompt = String.format(EXTRACT_PROMPT,
                    "用户：" + userMessage + "\nAI：" + aiReply);

            String content = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.1)
                            .build())
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

            JsonNode factsNode = objectMapper.readTree(content).get("facts");
            if (factsNode == null || factsNode.size() == 0) return;

            for (JsonNode fact : factsNode) {
                String category = fact.get("category").asText();
                String factContent = fact.get("content").asText();

                if (factContent == null || factContent.isBlank()) continue;

                UserFact existing = factMapper.selectOne(
                        new LambdaQueryWrapper<UserFact>()
                                .eq(UserFact::getUserId, userId)
                                .eq(UserFact::getCategory, category)
                                .eq(UserFact::getFactContent, factContent)
                );

                if (existing != null) {
                    existing.setConfidence(Math.min(existing.getConfidence() + 0.1, 1.0));
                    existing.setStatus("active");
                    existing.setUpdatedAt(LocalDateTime.now());
                    factMapper.updateById(existing);
                    log.debug("Fact updated (confidence={}): [{}] {}", existing.getConfidence(), category, factContent);
                } else {
                    UserFact uf = new UserFact();
                    uf.setUserId(userId);
                    uf.setCategory(category);
                    uf.setFactContent(factContent);
                    uf.setConfidence(0.7);
                    uf.setStatus("active");
                    factMapper.insert(uf);
                    log.debug("Fact saved: [{}] {}", category, factContent);
                }
            }
        } catch (Exception e) {
            log.warn("Fact extraction failed: {}", e.getMessage());
        }
    }
}
