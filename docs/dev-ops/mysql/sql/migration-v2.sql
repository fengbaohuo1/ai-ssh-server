-- =====================================================================
-- ai-ssh 升级迁移脚本 v2（主机元数据 / 操作审计）
-- 适用：已有 ai_ssh 库（MySQL 8.0）
-- 说明：
--   1) 本脚本只做结构变更，不触碰存量凭证；存量明文凭证会在下次"保存连接"时自动加密
--   2) MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 duplicate column，属预期
--   3) 生产执行前请备份：mysqldump -u root -p ai_ssh > ai_ssh_before_v2.sql
-- =====================================================================

use `ai_ssh`;

-- ---------------------------------------------------------------------
-- 1. ssh_connection：主机分组 / 标签 / 备注 / 收藏 / 最近连接时间
-- ---------------------------------------------------------------------
ALTER TABLE `ssh_connection`
    ADD COLUMN `group_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '主机分组' AFTER `user_id`,
    ADD COLUMN `tags` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标签(逗号分隔)' AFTER `group_name`,
    ADD COLUMN `remark` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注' AFTER `tags`,
    ADD COLUMN `favorite` tinyint NOT NULL DEFAULT '0' COMMENT '是否收藏:0-否,1-是' AFTER `remark`,
    ADD COLUMN `last_connected_at` datetime DEFAULT NULL COMMENT '最近一次成功连接时间' AFTER `favorite`;

ALTER TABLE `ssh_connection`
    ADD KEY `idx_user_group` (`user_id`, `group_name`),
    ADD KEY `idx_user_favorite` (`user_id`, `favorite`);

-- ---------------------------------------------------------------------
-- 2. operation_audit_log：操作审计（连接生命周期 / 命令 / 文件 / AI）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `operation_audit_log`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`         varchar(64)  NOT NULL DEFAULT 'default' COMMENT '操作用户',
    `connection_id`   varchar(64)           DEFAULT NULL COMMENT '关联连接ID',
    `connection_name` varchar(128)          DEFAULT NULL COMMENT '连接名快照',
    `action`          varchar(64)  NOT NULL COMMENT '动作:CONNECTION_*/COMMAND_EXECUTE/FILE_*等',
    `resource`        varchar(1024)         DEFAULT NULL COMMENT '资源(命令/路径/会话)',
    `detail`          varchar(2048)         DEFAULT NULL COMMENT '明细',
    `risk_level`      varchar(16)  NOT NULL DEFAULT 'LOW' COMMENT '风险:LOW/MEDIUM/HIGH/CRITICAL',
    `success`         tinyint      NOT NULL DEFAULT '1' COMMENT '是否成功:0-否,1-是',
    `duration_ms`     bigint       NOT NULL DEFAULT '0' COMMENT '耗时(毫秒)',
    `client_ip`       varchar(64)           DEFAULT NULL COMMENT '客户端IP',
    `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_connection_time` (`connection_id`, `created_at`),
    KEY `idx_action_time` (`action`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='操作审计日志';
