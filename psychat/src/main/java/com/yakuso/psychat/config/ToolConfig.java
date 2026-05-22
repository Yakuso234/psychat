package com.yakuso.psychat.config;

import com.yakuso.psychat.service.CrisisTool;
import com.yakuso.psychat.service.WeeklyReportTool;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfig {

    private final ToolRegistry registry;
    private final CrisisTool crisisTool;
    private final WeeklyReportTool weeklyReportTool;

    public ToolConfig(ToolRegistry registry, CrisisTool crisisTool,
                      WeeklyReportTool weeklyReportTool) {
        this.registry = registry;
        this.crisisTool = crisisTool;
        this.weeklyReportTool = weeklyReportTool;
    }

    @PostConstruct
    public void registerTools() {
        registry.register(
                "notify_crisis",
                "当用户表现出明确的自伤、自杀倾向或极端绝望情绪时调用此工具通知管理员。"
                        + "仅在风险信号明确时调用，不确定时不要调用。调用后无需等待结果，继续安抚用户。",
                """
                {
                    "type": "object",
                    "properties": {
                        "risk_level": {
                            "type": "string",
                            "enum": ["critical", "high"],
                            "description": "critical: 有明确自伤计划或意图; high: 表达强烈无望感"
                        },
                        "evidence": {
                            "type": "string",
                            "description": "用户原话关键片段，不超过30字"
                        },
                        "summary": {
                            "type": "string",
                            "description": "本轮情绪状态简述，不超过40字"
                        }
                    },
                    "required": ["risk_level", "evidence", "summary"]
                }
                """,
                (userId, args) -> crisisTool.execute(
                        userId,
                        String.valueOf(args.get("risk_level")),
                        String.valueOf(args.get("evidence")),
                        String.valueOf(args.get("summary"))
                )
        );

        registry.register(
                "offer_weekly_report",
                "用户主动询问情绪报告/情绪趋势/周报时必须调用，trigger='user_request'。\n"
                        + "AI自动感知：仅在对话中出现绝望/崩溃/极度难过等剧烈情绪反复时才可主动调用，trigger='auto_detect'。"
                        + "auto_detect 返回 offered=false 时闭嘴不提；user_request 返回 offered=false 时告知数据不足、建议手动点击顶部按钮。",
                """
                {
                    "type": "object",
                    "properties": {
                        "trigger": {
                            "type": "string",
                            "enum": ["user_request", "auto_detect"],
                            "description": "user_request=用户主动要求; auto_detect=AI感知剧烈情绪波动主动关怀"
                        }
                    },
                    "required": ["trigger"]
                }
                """,
                (userId, args) -> weeklyReportTool.offerWeeklyReport(
                        userId,
                        String.valueOf(args.get("trigger"))
                )
        );

        registry.register(
                "show_weekly_report",
                "当用户明确同意查看情绪周报时（用户说了'好''ok''看看''可以''嗯''行'等肯定回复），"
                        + "必须调用此工具。不要凭记忆生成报告内容，不要跳过工具调用。",
                """
                {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
                """,
                (userId, args) -> weeklyReportTool.showWeeklyReport(userId)
        );
    }
}
