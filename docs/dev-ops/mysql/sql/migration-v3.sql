-- =====================================================================
-- ai-ssh 升级迁移脚本 v3（会话任务态表 / 双模式存储补齐）
-- 适用：已有 ai_ssh 库（MySQL 8.0），且已执行过 v2 迁移
-- 背景：
--   本地 H2 模式（local profile）按 ChatTaskStatePO 建表时已经包含
--   chat_task_state，但 MySQL 侧 init.sql / 迁移脚本此前漏了这张表，
--   导致 server profile（MySQL）下任务态落库与冷启动恢复缺少存储。
--   本脚本把两种模式的表结构补齐到一致。
-- 说明：
--   1) 只新增表，不改存量表结构，可重复执行（CREATE TABLE IF NOT EXISTS）
--   2) 表结构与 ai-ssh-server-app/src/main/resources/db/schema-h2.sql 对齐
--   3) 执行前建议备份：mysqldump -u root -p ai_ssh > ai_ssh_before_v3.sql
-- =====================================================================

use `ai_ssh`;

-- ---------------------------------------------------------------------
-- 会话任务态：随会话一行存储（root_intent / 任务锚点 / 步骤留痕 / 完成标记）
-- 对应 ChatTaskStatePO / chat_task_state
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_task_state`
(
    `session_id`           varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID(主键，与 chat_session.id 一致)',
    `root_intent`          varchar(64) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '根意图枚举名',
    `task_description`     text COMMENT '任务锚点描述',
    `steps`                text COMMENT '步骤留痕(0x1F 分隔)',
    `current_step_index`   int         NOT NULL DEFAULT '0' COMMENT '当前步骤下标',
    `completed`            tinyint     NOT NULL DEFAULT '0' COMMENT '任务是否完成:0-否,1-是',
    `last_feedback_failed` tinyint     NOT NULL DEFAULT '0' COMMENT '最近反馈是否失败/回合中断:0-否,1-是',
    `updated_at`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='会话任务态表';
