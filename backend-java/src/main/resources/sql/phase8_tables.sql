-- Phase 8: 数字人配置 + 情感分析
-- 执行方式: 在 MySQL 中运行此脚本

-- 数字人配置表
CREATE TABLE IF NOT EXISTS digital_human_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(50) UNIQUE NOT NULL,
  config_value VARCHAR(200) NOT NULL,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始配置
INSERT IGNORE INTO digital_human_config (config_key, config_value) VALUES
('voice', 'zh-CN-XiaoxiaoNeural'),
('voice_speed', '1.0'),
('model', 'aniya');
