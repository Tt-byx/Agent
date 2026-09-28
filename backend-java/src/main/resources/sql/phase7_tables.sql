-- Phase 7: 消费分析 + 反馈系统
-- 执行方式: 在 MySQL 中运行此脚本

-- 消费数据表（xlsx 导入目标）
CREATE TABLE IF NOT EXISTS tourism_data (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tourist_id VARCHAR(20),
  nickname VARCHAR(50),
  age INT,
  gender VARCHAR(10),
  attraction_name VARCHAR(100),
  attraction_type VARCHAR(50),
  visit_date DATE,
  stay_duration DECIMAL(5,1),
  ticket_cost DECIMAL(10,2),
  food_cost DECIMAL(10,2),
  shopping_cost DECIMAL(10,2),
  transport_cost DECIMAL(10,2),
  entertainment_cost DECIMAL(10,2),
  total_cost DECIMAL(10,2),
  group_size INT,
  satisfaction DECIMAL(3,1),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_visit_date (visit_date),
  INDEX idx_attraction (attraction_name),
  INDEX idx_tourist (tourist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 消息反馈表（点赞/踩）
CREATE TABLE IF NOT EXISTS message_feedback (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  user_id BIGINT,
  feedback_type VARCHAR(10) NOT NULL COMMENT 'like or dislike',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_message_user (message_id, user_id),
  INDEX idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
