package com.yakuso.psychat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bind_relations")
public class BindRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminId;
    private Long userId;
    private String status;
    private String initiator;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
