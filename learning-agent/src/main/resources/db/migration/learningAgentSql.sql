CREATE DATABASE IF NOT EXISTS `learning_agent`;

-- 用户表
CREATE TABLE IF NOT EXISTS user (
                                     id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
                                     username VARCHAR(64) NOT NULL COMMENT '用户名',
                                     password VARCHAR(255) NOT NULL COMMENT '密码哈希值，不保存明文密码',
                                     avatar_url VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
                                     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                     PRIMARY KEY (id),
                                     UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户表';
ALTER TABLE user
    ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'user'
        COMMENT '用户角色：user-普通用户，admin-管理员'
        AFTER avatar_url,
    ADD CONSTRAINT chk_users_role
        CHECK (`role` IN ('user', 'admin'));

-- 课程表
CREATE TABLE IF NOT EXISTS courses (
                                       id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课程主键',
                                       user_id BIGINT UNSIGNED NOT NULL COMMENT '课程拥有者或创建者 ID，逻辑外键',
                                       course_name VARCHAR(128) NOT NULL COMMENT '课程名称',
                                       difficulty_level TINYINT UNSIGNED NOT NULL COMMENT '学习难度，取值 1~5',
                                       publisher_id BIGINT UNSIGNED NOT NULL COMMENT '发布人 ID，逻辑外键',
                                       learning_outline JSON DEFAULT NULL COMMENT '学习大纲，可由用户设置或由 AI 生成',

                                       created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                       PRIMARY KEY (id),
                                       KEY idx_courses_user_id (user_id),
                                       KEY idx_courses_publisher_id (publisher_id),

                                       CONSTRAINT chk_courses_difficulty_level
                                           CHECK (difficulty_level BETWEEN 1 AND 5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '课程表';

ALTER TABLE courses
    ADD COLUMN course_type VARCHAR(20) NOT NULL DEFAULT 'PRIVATE'
    COMMENT '课程类型：PUBLIC-公共课程，PRIVATE-用户专有课程'
        AFTER publisher_id,
    ADD CONSTRAINT chk_courses_course_type
        CHECK (course_type IN ('PUBLIC', 'PRIVATE'));


-- 学习会话表
CREATE TABLE IF NOT EXISTS learning_sessions (
                                                 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '学习会话主键',
                                                 course_id BIGINT UNSIGNED NOT NULL COMMENT '课程 ID，逻辑外键',
                                                 user_id BIGINT UNSIGNED NOT NULL COMMENT '学习用户 ID，逻辑外键',
                                                 session_title VARCHAR(255) NOT NULL COMMENT '会话标题',
                                                 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态',
                                                 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                     ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                                 PRIMARY KEY (id),
                                                 KEY idx_learning_sessions_course_id (course_id),
                                                 KEY idx_learning_sessions_user_id (user_id),

                                                 CONSTRAINT chk_learning_sessions_status
                                                     CHECK (status IN ('ACTIVE', 'COMPLETED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话表';