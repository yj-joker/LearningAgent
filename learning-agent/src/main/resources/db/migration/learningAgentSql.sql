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
    ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'USER'
        COMMENT '用户角色：user-普通用户，admin-管理员'
        AFTER avatar_url,
    ADD CONSTRAINT chk_users_role
        CHECK (`role` IN ('USER', 'ADMIN'));

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
    COMMENT '课程审核状态：PRIVATE-私有，PENDING-待审核，PUBLISHED-已发布'
        AFTER publisher_id,
    ADD CONSTRAINT chk_courses_course_type
        CHECK (course_type IN ('PRIVATE', 'PENDING', 'PUBLISHED'));


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

-- 章节表
CREATE TABLE IF NOT EXISTS chapters (
                                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
                                            COMMENT '章节主键',

                                        course_id BIGINT UNSIGNED NOT NULL
                                            COMMENT '课程 ID，逻辑外键，关联 courses.id',

                                        title VARCHAR(255) NOT NULL
                                            COMMENT '章节名称',

                                        sort_order INT UNSIGNED NOT NULL
                                            COMMENT '章节展示顺序，同一课程内必须唯一，初始值*1000，重排阀值为100',

                                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            COMMENT '创建时间',

                                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            ON UPDATE CURRENT_TIMESTAMP
                                            COMMENT '更新时间',

                                        PRIMARY KEY (id),

    -- 同一课程内，章节顺序不能重复
                                        UNIQUE KEY uk_chapters_course_sort_order (course_id, sort_order),
                                        UNIQUE KEY uk_chapters_course_title(course_id,title)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '章节表';


-- 知识点表
CREATE TABLE IF NOT EXISTS knowledge_points (
                                                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
                                                    COMMENT '知识点主键',

                                                course_id BIGINT UNSIGNED NOT NULL
                                                    COMMENT '课程 ID，逻辑外键；有意的反范式冗余，可由 chapter_id 推导，便于按课程直接过滤，也便于第 5 周 RAG 权限过滤时避免 JOIN',

                                                chapter_id BIGINT UNSIGNED NOT NULL
                                                    COMMENT '章节 ID，逻辑外键，关联 chapters.id',

                                                name VARCHAR(255) NOT NULL
                                                    COMMENT '知识点名称',

                                                description TEXT
                                                    COMMENT '知识点描述',

                                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                    COMMENT '创建时间',

                                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                    ON UPDATE CURRENT_TIMESTAMP
                                                    COMMENT '更新时间',

                                                PRIMARY KEY (id),

    -- 同一章节内，知识点名称不能重复
                                                UNIQUE KEY uk_knowledge_points_chapter_name (chapter_id, name),

                                                KEY idx_knowledge_points_course_id (course_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识点表';


-- 知识点关系表
CREATE TABLE IF NOT EXISTS knowledge_point_relations (
                                                         id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
                                                             COMMENT '知识点关系主键',

                                                         from_point_id BIGINT UNSIGNED NOT NULL
                                                             COMMENT '起始知识点 ID，逻辑外键，关联 knowledge_points.id',

                                                         to_point_id BIGINT UNSIGNED NOT NULL
                                                             COMMENT '目标知识点 ID，逻辑外键，关联 knowledge_points.id',

                                                         relation_type VARCHAR(20) NOT NULL
                                                             COMMENT '关系类型：PREREQUISITE-前置关系，CONFUSABLE-易混淆关系',

                                                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                             COMMENT '创建时间',

                                                         PRIMARY KEY (id),

    -- 防止完全相同的关系重复出现
                                                         UNIQUE KEY uk_kpr_relation (
                                                                                     from_point_id,
                                                                                     to_point_id,
                                                                                     relation_type
                                                             ),

    -- 为反向查询单独建立索引
                                                         KEY idx_kpr_to_point_id (to_point_id),

    -- 关系类型只能是指定值
                                                         CONSTRAINT chk_kpr_relation_type
                                                             CHECK (relation_type IN ('PREREQUISITE', 'CONFUSABLE')),

    -- 防止知识点指向自己
                                                         CONSTRAINT chk_kpr_no_self_loop
                                                             CHECK (from_point_id <> to_point_id),

    -- CONFUSABLE 是无向关系，统一要求较小 ID 放在 from_point_id
                                                         CONSTRAINT chk_kpr_confusable_order
                                                             CHECK (
                                                                 relation_type <> 'CONFUSABLE'
                                                                     OR from_point_id < to_point_id
                                                                 )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识点关系表';