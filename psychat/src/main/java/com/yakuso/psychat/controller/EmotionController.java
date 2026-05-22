package com.yakuso.psychat.controller;

import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.entity.EmotionEvent;
import com.yakuso.psychat.mapper.EmotionEventMapper;
import com.yakuso.psychat.service.EmotionReportService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/emotion")
public class EmotionController {

    private final EmotionEventMapper emotionEventMapper;
    private final EmotionReportService emotionReportService;

    public EmotionController(EmotionEventMapper emotionEventMapper,
                             EmotionReportService emotionReportService) {
        this.emotionEventMapper = emotionEventMapper;
        this.emotionReportService = emotionReportService;
    }

    @GetMapping("/recent")
    public Result<List<EmotionEvent>> recent(@RequestParam(defaultValue = "10") int limit) {
        Long userId = AuthContext.getUserId();
        List<EmotionEvent> events = emotionEventMapper.selectList(
                new LambdaQueryWrapper<EmotionEvent>()
                        .eq(EmotionEvent::getUserId, userId)
                        .orderByDesc(EmotionEvent::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, 50))
        );
        return Result.ok(events);
    }

    @GetMapping("/weekly-report")
    public Result<Map<String, Object>> weeklyReport() {
        Long userId = AuthContext.getUserId();
        Map<String, Object> report = emotionReportService.generateWeeklyReport(userId);
        return Result.ok(report);
    }
}
