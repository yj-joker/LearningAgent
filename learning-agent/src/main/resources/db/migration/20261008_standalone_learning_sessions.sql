-- 已有数据库的增量迁移：可重复执行，不删除或重建表，不改已有会话编号与消息。
-- 项目目前没有自动迁移框架；部署前在目标库手动执行本脚本，再启动新版后端。
-- COURSE 表示已有会话来自课程入口，旧 CHAT/FOCUS 消息仍保留原有 agent_mode。
ALTER TABLE learning_sessions
    MODIFY COLUMN course_id BIGINT UNSIGNED NULL COMMENT '课程 ID；独立问答或专注会话为空';

-- 只在缺少列时增加；重复执行不会把已经保存的 CHAT/FOCUS 重写为 COURSE。
SET @standalone_session_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'session_mode') = 0,
    'ALTER TABLE learning_sessions ADD COLUMN session_mode VARCHAR(20) NOT NULL DEFAULT ''COURSE'' COMMENT ''课程、问答或专注模式'' AFTER session_title',
    'SELECT ''session_mode 已存在，保留现有值'' AS migration_status');
PREPARE standalone_session_statement FROM @standalone_session_ddl;
EXECUTE standalone_session_statement;
DEALLOCATE PREPARE standalone_session_statement;

-- 模式校验约束也按名称检查，只添加缺少的约束。
SET @standalone_session_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions'
       AND CONSTRAINT_NAME = 'chk_learning_sessions_mode') = 0,
    'ALTER TABLE learning_sessions ADD CONSTRAINT chk_learning_sessions_mode CHECK (session_mode IN (''CHAT'', ''FOCUS'', ''COURSE''))',
    'SELECT ''模式约束已存在'' AS migration_status');
PREPARE standalone_session_statement FROM @standalone_session_ddl;
EXECUTE standalone_session_statement;
DEALLOCATE PREPARE standalone_session_statement;

-- 独立会话不填课程编号，课程会话必须使用 COURSE 标记其来源。
SET @standalone_session_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions'
       AND CONSTRAINT_NAME = 'chk_learning_sessions_course_mode') = 0,
    'ALTER TABLE learning_sessions ADD CONSTRAINT chk_learning_sessions_course_mode CHECK ((course_id IS NULL AND session_mode IN (''CHAT'', ''FOCUS'')) OR (course_id IS NOT NULL AND session_mode = ''COURSE''))',
    'SELECT ''课程与模式约束已存在'' AS migration_status');
PREPARE standalone_session_statement FROM @standalone_session_ddl;
EXECUTE standalone_session_statement;
DEALLOCATE PREPARE standalone_session_statement;

SET @standalone_session_ddl = NULL;
