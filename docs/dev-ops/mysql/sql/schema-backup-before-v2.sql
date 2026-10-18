-- ssh_connection
CREATE TABLE `ssh_connection` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `connection_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接唯一标识(UUID)',
  `connection_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接名称',
  `host` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主机地址',
  `port` int NOT NULL DEFAULT '22' COMMENT '端口�?',
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户�?',
  `auth_type` tinyint NOT NULL DEFAULT '1' COMMENT '认证类型:1-密码,2-私钥',
  `password` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码(加密存储)',
  `private_key` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '私钥内容(加密存储)',
  `encrypted` tinyint NOT NULL DEFAULT '1' COMMENT '是否加密:0-�?,1-�?',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '连接状�??:0-未连�?,1-已连�?,2-连接�?,3-连接失败',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删�?,1-已删�?',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_connection_id` (`connection_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接配置�?';

-- ssh_connection_config
CREATE TABLE `ssh_connection_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `connection_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联的连接ID',
  `connect_timeout` int NOT NULL DEFAULT '10' COMMENT '连接超时时间(�?)',
  `keepalive_interval` int NOT NULL DEFAULT '60' COMMENT '保活间隔(�?)',
  `startup_command` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '连接后执行的启动命令',
  `compression` tinyint NOT NULL DEFAULT '0' COMMENT '是否压缩:0-�?,1-�?',
  `strict_host_key_check` tinyint NOT NULL DEFAULT '1' COMMENT '严格主机密钥�?�?:0-�?,1-�?',
  `known_hosts` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '已知主机密钥列表',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_connection_id` (`connection_id`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接高级配置�?';

