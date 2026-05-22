package com.yakuso.psychat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_facts")
public class UserFact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String category;
    private String factContent;
    private Double confidence;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
