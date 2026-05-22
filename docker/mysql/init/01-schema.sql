CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `password_hash` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(100) DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN / USER',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `chat_messages` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `session_id` VARCHAR(64) NOT NULL DEFAULT 'default',
    `role` VARCHAR(20) NOT NULL COMMENT 'USER / ASSISTANT',
    `content` TEXT NOT NULL,
    `emotion_summary` VARCHAR(500) DEFAULT NULL COMMENT 'AI-generated emotion summary for this message',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_session` (`user_id`, `session_id`),
    INDEX `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `bind_relations` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `admin_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED',
    `initiator` VARCHAR(20) NOT NULL COMMENT 'ADMIN / USER',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_admin_user` (`admin_id`, `user_id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_admin` (`admin_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_facts` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `category` VARCHAR(50) NOT NULL COMMENT '基本信息/情绪状态/关注问题/经历事件',
    `fact_content` VARCHAR(500) NOT NULL,
    `confidence` DOUBLE NOT NULL DEFAULT 0.8 COMMENT '置信度 0-1',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active / superseded / deprecated',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_user` (`user_id`),
    INDEX `idx_user_category` (`user_id`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `emotion_events` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `session_id` VARCHAR(64) NOT NULL,
    `message_id` BIGINT DEFAULT NULL,
    `emotion_label` VARCHAR(32) NOT NULL COMMENT '焦虑/抑郁/愤怒/悲伤/恐惧/平静/开心/困惑/绝望/孤独/压力/希望',
    `intensity` DOUBLE NOT NULL DEFAULT 0.5 COMMENT '强度 0-1',
    `sentiment_score` DOUBLE NOT NULL DEFAULT 0 COMMENT '情感极性 -1 到 1',
    `summary` VARCHAR(512) DEFAULT NULL COMMENT '情绪来源简述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_created` (`user_id`, `created_at`),
    INDEX `idx_user_session` (`user_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL UNIQUE,
    `tone_style` VARCHAR(32) NOT NULL DEFAULT 'warm' COMMENT 'warm / casual / professional / concise',
    `response_length` VARCHAR(32) NOT NULL DEFAULT 'medium' COMMENT 'short / medium / long',
    `allow_proactive` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许主动问候',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `crisis_notifications` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `admin_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `username` VARCHAR(100) NOT NULL COMMENT '用户昵称',
    `risk_level` VARCHAR(32) NOT NULL COMMENT '风险等级',
    `evidence` VARCHAR(500) DEFAULT NULL COMMENT '触发关键词',
    `summary` VARCHAR(500) DEFAULT NULL COMMENT '危机概要',
    `is_read` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_admin_read` (`admin_id`, `is_read`),
    INDEX `idx_admin_created` (`admin_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `memory_summaries` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `session_id` VARCHAR(64) DEFAULT NULL,
    `summary` TEXT NOT NULL COMMENT '对话摘要',
    `tags` VARCHAR(500) DEFAULT NULL COMMENT '逗号分隔标签',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
