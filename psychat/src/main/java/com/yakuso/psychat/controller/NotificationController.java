package com.yakuso.psychat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.entity.CrisisNotification;
import com.yakuso.psychat.mapper.CrisisNotificationMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private final CrisisNotificationMapper notificationMapper;

    public NotificationController(CrisisNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        Long userId = AuthContext.getUserId();
        Long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<CrisisNotification>()
                        .eq(CrisisNotification::getAdminId, userId)
                        .eq(CrisisNotification::getIsRead, false)
        );
        return Result.ok(count.intValue());
    }

    @GetMapping("/list")
    public Result<List<CrisisNotification>> list(@RequestParam(defaultValue = "20") int limit) {
        Long userId = AuthContext.getUserId();
        List<CrisisNotification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<CrisisNotification>()
                        .eq(CrisisNotification::getAdminId, userId)
                        .orderByDesc(CrisisNotification::getCreatedAt)
                        .last("LIMIT " + limit)
        );
        return Result.ok(list);
    }

    @PutMapping("/read/{id}")
    public Result<Void> markRead(@PathVariable Long id) {
        Long userId = AuthContext.getUserId();
        notificationMapper.update(null,
                new LambdaUpdateWrapper<CrisisNotification>()
                        .eq(CrisisNotification::getId, id)
                        .eq(CrisisNotification::getAdminId, userId)
                        .set(CrisisNotification::getIsRead, true)
        );
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        Long userId = AuthContext.getUserId();
        notificationMapper.update(null,
                new LambdaUpdateWrapper<CrisisNotification>()
                        .eq(CrisisNotification::getAdminId, userId)
                        .eq(CrisisNotification::getIsRead, false)
                        .set(CrisisNotification::getIsRead, true)
        );
        return Result.ok();
    }
}
