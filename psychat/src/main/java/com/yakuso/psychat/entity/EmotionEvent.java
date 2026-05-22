package com.yakuso.psychat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("emotion_events")
public class EmotionEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sessionId;
    private Long messageId;
    private String emotionLabel;
    private Double intensity;
    private String summary;
    private LocalDateTime createdAt;
}
