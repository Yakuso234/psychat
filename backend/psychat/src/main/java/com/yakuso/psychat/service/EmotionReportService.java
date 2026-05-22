package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yakuso.psychat.entity.EmotionEvent;
import com.yakuso.psychat.entity.UserFact;
import com.yakuso.psychat.entity.UserPreference;
import com.yakuso.psychat.mapper.EmotionEventMapper;
import com.yakuso.psychat.mapper.UserFactMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmotionReportService {

    private static final Logger log = LoggerFactory.getLogger(EmotionReportService.class);

    private final EmotionEventMapper emotionEventMapper;
    private final UserFactMapper userFactMapper;
    private final UserPreferenceService preferenceService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String REPORT_PROMPT = """
        你是一位温暖的心理咨询师。请根据以下用户信息，生成一份个性化关怀周报。

        用户画像：
        %s

        用户偏好：
        %s

        本周情绪时间线：
        %s

        请用温柔共情的语气，生成以下内容。建议务必结合用户画像和个人偏好，做到"千人千面"：
        1. 本周情绪概览（2-3句，称呼用户时结合画像）
        2. 情绪趋势（从数据中发现的模式或变化）
        3. 自我关怀建议（2-3条具体建议，结合用户偏好和画像，比如喜欢运动就给运动建议，是学生就给校园场景建议）
        4. 下周小目标（1个微小、可实现的积极行动，贴合用户生活场景）

        返回格式（严格JSON，不要Markdown包裹，不要其他文字）：
        {"summary":"...","trend":"...","suggestions":["...","..."],"weeklyGoal":"..."}""";

    public EmotionReportService(EmotionEventMapper emotionEventMapper,
                                 UserFactMapper userFactMapper,
                                 UserPreferenceService preferenceService,
                                 ChatClient chatClient,
                                 ObjectMapper objectMapper) {
        this.emotionEventMapper = emotionEventMapper;
        this.userFactMapper = userFactMapper;
        this.preferenceService = preferenceService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateWeeklyReport(Long userId) {
        try {
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            List<EmotionEvent> events = emotionEventMapper.selectList(
                    new LambdaQueryWrapper<EmotionEvent>()
                            .eq(EmotionEvent::getUserId, userId)
                            .ge(EmotionEvent::getCreatedAt, weekAgo)
                            .orderByAsc(EmotionEvent::getCreatedAt)
            );

            if (events.isEmpty()) {
                return Map.of("empty", true, "eventCount", 0,
                        "message", "本周暂无情绪记录，多来找我聊聊吧~");
            }

            // load user persona: structured facts
            List<UserFact> facts = userFactMapper.selectList(
                    new LambdaQueryWrapper<UserFact>()
                            .eq(UserFact::getUserId, userId)
                            .eq(UserFact::getStatus, "active")
            );
            StringBuilder persona = new StringBuilder();
            if (!facts.isEmpty()) {
                for (var f : facts) {
                    persona.append("- ").append(f.getCategory()).append("：").append(f.getFactContent()).append("\n");
                }
            } else {
                persona.append("暂无详细画像\n");
            }

            // load user preferences
            UserPreference pref = preferenceService.getByUserId(userId);
            StringBuilder prefText = new StringBuilder();
            if (pref != null) {
                String toneLabel = switch (pref.getToneStyle() != null ? pref.getToneStyle() : "warm") {
                    case "casual" -> "轻松随意";
                    case "professional" -> "专业理性";
                    case "concise" -> "简洁直接";
                    default -> "温柔共情";
                };
                prefText.append("沟通风格偏好：").append(toneLabel).append("\n");
                prefText.append("回复长度偏好：").append("long".equals(pref.getResponseLength()) ? "较长" : "medium".equals(pref.getResponseLength()) ? "中等" : "简短").append("\n");
            } else {
                prefText.append("暂无偏好记录\n");
            }

            // build emotion timeline
            StringBuilder timeline = new StringBuilder();
            for (var e : events) {
                String day = e.getCreatedAt().getDayOfWeek().toString();
                String level = EmotionAnalysisService.intensityToLevel(e.getIntensity());
                timeline.append(String.format("- %s: %s·%s", day, e.getEmotionLabel(), level));
                if (e.getSummary() != null && !e.getSummary().isBlank()) {
                    timeline.append("（").append(e.getSummary()).append("）");
                }
                timeline.append("\n");
            }

            String prompt = String.format(REPORT_PROMPT, persona, prefText, timeline);
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (content == null) {
                return Map.of("error", "报告生成失败");
            }

            content = content.trim();
            if (content.startsWith("```")) {
                content = content.substring(content.indexOf('\n') + 1);
                if (content.endsWith("```")) {
                    content = content.substring(0, content.lastIndexOf("```")).trim();
                }
            }

            Map<String, Object> report = objectMapper.readValue(content, Map.class);
            report.put("empty", false);
            report.put("eventCount", events.size());
            report.put("generatedAt", LocalDateTime.now().toString());

            // build chart data: one point per event, {day, label, intensity}
            List<Map<String, Object>> chart = new ArrayList<>();
            for (var e : events) {
                String dayLabel = switch (e.getCreatedAt().getDayOfWeek()) {
                    case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
                    case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六";
                    case SUNDAY -> "周日";
                };
                chart.add(Map.of(
                        "day", dayLabel,
                        "label", e.getEmotionLabel() != null ? e.getEmotionLabel() : "",
                        "intensity", e.getIntensity()
                ));
            }
            report.put("chart", chart);
            return report;
        } catch (Exception e) {
            log.warn("Weekly report generation failed: {}", e.getMessage());
            return Map.of("error", "报告生成失败: " + e.getMessage());
        }
    }

    /**
     * Check if user has enough significant emotional events this week
     * to warrant a proactive report offering.
     */
    public boolean shouldProactiveOffer(Long userId) {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<EmotionEvent> events = emotionEventMapper.selectList(
                new LambdaQueryWrapper<EmotionEvent>()
                        .eq(EmotionEvent::getUserId, userId)
                        .ge(EmotionEvent::getCreatedAt, weekAgo)
        );
        // threshold: at least 3 events with intensity > 0.7 (难过/悲观/绝望)
        long significant = events.stream()
                .filter(e -> e.getIntensity() > 0.7)
                .count();
        return significant >= 3;
    }
}
