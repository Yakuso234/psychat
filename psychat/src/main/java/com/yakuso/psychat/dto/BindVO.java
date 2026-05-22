package com.yakuso.psychat.dto;

import com.yakuso.psychat.entity.BindRelation;
import com.yakuso.psychat.entity.User;
import lombok.Data;

@Data
public class BindVO {
    private Long id;
    private Long adminId;
    private String adminName;
    private Long userId;
    private String userName;
    private String status;
    private String initiator;
    private String createdAt;

    public BindVO(BindRelation r, User admin, User user) {
        this.id = r.getId();
        this.adminId = r.getAdminId();
        this.adminName = admin != null ? admin.getUsername() : "未知";
        this.userId = r.getUserId();
        this.userName = user != null ? user.getUsername() : "未知";
        this.status = r.getStatus();
        this.initiator = r.getInitiator();
        this.createdAt = r.getCreatedAt() != null ? r.getCreatedAt().toString() : null;
    }
}
