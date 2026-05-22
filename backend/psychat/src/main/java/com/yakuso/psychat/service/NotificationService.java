package com.yakuso.psychat.service;

import com.yakuso.psychat.websocket.NotificationHandler;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationHandler notificationHandler;

    public NotificationService(NotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
    }

    public void sendToUser(Long userId, String message) {
        notificationHandler.sendToUser(userId, message);
    }

    public void sendBindRequest(Long userId, Long fromAdminId, String adminName) {
        String msg = String.format(
                "{\"type\":\"BIND_REQUEST\",\"adminId\":%d,\"adminName\":\"%s\",\"message\":\"管理员 %s 希望与你建立关怀关系\"}",
                fromAdminId, adminName, adminName
        );
        sendToUser(userId, msg);
    }

    public void sendCrisisAlert(Long adminId, Long userId, String username, String summary) {
        String msg = String.format(
                "{\"type\":\"CRISIS_ALERT\",\"userId\":%d,\"username\":\"%s\",\"summary\":\"%s\"}",
                userId, username, summary
        );
        sendToUser(adminId, msg);
    }
}
