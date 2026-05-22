package com.yakuso.psychat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WeeklyReportTool {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportTool.class);

    private final EmotionReportService reportService;
    private final ObjectMapper objectMapper;

    public WeeklyReportTool(EmotionReportService reportService, ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @org.springframework.ai.tool.annotation.Tool(description = """
            当用户主动询问"情绪报告""情绪趋势""周报""最近的情绪怎么样"等时，必须调用本工具，
            trigger 参数设为 "user_request"。
            仅在对话中检测到用户情绪波动非常剧烈（绝望/崩溃/极度难过反复出现）时才可主动调用，
            trigger 参数设为 "auto_detect"——此时如果返回 offered=false 说明波动未达阈值，闭嘴不提。""")
    public String offerWeeklyReport(Long userId,
            @org.springframework.ai.tool.annotation.ToolParam(description = "user_request: 用户主动要求查看; auto_detect: AI感知到剧烈情绪波动主动关怀") String trigger) {
        try {
            // auto_detect: check hard threshold first, stay silent if not met
            if ("auto_detect".equals(trigger)) {
                if (!reportService.shouldProactiveOffer(userId)) {
                    log.info("Auto-detect threshold not met for user {}", userId);
                    return "{\"offered\":false,\"reason\":\"auto_detect_threshold_not_met\"}";
                }
            }

            Map<String, Object> report = reportService.generateWeeklyReport(userId);
            boolean isEmpty = Boolean.TRUE.equals(report.get("empty"));
            String result;
            if (isEmpty) {
                if ("auto_detect".equals(trigger)) {
                    result = "{\"offered\":false,\"reason\":\"auto_detect_no_data\"}";
                } else {
                    result = "{\"offered\":false,\"reason\":\"本周暂无足够情绪记录，可以手动点击顶部「情绪周报」按钮查看\"}";
                }
            } else {
                String summary = String.valueOf(report.getOrDefault("summary", ""));
                String suggestion = "";
                var suggestions = report.get("suggestions");
                if (suggestions instanceof java.util.List<?> list && !list.isEmpty()) {
                    suggestion = String.valueOf(list.get(0));
                }
                result = objectMapper.writeValueAsString(Map.of(
                        "offered", true,
                        "summary", summary,
                        "topSuggestion", suggestion,
                        "eventCount", report.getOrDefault("eventCount", 0)
                ));
            }
            log.info("Weekly report tool ({}): {}", trigger, result);
            return result;
        } catch (Exception e) {
            log.warn("Weekly report tool failed: {}", e.getMessage());
            return "{\"offered\":false,\"reason\":\"error\"}";
        }
    }

    public String showWeeklyReport(Long userId) {
        try {
            Map<String, Object> report = reportService.generateWeeklyReport(userId);
            if (Boolean.TRUE.equals(report.get("empty"))) {
                return "{\"shown\":false,\"reason\":\"暂无本周情绪记录\"}";
            }
            String json = objectMapper.writeValueAsString(report);
            String result = "__REPORT__:" + json;
            log.info("Show weekly report for user {}", userId);
            return result;
        } catch (Exception e) {
            log.warn("Show weekly report failed: {}", e.getMessage());
            return "{\"shown\":false,\"reason\":\"error\"}";
        }
    }
}
