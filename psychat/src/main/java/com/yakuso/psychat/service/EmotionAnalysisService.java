package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yakuso.psychat.entity.EmotionEvent;
import com.yakuso.psychat.mapper.EmotionEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmotionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(EmotionAnalysisService.class);

    private final EmotionEventMapper emotionEventMapper;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    private static final String EMOTION_PROMPT = """
        请分析以下对话中用户的情绪状态，以JSON格式返回。

        情绪标签（选其一）：焦虑、低落、疲惫、烦躁、委屈、悲伤、绝望、困惑、孤独、内耗、愤怒、压力、恐慌、恐惧、后悔、无力、麻木、失望、压抑、被抛弃、平静、开心
        emotion_level：情绪五级强度，严格从以下五选一：平静、低落、难过、悲观、绝望
        ——平静：无明显负面情绪，正常交流
        ——低落：有些沮丧、疲惫、提不起精神，但尚能正常表达
        ——难过：明显痛苦、想哭、内心难受，需要深度共情
        ——悲观：觉得没希望、自我否定、接近崩溃边缘
        ——绝望：有自伤倾向或极端无助，需要危机干预
        summary：简短描述情绪原因，不超过12字

        返回格式（严格JSON，不要其他文字）：
        {"emotion_label":"焦虑","emotion_level":"难过","summary":"考试压力失眠"}

        对话：
        %s""";

    public EmotionAnalysisService(EmotionEventMapper emotionEventMapper,
                                   ObjectMapper objectMapper,
                                   ChatClient chatClient) {
        this.emotionEventMapper = emotionEventMapper;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    @Async
    public void analyzeAndSave(Long userId, String sessionId, String userMessage, String aiReply) {
        try {
            String prompt = String.format(EMOTION_PROMPT,
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

            JsonNode emotionNode = objectMapper.readTree(content);

            EmotionEvent event = new EmotionEvent();
            event.setUserId(userId);
            event.setSessionId(sessionId);
            event.setEmotionLabel(emotionNode.get("emotion_label").asText());

            double intensity;
            if (emotionNode.has("emotion_level") && !emotionNode.get("emotion_level").isNull()) {
                String level = emotionNode.get("emotion_level").asText();
                intensity = levelToIntensity(level);
            } else if (emotionNode.has("intensity") && !emotionNode.get("intensity").isNull()) {
                intensity = emotionNode.get("intensity").asDouble();
            } else {
                intensity = 0.5;
            }
            event.setIntensity(intensity);

            if (emotionNode.has("summary") && !emotionNode.get("summary").isNull()) {
                event.setSummary(emotionNode.get("summary").asText());
            }
            emotionEventMapper.insert(event);
            log.debug("Emotion saved: {} intensity={} level={}",
                    event.getEmotionLabel(), event.getIntensity(), intensityToLevel(intensity));
        } catch (Exception e) {
            log.warn("Emotion analysis failed: {}", e.getMessage());
        }
    }

    private static double levelToIntensity(String level) {
        return switch (level) {
            case "平静" -> 0.15;
            case "低落" -> 0.4;
            case "难过" -> 0.6;
            case "悲观" -> 0.8;
            case "绝望" -> 0.95;
            default -> 0.5;
        };
    }

    public static String intensityToLevel(double intensity) {
        if (intensity < 0.3) return "平静";
        if (intensity < 0.5) return "低落";
        if (intensity < 0.7) return "难过";
        if (intensity < 0.9) return "悲观";
        return "绝望";
    }
}
