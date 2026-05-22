package com.yakuso.psychat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yakuso.psychat.entity.BindRelation;
import com.yakuso.psychat.entity.CrisisNotification;
import com.yakuso.psychat.entity.User;
import com.yakuso.psychat.mapper.BindRelationMapper;
import com.yakuso.psychat.mapper.CrisisNotificationMapper;
import com.yakuso.psychat.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class CrisisTool {

    private static final Logger log = LoggerFactory.getLogger(CrisisTool.class);
    private static final String COOLDOWN_KEY_PREFIX = "crisis:cooldown:";

    private final BindRelationMapper bindRelationMapper;
    private final UserMapper userMapper;
    private final CrisisNotificationMapper crisisNotificationMapper;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public CrisisTool(BindRelationMapper bindRelationMapper,
                      UserMapper userMapper,
                      CrisisNotificationMapper crisisNotificationMapper,
                      NotificationService notificationService,
                      StringRedisTemplate redis,
                      @Value("${app.crisis.cooldown-minutes:30}") int cooldownMinutes) {
        this.bindRelationMapper = bindRelationMapper;
        this.userMapper = userMapper;
        this.crisisNotificationMapper = crisisNotificationMapper;
        this.notificationService = notificationService;
        this.redis = redis;
        this.cooldown = Duration.ofMinutes(cooldownMinutes);
    }

    @org.springframework.ai.tool.annotation.Tool(description = """
            当用户表现出明确的自伤、自杀倾向或极端绝望情绪时调用此工具通知管理员。
            仅在风险信号明确时调用，不确定时不要调用。调用后无需等待结果，继续安抚用户。""")
    public String execute(Long userId,
            @org.springframework.ai.tool.annotation.ToolParam(description = "critical: 有明确自伤计划或意图; high: 表达强烈无望感") String riskLevel,
            @org.springframework.ai.tool.annotation.ToolParam(description = "用户原话关键片段，不超过30字") String evidence,
            @org.springframework.ai.tool.annotation.ToolParam(description = "本轮情绪状态简述，不超过40字") String summary) {
        try {
            String cooldownKey = COOLDOWN_KEY_PREFIX + userId;
            if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
                log.info("Crisis alert cooldown active for user {}", userId);
                return "{\"notified\":0,\"reason\":\"cooldown\"}";
            }

            List<BindRelation> binds = bindRelationMapper.selectList(
                    new LambdaQueryWrapper<BindRelation>()
                            .eq(BindRelation::getUserId, userId)
                            .eq(BindRelation::getStatus, "ACCEPTED")
            );

            if (binds.isEmpty()) {
                log.info("No bound admins for user {}", userId);
                return "{\"notified\":0,\"reason\":\"no_bindings\"}";
            }

            User user = userMapper.selectById(userId);
            String nickname = user != null && user.getNickname() != null
                    ? user.getNickname()
                    : "用户#" + userId;

            int count = 0;
            for (BindRelation bind : binds) {
                notificationService.sendCrisisAlert(
                        bind.getAdminId(), userId, nickname,
                        summary + "\n风险等级: " + riskLevel + "\n关键词: " + evidence
                );

                CrisisNotification notif = new CrisisNotification();
                notif.setAdminId(bind.getAdminId());
                notif.setUserId(userId);
                notif.setUsername(nickname);
                notif.setRiskLevel(riskLevel);
                notif.setEvidence(evidence);
                notif.setSummary(summary);
                notif.setIsRead(false);
                crisisNotificationMapper.insert(notif);

                count++;
            }

            redis.opsForValue().set(cooldownKey, riskLevel, cooldown);
            log.info("Crisis alert sent: userId={}, risk={}, admins={}", userId, riskLevel, count);
            return String.format("{\"notified\":%d}", count);
        } catch (Exception e) {
            log.error("Crisis tool execution failed for userId={}", userId, e);
            return "{\"notified\":0,\"reason\":\"error\"}";
        }
    }
}
