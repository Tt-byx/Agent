-- Phase 9: 游客画像标签
-- 执行方式: 在 MySQL 中运行此脚本

-- 游客画像标签表
CREATE TABLE IF NOT EXISTS visitor_profile_tag (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  tag_name VARCHAR(50) NOT NULL,
  tag_score INT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_tag (user_id, tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
