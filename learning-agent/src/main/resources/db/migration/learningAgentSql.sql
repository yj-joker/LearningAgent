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

-- 学习会话消息表
CREATE TABLE IF NOT EXISTS learning_session_messages (
                                                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息主键',
                                                        session_id BIGINT UNSIGNED NOT NULL COMMENT '所属学习会话 ID，逻辑外键',
                                                        role VARCHAR(20) NOT NULL COMMENT '消息角色：USER、ASSISTANT、TOOL',
                                                        content LONGTEXT DEFAULT NULL COMMENT '消息正文或工具执行结果',
                                                        tool_calls JSON DEFAULT NULL COMMENT 'ASSISTANT 发起的工具调用列表',
                                                        tool_call_id VARCHAR(128) DEFAULT NULL COMMENT 'TOOL 消息对应的工具调用 ID',
                                                        context_replayable TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许进入未来模型上下文',
                                                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                                                        PRIMARY KEY (id),
                                                        KEY idx_session_messages_session_id_id (session_id, id),

                                                        CONSTRAINT chk_learning_session_messages_role
                                                            CHECK (role IN ('USER', 'ASSISTANT', 'TOOL'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话消息表';

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

                                                sort_order INT UNSIGNED NOT NULL
                                                    COMMENT '知识点展示顺序，同章节内必须唯一，初始值*1000，重排阀值为100',

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
                                                UNIQUE KEY uk_knowledge_points_course_sort_order (chapter_id, sort_order),
                                                KEY idx_knowledge_points_course_id (course_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识点表';

ALTER TABLE knowledge_points ADD COLUMN created_by BIGINT COMMENT '创建者用户ID，NULL=系统/Agent';
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
ALTER TABLE knowledge_point_relations
    ADD COLUMN source ENUM('ADMIN', 'USER_SUGGESTED', 'AI_GENERATED') NOT NULL DEFAULT 'ADMIN'
        COMMENT '来源：管理员创建/用户建议/AI生成',
    ADD COLUMN status ENUM('PENDING', 'ACTIVE', 'REJECTED', 'DEPRECATED') NOT NULL DEFAULT 'ACTIVE'
        COMMENT '状态：待审核/活跃/已拒绝/已废弃',
    ADD COLUMN created_by BIGINT COMMENT '创建者用户ID，AI生成时为NULL',
    ADD COLUMN reviewed_by BIGINT COMMENT '审核者用户ID',
    ADD COLUMN reviewed_at DATETIME COMMENT '审核时间';

-- 知识库表
CREATE TABLE `knowledge_base` (
                                  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
                                      COMMENT '知识库ID',
                                  `course_id` BIGINT UNSIGNED NOT NULL
                                      COMMENT '课程ID',
                                  `name` VARCHAR(255) NOT NULL
                                      COMMENT '知识库名称',
                                  `description` TEXT NULL
                                      COMMENT '知识库描述',
                                  `owner_type` ENUM('USER', 'SYSTEM') NOT NULL DEFAULT 'USER'
                                      COMMENT '所有者类型：用户、系统',
                                  `visibility` ENUM('PRIVATE', 'PUBLIC') NOT NULL DEFAULT 'PRIVATE'
                                      COMMENT '可见性：私有、公开',
                                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      COMMENT '创建时间',
                                  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP
                                      COMMENT '更新时间',
                                  PRIMARY KEY (`id`),
                                  KEY `idx_knowledge_base_course_id` (`course_id`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识库表';


-- 文档表
CREATE TABLE `documents` (
                             `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT
                                 COMMENT '文档ID',
                             `kb_id` BIGINT UNSIGNED NOT NULL
                                 COMMENT '知识库ID',
                             `filename` VARCHAR(255) NOT NULL
                                 COMMENT '文件名',
                             `object_name` VARCHAR(1024) NOT NULL
                                 COMMENT '存储在minIO当中的文件唯一标识',
                             `status` ENUM('UPLOADED', 'PARSING', 'READY', 'FAILED') NOT NULL DEFAULT 'UPLOADED'
                                 COMMENT '文档状态：已上传、解析中、解析完成、解析失败',
                             `file_size` BIGINT UNSIGNED NOT NULL DEFAULT 0
                                 COMMENT '文件大小，单位：字节',
                             `mime_type` VARCHAR(128) NULL
                                 COMMENT '文件MIME类型',
                             `upload_user_id` BIGINT UNSIGNED NOT NULL
                                 COMMENT '上传用户ID',
                             `deleted_at` DATETIME DEFAULT NULL
                                 COMMENT '软删除时间：NULL-正常，非NULL-已删除',
                             `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 COMMENT '创建时间',
                             `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP
                                 COMMENT '更新时间',
                             PRIMARY KEY (`id`),
                             KEY `idx_documents_kb_id_created_at`
                                 (`kb_id`, `created_at`),
                             KEY `idx_documents_upload_user_id_created_at`
                                 (`upload_user_id`, `created_at`),
                             KEY `idx_documents_status_created_at`
                                 (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '文档表';


-- document_chunks 表
CREATE TABLE document_chunks (
                                 id BIGINT UNSIGNED NOT NULL COMMENT '文档切片ID，由应用雪花算法生成',
                                 document_id BIGINT UNSIGNED NOT NULL COMMENT '所属文档',
                                 chunk_index INT UNSIGNED NOT NULL COMMENT '切片序号，从 0 开始',
                                 content TEXT NOT NULL COMMENT '切片的文本内容（约 500 字）',
                                 vector_id VARCHAR(128) COMMENT '向量数据库里的 ID',
                                 metadata JSON COMMENT '{
  "page": 23,                    // 这个 chunk 来自第 23 页
  "chapter": "第三章 进程调度",    // 所属章节
  "section": "3.2 调度算法",      // 所属小节
  "start_char": 15000,           // 在全文中的起始字符位置
  "end_char": 15500,             // 结束位置
  "has_image": true,             // 这个 chunk 附近有图片（第 11 周加 OCR 时用）
  "has_table": false,            // 是否包含表格
  "is_code": false               // 是否是代码块（技术文档特有）
}',
                                 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 PRIMARY KEY (id),
                                 UNIQUE KEY uk_document_chunks_document_id_chunk_index
                                     (document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档切片表';

-- documents 表增加字段
ALTER TABLE documents
    ADD COLUMN chunk_count INT UNSIGNED DEFAULT 0 COMMENT '切片总数',
    ADD COLUMN parse_error TEXT COMMENT '解析失败时的错误信息';

-- 同一用户使用同一个上传请求幂等键时，只允许创建一条文档记录。
ALTER TABLE documents
    ADD COLUMN upload_request_id VARCHAR(64) NULL
        COMMENT '上传请求幂等键';

UPDATE documents
SET upload_request_id = CONCAT('legacy-', id)
WHERE upload_request_id IS NULL;

ALTER TABLE documents
    MODIFY COLUMN upload_request_id VARCHAR(64) NOT NULL
        COMMENT '上传请求幂等键',
    ADD UNIQUE KEY uk_documents_upload_request
        (upload_user_id, upload_request_id);

# 文档处理任务表
CREATE TABLE document_tasks (
                                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
                                document_id BIGINT UNSIGNED NOT NULL COMMENT '关联的文档',
                                user_id BIGINT UNSIGNED NOT NULL COMMENT '任务所属用户',
                                task_type VARCHAR(32) NOT NULL DEFAULT 'VECTORIZE' COMMENT '任务类型',
                                status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
                                retry_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已重试次数',
                                max_retries INT UNSIGNED NOT NULL DEFAULT 3 COMMENT '最大重试次数',
                                error_message TEXT COMMENT '失败原因',
                                started_at DATETIME COMMENT '开始处理时间',
                                completed_at DATETIME COMMENT '完成时间',
                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                PRIMARY KEY (id),
                                UNIQUE KEY uk_document_task_type (document_id, task_type),
                                KEY idx_document (document_id),
                                KEY idx_status_created_at (status, created_at)
) COMMENT '文档处理任务表';
