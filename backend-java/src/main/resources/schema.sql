-- =============================================
-- 景区导览AI数字人 - 数据库初始化脚本
-- =============================================

CREATE DATABASE IF NOT EXISTS scenic_ai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE scenic_ai;

-- ========== 1. 用户表 ==========
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(255) NOT NULL COMMENT '密码（后期加密存储）',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar_url`  VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'visitor' COMMENT '角色: visitor-游客, admin-管理员',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-禁用',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ========== 2. 景点表 ==========
CREATE TABLE IF NOT EXISTS `scenic_spot` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(100)  NOT NULL COMMENT '景点名称',
    `description` TEXT          DEFAULT NULL COMMENT '景点描述',
    `category`    VARCHAR(50)   DEFAULT NULL COMMENT '分类: 历史古迹/自然风光/人文景观/美食街区',
    `latitude`    DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
    `longitude`   DECIMAL(10,7) DEFAULT NULL COMMENT '经度',
    `image_url`   VARCHAR(500)  DEFAULT NULL COMMENT '景点图片URL',
    `open_time`   VARCHAR(50)   DEFAULT NULL COMMENT '开放时间',
    `ticket_info` VARCHAR(200)  DEFAULT NULL COMMENT '门票信息',
    `sort_order`  INT           DEFAULT 0 COMMENT '排序权重',
    `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-下架',
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='景点表';

-- ========== 3. 对话会话表 ==========
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话ID（UUID）',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '关联用户ID（游客可为空）',
    `title`       VARCHAR(100) DEFAULT NULL COMMENT '会话标题（取第一条消息）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1-进行中, 0-已结束',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';

-- ========== 4. 聊天消息表 ==========
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT   NOT NULL COMMENT '所属会话ID',
    `role`            VARCHAR(20) NOT NULL COMMENT '角色: user-游客, assistant-AI, system-系统',
    `content`         TEXT     NOT NULL COMMENT '消息内容',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';

-- ========== 5. 知识文档表 ==========
CREATE TABLE IF NOT EXISTS `knowledge_doc` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`         VARCHAR(200) NOT NULL COMMENT '文档标题',
    `file_name`     VARCHAR(200) DEFAULT NULL COMMENT '原始文件名',
    `file_url`      VARCHAR(500) DEFAULT NULL COMMENT '文件存储URL',
    `file_type`     VARCHAR(20)  DEFAULT NULL COMMENT '文件类型: pdf/docx/txt',
    `content`       LONGTEXT     DEFAULT NULL COMMENT '解析后的文本内容',
    `chunk_count`   INT          DEFAULT 0 COMMENT '切片数量',
    `vector_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '向量化状态: 0-未处理, 1-处理中, 2-已完成, 3-失败',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-删除',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档表';
