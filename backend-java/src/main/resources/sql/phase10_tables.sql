-- Phase 10: 知识库管理增强 — knowledge_doc 表新增字段
-- 执行方式: 在 MySQL 中运行此脚本

ALTER TABLE knowledge_doc
  ADD COLUMN category VARCHAR(50) DEFAULT '未分类' COMMENT '分类标签',
  ADD COLUMN scenic_area VARCHAR(100) DEFAULT '' COMMENT '关联景区',
  ADD COLUMN last_modified_by VARCHAR(50) DEFAULT '' COMMENT '最后修改人';

-- 确保现有记录有默认值
UPDATE knowledge_doc SET category = '未分类' WHERE category IS NULL;
