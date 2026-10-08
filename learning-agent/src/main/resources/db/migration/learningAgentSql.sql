CREATE DATABASE IF NOT EXISTS `learning_agent`;

-- 用户表
CREATE TABLE IF NOT EXISTS `users` (
                                       `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
                                       `username` VARCHAR(64) NOT NULL COMMENT '用户名',
                                       `password` VARCHAR(255) NOT NULL COMMENT '密码哈希值，不保存明文密码',
                                       `avatar_url` VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
                                       `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '用户角色：USER-普通用户，ADMIN-管理员',
                                       `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                       `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                       PRIMARY KEY (`id`),
                                       UNIQUE KEY `uk_users_username` (`username`),
                                       CONSTRAINT `chk_users_role` CHECK (`role` IN ('USER', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户表';

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
                                                     CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话表';

-- 课程学习进度：每个知识点一行，当前知识点由最先未确认的记录推导，不依赖模型记忆。
CREATE TABLE IF NOT EXISTS course_learning_point_progress (
    session_id BIGINT UNSIGNED NOT NULL COMMENT '学习会话 ID，逻辑外键',
    course_id BIGINT UNSIGNED NOT NULL COMMENT '课程 ID，逻辑外键',
    chapter_id BIGINT UNSIGNED NOT NULL COMMENT '章节 ID，逻辑外键',
    knowledge_point_id BIGINT UNSIGNED NOT NULL COMMENT '知识点 ID，逻辑外键',
    chapter_sort_order_snapshot INT UNSIGNED NOT NULL COMMENT '初始化时的章节顺序快照',
    knowledge_point_sort_order_snapshot INT UNSIGNED NOT NULL COMMENT '初始化时的知识点顺序快照',
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED' COMMENT '未开始、学习中、已确认、待复核或已移除',
    evidence_type VARCHAR(20) NULL COMMENT 'EXPLANATION、EXERCISE 或 BOTH',
    evidence_summary VARCHAR(2000) NULL COMMENT '已记录的学习证据摘要',
    assessment_reason VARCHAR(2000) NULL COMMENT '掌握判断理由，仅作为审批说明',
    course_updated_at_snapshot DATETIME NULL COMMENT '初始化进度时的课程更新时间快照',
    chapter_title_snapshot VARCHAR(255) NOT NULL COMMENT '初始化进度时的章节标题快照',
    knowledge_point_name_snapshot VARCHAR(255) NOT NULL COMMENT '初始化进度时的知识点名称快照',
    knowledge_point_description_snapshot LONGTEXT NULL COMMENT '初始化进度时的完整知识点描述快照',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '进度记录乐观锁版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (session_id, knowledge_point_id),
    KEY idx_course_learning_progress_session_order (session_id, chapter_id, knowledge_point_id),
    KEY idx_course_learning_progress_course (course_id, knowledge_point_id),
    CONSTRAINT chk_course_learning_progress_status
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'CONFIRMED', 'REVIEW_REQUIRED', 'REMOVED')),
    CONSTRAINT chk_course_learning_progress_evidence
        CHECK (evidence_type IS NULL OR evidence_type IN ('EXPLANATION', 'EXERCISE', 'BOTH')),
    CONSTRAINT chk_course_learning_progress_version CHECK (version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '课程知识点学习进度';

-- 任务计划：只保存目标与限制，不复制聊天历史，也不依赖已经产生审批记录。
CREATE TABLE IF NOT EXISTS agent_task_plans (
    plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立计划编号，跨多次执行保留',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户，由后端登录上下文提供',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属学习会话，逻辑外键',
    goal_number INT UNSIGNED NULL COMMENT '会话内固定目标序号，未接入会话时为空',
    goal VARCHAR(2000) NOT NULL COMMENT '任务目标',
    constraints_text VARCHAR(2000) NULL COMMENT '用户限制，不保存完整历史',
    learning_plan_draft_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建任务时绑定的长期计划引用',
    learning_plan_stage_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建任务时绑定的长期阶段引用',
    learning_plan_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建任务时看到的长期计划数据库版本',
    learning_plan_semantic_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建任务时看到的长期计划语义版本',
    learning_plan_scope VARCHAR(20) NOT NULL DEFAULT 'OUT_OF_PLAN' COMMENT 'CURRENT_STAGE、OTHER_STAGE 或 OUT_OF_PLAN',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '计划版本，后续用于拒绝旧版本覆盖',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_id),
    UNIQUE KEY uk_session_goal_number (session_id, goal_number),
    KEY idx_task_plan_owner_session (user_id, session_id, created_at),
    CONSTRAINT chk_task_plan_version CHECK (version >= 1),
    CONSTRAINT chk_task_learning_plan_version CHECK (learning_plan_version >= 0),
    CONSTRAINT chk_task_learning_plan_semantic_version CHECK (learning_plan_semantic_version >= 0),
    CONSTRAINT chk_task_learning_plan_scope CHECK (learning_plan_scope IN ('CURRENT_STAGE','OTHER_STAGE','OUT_OF_PLAN')),
    CONSTRAINT chk_task_goal_number CHECK (goal_number IS NULL OR goal_number >= 1),
    CONSTRAINT chk_task_plan_goal CHECK (CHAR_LENGTH(TRIM(goal)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 任务计划';

-- 步骤与计划通过 plan_id 关联；切换目标时不删除或重建步骤。
CREATE TABLE IF NOT EXISTS agent_task_steps (
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定步骤编号，调整顺序时不变',
    plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属计划，逻辑外键',
    position INT UNSIGNED NOT NULL COMMENT '执行顺序，从 1 开始，不作为步骤身份',
    description VARCHAR(1000) NOT NULL COMMENT '这一步要做什么',
    completion_criteria VARCHAR(1000) NOT NULL COMMENT '可观察的完成条件；学习类说明用户应达到的程度',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result_summary VARCHAR(2000) NULL COMMENT '确认方式、简短理由及用户原话，不代表掌握已验证',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (step_id),
    UNIQUE KEY uk_task_step_position (plan_id, position),
    CONSTRAINT chk_task_step_position CHECK (position >= 1),
    CONSTRAINT chk_task_step_description CHECK (CHAR_LENGTH(TRIM(description)) > 0),
    CONSTRAINT chk_task_step_criteria CHECK (CHAR_LENGTH(TRIM(completion_criteria)) > 0),
    CONSTRAINT chk_task_step_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','BLOCKED','CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 任务步骤';

-- 每个会话仅一行当前目标；其余已登记目标是搁置状态，步骤进度原样保留。
CREATE TABLE IF NOT EXISTS agent_session_focus (
    session_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    active_plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    learning_plan_draft_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '可选关联的跨会话学习计划',
    learning_plan_binding_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '学习计划关联独立版本',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '每次新增或切换目标递增，防止旧审批覆盖',
    next_goal_number INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '下一可用目标序号，不循环复用',
    CONSTRAINT chk_session_focus_version CHECK (version >= 1),
    CONSTRAINT chk_session_focus_number CHECK (next_goal_number >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话当前专注目标';

-- 跨会话复用的学习计划草案；草案始终未正式生效，正式执行仍由专注模式单独管理。
CREATE TABLE IF NOT EXISTS learning_plan_drafts (
    draft_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '草案稳定引用',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID，逻辑外键',
    title VARCHAR(200) NOT NULL COMMENT '草案标题',
    objective VARCHAR(2000) NOT NULL COMMENT '总体学习目标',
    learner_profile VARCHAR(1000) NULL COMMENT '学习者基础情况',
    weekly_commitment VARCHAR(500) NULL COMMENT '每周可投入时间',
    constraints_text VARCHAR(2000) NULL COMMENT '限制条件',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '生命周期：DRAFT、ACTIVE、ARCHIVED',
    source VARCHAR(20) NOT NULL COMMENT 'MANUAL 或 AGENT',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    semantic_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '忽略标点空白后的实质内容版本',
    semantic_change_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最近一次实质变化对应的数据库版本',
    previous_semantic_snapshot LONGTEXT NULL COMMENT '最近一次实质变化前的计划内容快照',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (draft_ref),
    KEY idx_learning_plan_draft_user_updated (user_id, updated_at),
    CONSTRAINT chk_learning_plan_draft_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_learning_plan_draft_source CHECK (source IN ('MANUAL', 'AGENT')),
    CONSTRAINT chk_learning_plan_draft_version CHECK (version >= 1),
    CONSTRAINT chk_learning_plan_draft_semantic_version CHECK (semantic_version >= 1),
    CONSTRAINT chk_learning_plan_draft_semantic_change_version CHECK (semantic_change_version >= 1),
    CONSTRAINT chk_learning_plan_draft_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_learning_plan_draft_objective CHECK (CHAR_LENGTH(TRIM(objective)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习计划草案';

-- 草案步骤与草案分表保存，step_ref 在更新时保持稳定。
CREATE TABLE IF NOT EXISTS learning_plan_draft_steps (
    step_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '步骤稳定引用',
    draft_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属草案引用',
    position INT UNSIGNED NOT NULL COMMENT '步骤顺序，从 1 开始',
    description VARCHAR(1000) NOT NULL COMMENT '步骤内容',
    completion_criteria VARCHAR(1000) NOT NULL COMMENT '完成条件',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (step_ref),
    UNIQUE KEY uk_learning_plan_draft_step_position (draft_ref, position),
    KEY idx_learning_plan_draft_step_draft (draft_ref, position),
    CONSTRAINT chk_learning_plan_draft_step_position CHECK (position >= 1),
    CONSTRAINT chk_learning_plan_draft_step_description CHECK (CHAR_LENGTH(TRIM(description)) > 0),
    CONSTRAINT chk_learning_plan_draft_step_criteria CHECK (CHAR_LENGTH(TRIM(completion_criteria)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习计划草案步骤';

-- 长期学习计划步骤的独立进度；计划内容修改不会直接覆盖学习证据。
CREATE TABLE IF NOT EXISTS learning_plan_step_progress (
    draft_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属学习计划引用',
    step_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属计划步骤引用',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID，逻辑外键',
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED' COMMENT '未开始、学习中、已确认',
    evidence_type VARCHAR(20) NULL COMMENT 'EXPLANATION、EXERCISE 或 BOTH',
    evidence_summary VARCHAR(2000) NULL COMMENT '最近一次已记录证据的简短摘要',
    assessment_reason VARCHAR(2000) NULL COMMENT '模型判断理由，仅作为审批说明',
    evaluated_plan_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '证据对应的计划数据库版本',
    evaluated_semantic_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '证据对应的计划语义版本',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '进度记录乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (draft_ref, step_ref),
    KEY idx_learning_plan_progress_user_draft (user_id, draft_ref),
    CONSTRAINT chk_learning_plan_progress_status CHECK (status IN ('NOT_STARTED','IN_PROGRESS','CONFIRMED')),
    CONSTRAINT chk_learning_plan_progress_evidence CHECK (evidence_type IS NULL OR evidence_type IN ('EXPLANATION','EXERCISE','BOTH')),
    CONSTRAINT chk_learning_plan_progress_version CHECK (version >= 1),
    CONSTRAINT chk_learning_plan_progress_plan_version CHECK (evaluated_plan_version >= 1),
    CONSTRAINT chk_learning_plan_progress_semantic_version CHECK (evaluated_semantic_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='长期学习计划步骤进度';

-- 用户长期记忆；只保存结构化事实，不替代完整会话消息。
CREATE TABLE IF NOT EXISTS user_memories (
                                             id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '长期记忆主键',
                                             user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID，逻辑外键',
                                             memory_key VARCHAR(128) NOT NULL COMMENT '稳定记忆名称，例如 learning_language',
                                             memory_topic VARCHAR(128) NOT NULL COMMENT '记忆主题，例如 learning_preference',
                                             memory_summary VARCHAR(1000) NOT NULL COMMENT '轻量索引摘要',
                                             memory_content LONGTEXT NOT NULL COMMENT '需要时召回的完整记忆正文',
                                             status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '记忆状态：ACTIVE、DELETED',
                                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                             updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                 ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                             PRIMARY KEY (id),
                                             KEY idx_user_memories_user_status_topic (user_id, status, memory_topic),
                                             UNIQUE KEY uk_user_memories_user_key (user_id, memory_key),

                                             CONSTRAINT chk_user_memories_status
                                                 CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户长期记忆表';

-- 会话级结构化记忆；只服务于当前学习会话，不保存完整聊天记录。
CREATE TABLE IF NOT EXISTS session_memories (
                                                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话记忆主键',
                                                session_id BIGINT UNSIGNED NOT NULL COMMENT '所属学习会话 ID，逻辑外键',
                                                memory_key VARCHAR(128) NOT NULL COMMENT '稳定记忆名称，例如 current_goal',
                                                memory_topic VARCHAR(128) NOT NULL COMMENT '记忆主题，例如 task_state',
                                                memory_summary VARCHAR(1000) NOT NULL COMMENT '轻量索引摘要',
                                                memory_content LONGTEXT NOT NULL COMMENT '需要时召回的完整记忆正文',
                                                status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '记忆状态：ACTIVE、DELETED',
                                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                                PRIMARY KEY (id),
                                                KEY idx_session_memories_session_status_topic (session_id, status, memory_topic),
                                                UNIQUE KEY uk_session_memories_session_key (session_id, memory_key),

                                                CONSTRAINT chk_session_memories_status
                                                    CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话结构化记忆表';

-- 记忆整理进度；当前开发阶段统一维护完整建表定义。
  CREATE TABLE IF NOT EXISTS memory_consolidation_state (
    scope VARCHAR(20) NOT NULL COMMENT 'USER 或 SESSION，两类记忆分别整理',
    owner_id BIGINT UNSIGNED NOT NULL COMMENT '对应的用户 ID 或会话 ID',
    change_count BIGINT NOT NULL DEFAULT 0 COMMENT '初始有效记录数加正常记忆变更次数',
    processed_count BIGINT NOT NULL DEFAULT 0 COMMENT '上次成功整理覆盖的变更次数',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '进度更新时间，不用于推断事实新旧',
    PRIMARY KEY (scope, owner_id),
    CONSTRAINT chk_memory_consolidation_scope CHECK (scope IN ('USER', 'SESSION')),
    CONSTRAINT chk_memory_consolidation_counts CHECK (processed_count >= 0 AND change_count >= processed_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
      COMMENT = '记忆整理触发与完成进度';

  -- 记忆写入审批申请；先保存候选和快照，用户批准后才修改记忆表。
  -- 完整建表定义只用于新建表；IF NOT EXISTS 不会给已经存在的旧表补字段。
  -- 更新审批代码后应核对实际表结构，不能仅靠重启后端或重复执行 CREATE 更新旧表。
  CREATE TABLE IF NOT EXISTS memory_approval_requests (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审批申请主键',
      user_id BIGINT UNSIGNED NOT NULL COMMENT '申请所属用户',
      session_id BIGINT UNSIGNED NOT NULL COMMENT '申请所属学习会话',
      approval_type VARCHAR(24) NOT NULL DEFAULT 'CHANGE' COMMENT 'CHANGE 或 CONSOLIDATION',
      operation VARCHAR(20) NULL COMMENT '单条变更的操作；整理方案为空',
      scope VARCHAR(20) NOT NULL COMMENT 'USER 或 SESSION',
      candidate_json JSON NOT NULL COMMENT '按申请类型保存单条候选或整批方案',
      target_snapshot_json JSON NOT NULL COMMENT '单条目标快照或完整整理快照',
      snapshot_change_count BIGINT NULL COMMENT '整理看到的累计变更数',
      snapshot_processed_count BIGINT NULL COMMENT '整理看到的已处理数',
      status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、APPROVED、REJECTED 或 STALE',
      consolidation_owner_id BIGINT UNSIGNED GENERATED ALWAYS AS
          (CASE WHEN approval_type = 'CONSOLIDATION' THEN IF(scope = 'USER', user_id, session_id) ELSE NULL END) STORED,
      pending_consolidation_key VARCHAR(80) GENERATED ALWAYS AS
          (CASE WHEN approval_type = 'CONSOLIDATION' AND status = 'PENDING'
           THEN CONCAT(scope, ':', IF(scope = 'USER', user_id, session_id)) ELSE NULL END) STORED,
      decision_reason VARCHAR(500) DEFAULT NULL COMMENT '用户拒绝或审批说明',
      created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
      updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
      decided_at DATETIME(6) DEFAULT NULL,
      PRIMARY KEY (id),
      KEY idx_memory_approval_user_status (user_id, status, created_at),
      KEY idx_memory_approval_session_status (session_id, status, created_at),
      UNIQUE KEY uk_pending_consolidation (pending_consolidation_key),
      UNIQUE KEY uk_consolidation_version (scope, consolidation_owner_id, snapshot_change_count, snapshot_processed_count),
      CONSTRAINT chk_memory_approval_type CHECK (approval_type IN ('CHANGE', 'CONSOLIDATION')),
      CONSTRAINT chk_memory_approval_payload CHECK
          ((approval_type = 'CHANGE' AND operation IS NOT NULL AND snapshot_change_count IS NULL AND snapshot_processed_count IS NULL)
           OR (approval_type = 'CONSOLIDATION' AND operation IS NULL AND snapshot_change_count IS NOT NULL
               AND snapshot_processed_count IS NOT NULL AND snapshot_processed_count >= 0 AND snapshot_change_count > snapshot_processed_count)),
      CONSTRAINT chk_memory_approval_operation CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
      CONSTRAINT chk_memory_approval_scope CHECK (scope IN ('USER', 'SESSION')),
      CONSTRAINT chk_memory_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'STALE'))
  ) ENGINE = InnoDB
      DEFAULT CHARSET = utf8mb4
      COLLATE = utf8mb4_unicode_ci
      COMMENT = '结构化记忆审批申请';

-- 通用审批：运行检查点 + 本次工具调用的审批，两者不依赖记忆业务。
-- 手动执行此增量脚本；不删除旧审批表或修改旧申请，已有旧申请仍走旧接口。
CREATE TABLE IF NOT EXISTS agent_approval_runs (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '整个逻辑任务编号，恢复时不变',
    user_id BIGINT UNSIGNED NOT NULL,
    session_id BIGINT UNSIGNED NOT NULL,
    batch_number INT UNSIGNED NOT NULL COMMENT '每次暂停加一，旧批准不能授权新批次',
    status VARCHAR(32) NOT NULL,
    checkpoint_json JSON NOT NULL COMMENT '暂停时保存完整上下文，完成后只保留模式元数据',
    answer MEDIUMTEXT NULL COMMENT '最终答案，重复恢复直接返回',
    active_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status IN ('WAITING_APPROVAL','APPROVAL_RESOLVED','RUNNING') THEN session_id ELSE NULL END) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_agent_approval_active_session (active_session_id),
    KEY idx_agent_approval_owner (user_id, status, created_at),
    CONSTRAINT chk_agent_run_state CHECK (status IN ('WAITING_APPROVAL','APPROVAL_RESOLVED','RUNNING','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用 Agent 暂停与恢复检查点';

CREATE TABLE IF NOT EXISTS agent_tool_approvals (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_number INT UNSIGNED NOT NULL,
    tool_call_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    -- 用 LONGTEXT 保留原参数字符串，JSON 列会规范化空格，影响逐字核对已批准参数。
    arguments_json LONGTEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (run_id, batch_number, tool_call_id),
    CONSTRAINT fk_tool_approval_run FOREIGN KEY (run_id) REFERENCES agent_approval_runs(run_id),
    CONSTRAINT chk_tool_approval_state CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT chk_tool_approval_args CHECK (JSON_VALID(arguments_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用工具审批决定，不代表执行结果';

-- 学习会话消息表
CREATE TABLE IF NOT EXISTS learning_session_messages (
                                                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息主键',
                                                        session_id BIGINT UNSIGNED NOT NULL COMMENT '所属学习会话 ID，逻辑外键',
                                                        agent_mode VARCHAR(16) DEFAULT NULL COMMENT '上下文来源模式：CHAT、FOCUS 或 COURSE；NULL 表示旧记录未分类',
                                                        role VARCHAR(20) NOT NULL COMMENT '消息角色：USER、ASSISTANT、TOOL',
                                                        content LONGTEXT DEFAULT NULL COMMENT '消息正文或工具执行结果',
                                                        context_content LONGTEXT DEFAULT NULL COMMENT '发送给模型的工具结果压缩副本',
                                                        tool_calls JSON DEFAULT NULL COMMENT 'ASSISTANT 发起的工具调用列表',
                                                        tool_call_id VARCHAR(128) DEFAULT NULL COMMENT 'TOOL 消息对应的工具调用 ID',
                                                        context_replayable TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许进入未来模型上下文',
                                                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                                                        PRIMARY KEY (id),
                                                        KEY idx_session_messages_session_id_id (session_id, id),
                                                        KEY idx_session_messages_mode (session_id, agent_mode, id),

                                                        CONSTRAINT chk_learning_session_messages_role
                                                            CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
                                                        CONSTRAINT chk_learning_session_messages_mode
                                                            CHECK (agent_mode IS NULL OR agent_mode IN ('CHAT', 'FOCUS', 'COURSE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话消息表';

-- 学习会话摘要表；原始消息不删除，摘要只记录覆盖边界和当前压缩结果。
CREATE TABLE IF NOT EXISTS learning_session_summaries (
                                                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '摘要主键',
                                                        session_id BIGINT UNSIGNED NOT NULL COMMENT '所属学习会话 ID，逻辑外键',
                                                        agent_mode VARCHAR(16) NOT NULL COMMENT '摘要覆盖的上下文模式：CHAT、FOCUS 或 COURSE',
                                                        summary_content LONGTEXT NOT NULL COMMENT '发送给模型的历史摘要',
                                                        covered_until_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '摘要覆盖到的消息主键',
                                                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '摘要生成时间',

                                                        PRIMARY KEY (id),
                                                        KEY idx_session_summaries_session_mode_id (session_id, agent_mode, id),
                                                        CONSTRAINT chk_learning_session_summaries_mode
                                                            CHECK (agent_mode IN ('CHAT', 'FOCUS', 'COURSE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '学习会话上下文摘要表';

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
