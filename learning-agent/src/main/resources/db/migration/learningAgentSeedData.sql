-- ============================================================
-- Learning Agent 种子数据（独立于建表 SQL）
--
-- 执行前提：先执行 learningAgentSql.sql，确保表结构已经存在。
-- 本脚本会创建 7 门公开课程、15 个章节、35 个知识点、30 条关系和 7 条学习会话。
--
-- 本地演示账号：
--   seed_admin   / 123456   （ADMIN）
--   seed_student / 123456   （USER）
--
-- 兼容上一版种子数据：如果数据库中存在旧版的“两门种子课程”，脚本会先删除
-- 那两门课程及其专属章节、知识点、关系和学习会话，再按下面的 7 门课程重建。
-- 只匹配旧版固定课程名和 seed_admin 所有者，不会删除其他业务数据。
-- ============================================================

USE `learning_agent`;

-- ① 创建/更新本地演示用户。
-- BCrypt 哈希对应明文密码 123456。
SET @seed_password = '$2b$12$/JDh/kXPiu/7WlGMJNzjCOSJIa758nKg7BUksgLYSo3yh4tnjONnq';

INSERT INTO `user` (username, password, avatar_url, role)
VALUES ('seed_admin', @seed_password, NULL, 'ADMIN')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    role = 'ADMIN',
    id = LAST_INSERT_ID(id);

INSERT INTO `user` (username, password, avatar_url, role)
VALUES ('seed_student', @seed_password, NULL, 'USER')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    role = 'USER',
    id = LAST_INSERT_ID(id);

SET @seed_admin_id = (
    SELECT id FROM `user` WHERE username = 'seed_admin' LIMIT 1
);
SET @seed_student_id = (
    SELECT id FROM `user` WHERE username = 'seed_student' LIMIT 1
);

-- ② 清理上一版由本项目创建的两门种子课程，避免旧数据和新数据重复。
SET @legacy_os_course_id = (
    SELECT id FROM courses
    WHERE user_id = @seed_admin_id
      AND course_name = '操作系统（种子课程）'
    ORDER BY id LIMIT 1
);
SET @legacy_net_course_id = (
    SELECT id FROM courses
    WHERE user_id = @seed_admin_id
      AND course_name = '计算机网络（种子课程）'
    ORDER BY id LIMIT 1
);

DELETE relation_row
FROM knowledge_point_relations relation_row
LEFT JOIN knowledge_points from_point
       ON from_point.id = relation_row.from_point_id
LEFT JOIN knowledge_points to_point
       ON to_point.id = relation_row.to_point_id
WHERE from_point.course_id IN (@legacy_os_course_id, @legacy_net_course_id)
   OR to_point.course_id IN (@legacy_os_course_id, @legacy_net_course_id);

DELETE FROM learning_sessions
WHERE course_id IN (@legacy_os_course_id, @legacy_net_course_id);

DELETE FROM knowledge_points
WHERE course_id IN (@legacy_os_course_id, @legacy_net_course_id);

DELETE FROM chapters
WHERE course_id IN (@legacy_os_course_id, @legacy_net_course_id);

DELETE FROM courses
WHERE id IN (@legacy_os_course_id, @legacy_net_course_id);

-- ③ 课程定义：7 门全部为公开课程。
DROP TEMPORARY TABLE IF EXISTS seed_course_defs;
CREATE TEMPORARY TABLE seed_course_defs (
    course_key VARCHAR(64) NOT NULL PRIMARY KEY,
    course_name VARCHAR(128) NOT NULL,
    difficulty_level TINYINT UNSIGNED NOT NULL,
    learning_outline JSON NOT NULL,
    session_status VARCHAR(20) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO seed_course_defs (
    course_key, course_name, difficulty_level, learning_outline, session_status
)
VALUES
('os_process', '操作系统：进程与系统调用', 4,
 JSON_OBJECT('topics', JSON_ARRAY('进程', '用户态', '内核态', '系统调用')), 'COMPLETED'),
('os_memory', '操作系统：虚拟内存', 4,
 JSON_OBJECT('topics', JSON_ARRAY('虚拟内存', '页表', '缺页异常')), 'ACTIVE'),
('java_concurrency', 'Java并发：线程与锁', 5,
 JSON_OBJECT('topics', JSON_ARRAY('线程', '上下文切换', 'CPU调度', '互斥锁', '死锁')), 'ACTIVE'),
('http_basics', '计算机网络：HTTP基础', 3,
 JSON_OBJECT('topics', JSON_ARRAY('HTTP报文', 'GET方法', 'POST方法', 'HTTP状态码')), 'COMPLETED'),
('web_security', 'Web安全：会话与常见攻击', 4,
 JSON_OBJECT('topics', JSON_ARRAY('Cookie', 'Session', 'JWT', 'XSS', 'CSRF')), 'ACTIVE'),
('http_performance', 'Web性能：缓存与HTTP演进', 4,
 JSON_OBJECT('topics', JSON_ARRAY('HTTP强缓存', 'HTTP协商缓存', 'Keep-Alive', 'HTTP/2多路复用')), 'ACTIVE'),
('tcp_networking', '计算机网络：TCP可靠传输', 5,
 JSON_OBJECT('topics', JSON_ARRAY('TCP协议', 'TCP三次握手', 'TCP四次挥手', 'TCP序列号', 'Socket', 'TCP慢启动', 'TIME_WAIT')), 'ACTIVE');

INSERT INTO courses (
    user_id, course_name, difficulty_level, publisher_id, course_type, learning_outline
)
SELECT
    @seed_admin_id,
    definition.course_name,
    definition.difficulty_level,
    @seed_admin_id,
    'PUBLISHED',
    definition.learning_outline
FROM seed_course_defs definition
LEFT JOIN courses existing_course
       ON existing_course.user_id = @seed_admin_id
      AND existing_course.course_name = definition.course_name
WHERE existing_course.id IS NULL;

UPDATE courses course_row
JOIN seed_course_defs definition
  ON definition.course_name = course_row.course_name
SET course_row.user_id = @seed_admin_id,
    course_row.publisher_id = @seed_admin_id,
    course_row.difficulty_level = definition.difficulty_level,
    course_row.course_type = 'PUBLISHED',
    course_row.learning_outline = definition.learning_outline
WHERE course_row.user_id = @seed_admin_id;

-- ④ 章节定义：每门课程至少两个章节，TCP 课程拆成三个章节。
DROP TEMPORARY TABLE IF EXISTS seed_chapter_defs;
CREATE TEMPORARY TABLE seed_chapter_defs (
    course_key VARCHAR(64) NOT NULL,
    chapter_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    PRIMARY KEY (course_key, chapter_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO seed_chapter_defs (course_key, chapter_key, title, sort_order)
VALUES
('os_process', 'process_model', '进程模型', 1000),
('os_process', 'privilege_syscall', '特权级与系统调用', 2000),
('os_memory', 'virtual_address', '虚拟地址空间', 1000),
('os_memory', 'page_fault', '分页与缺页处理', 2000),
('java_concurrency', 'thread_scheduling', '线程与调度', 1000),
('java_concurrency', 'thread_sync', '线程工具与同步', 2000),
('http_basics', 'message', 'HTTP消息结构', 1000),
('http_basics', 'methods_status', '请求方法与状态码', 2000),
('web_security', 'session_auth', '会话与身份认证', 1000),
('web_security', 'web_attacks', 'Web安全攻击', 2000),
('http_performance', 'http_cache', 'HTTP缓存', 1000),
('http_performance', 'connection_multiplexing', '连接复用与多路复用', 2000),
('tcp_networking', 'connection_lifecycle', 'TCP连接管理', 1000),
('tcp_networking', 'reliable_congestion', '可靠传输与拥塞控制', 2000),
('tcp_networking', 'socket_api', 'Socket编程接口', 3000);

INSERT INTO chapters (course_id, title, sort_order)
SELECT course_row.id, definition.title, definition.sort_order
FROM seed_chapter_defs definition
JOIN seed_course_defs course_definition
  ON course_definition.course_key = definition.course_key
JOIN courses course_row
  ON course_row.course_name = course_definition.course_name
 AND course_row.user_id = @seed_admin_id
LEFT JOIN chapters existing_chapter
  ON existing_chapter.course_id = course_row.id
 AND existing_chapter.title = definition.title
WHERE existing_chapter.id IS NULL;

UPDATE chapters chapter_row
JOIN seed_chapter_defs definition
  ON chapter_row.title = definition.title
JOIN seed_course_defs course_definition
  ON course_definition.course_key = definition.course_key
JOIN courses course_row
  ON course_row.id = chapter_row.course_id
 AND course_row.course_name = course_definition.course_name
SET chapter_row.sort_order = definition.sort_order;

-- ⑤ 35 个知识点，按主题分配到上面的 7 门公开课程。
DROP TEMPORARY TABLE IF EXISTS seed_knowledge_point_defs;
CREATE TEMPORARY TABLE seed_knowledge_point_defs (
    course_key VARCHAR(64) NOT NULL,
    chapter_key VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    sort_order INT UNSIGNED NOT NULL,
    description TEXT NOT NULL,
    PRIMARY KEY (course_key, chapter_key, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO seed_knowledge_point_defs (
    course_key, chapter_key, name, sort_order, description
)
VALUES
('os_process', 'process_model', '进程', 1000, '操作系统分配资源和隔离程序的基本单位'),
('os_process', 'privilege_syscall', '用户态', 1000, 'CPU Ring 3，不能执行特权指令'),
('os_process', 'privilege_syscall', '内核态', 2000, 'CPU Ring 0，可执行特权指令并访问内核资源'),
('os_process', 'privilege_syscall', '系统调用', 3000, '用户态程序请求内核服务的机制，会触发模式切换'),
('os_memory', 'virtual_address', '虚拟内存', 1000, '每个进程独立的虚拟地址空间，由 MMU 映射到物理内存'),
('os_memory', 'virtual_address', '页表', 2000, '虚拟地址到物理地址的映射表'),
('os_memory', 'page_fault', '缺页异常', 1000, '访问的虚拟页不在物理内存时触发，由操作系统从磁盘加载'),
('java_concurrency', 'thread_scheduling', '线程', 1000, '进程内的执行单元，共享进程资源，独占栈和寄存器'),
('java_concurrency', 'thread_scheduling', '上下文切换', 2000, '保存当前线程寄存器状态，恢复下一个线程状态'),
('java_concurrency', 'thread_scheduling', '模式切换', 3000, '用户态到内核态的切换，保存寄存器但不更换执行主体'),
('java_concurrency', 'thread_scheduling', 'CPU调度', 4000, '决定哪个线程获得 CPU 执行权，基于时钟中断实现抢占'),
('java_concurrency', 'thread_scheduling', '时钟中断', 5000, '定时器触发的硬件中断，是抢占式调度的根基'),
('java_concurrency', 'thread_sync', 'ThreadLocal', 1000, '每个 Thread 持有 ThreadLocalMap，键为弱引用，值为强引用'),
('java_concurrency', 'thread_sync', '互斥锁', 2000, '同一时刻只允许一个线程持有，用于保护临界区'),
('java_concurrency', 'thread_sync', '死锁', 3000, '多个线程互相等待对方释放锁，形成循环等待'),
('http_basics', 'message', 'HTTP报文', 1000, '请求行或响应行、Header、空行和 Body 组成的消息结构'),
('http_basics', 'methods_status', 'GET方法', 1000, '获取资源，通常是幂等且安全的，参数通常位于 URL'),
('http_basics', 'methods_status', 'POST方法', 2000, '提交数据，通常是非幂等的，请求数据通常位于 Body'),
('http_basics', 'methods_status', 'HTTP状态码', 3000, '1xx 信息、2xx 成功、3xx 重定向、4xx 客户端错误、5xx 服务端错误'),
('web_security', 'session_auth', 'Cookie', 1000, '浏览器自动携带的键值对，常用于状态管理'),
('web_security', 'session_auth', 'Session', 2000, '服务端存储的会话数据，Session ID 通常通过 Cookie 传输'),
('web_security', 'session_auth', 'JWT', 3000, 'Header.Payload.Signature 结构，签名用于防篡改，常用于无状态认证'),
('web_security', 'web_attacks', 'XSS', 1000, '跨站脚本攻击，将恶意脚本注入页面并在受害者浏览器执行'),
('web_security', 'web_attacks', 'CSRF', 2000, '跨站请求伪造，利用浏览器自动携带 Cookie 发起操作'),
('http_performance', 'http_cache', 'HTTP强缓存', 1000, 'Cache-Control: max-age=N，缓存未过期时不向服务端发请求'),
('http_performance', 'http_cache', 'HTTP协商缓存', 2000, '通过 ETag/If-None-Match 等字段让服务端返回 200 或 304'),
('http_performance', 'connection_multiplexing', 'Keep-Alive', 1000, '复用 TCP 连接，避免重复握手和慢启动的代价'),
('http_performance', 'connection_multiplexing', 'HTTP/2多路复用', 2000, '使用二进制帧和 Stream ID，在同一 TCP 连接上并发多个请求'),
('tcp_networking', 'connection_lifecycle', 'TCP协议', 1000, '可靠、有序、面向连接的字节流协议'),
('tcp_networking', 'connection_lifecycle', 'TCP三次握手', 2000, 'SYN → SYN+ACK → ACK，建立连接并同步初始序列号'),
('tcp_networking', 'connection_lifecycle', 'TCP四次挥手', 3000, 'FIN → ACK → FIN → ACK，全双工连接的两个方向分别关闭'),
('tcp_networking', 'connection_lifecycle', 'TIME_WAIT', 4000, '主动关闭方等待 2MSL，确保对方收到最后一个 ACK'),
('tcp_networking', 'reliable_congestion', 'TCP序列号', 1000, '保证有序传输、确认和可靠重传的基础'),
('tcp_networking', 'reliable_congestion', 'TCP慢启动', 2000, '新连接的发送速率从较低值开始逐步增长，以避免网络拥塞'),
('tcp_networking', 'socket_api', 'Socket', 1000, '操作系统提供给应用程序使用网络的接口，通常通过文件描述符操作');

INSERT INTO knowledge_points (
    course_id, chapter_id, name, sort_order, description, created_by
)
SELECT
    course_row.id,
    chapter_row.id,
    definition.name,
    definition.sort_order,
    definition.description,
    @seed_admin_id
FROM seed_knowledge_point_defs definition
JOIN seed_course_defs course_definition
  ON course_definition.course_key = definition.course_key
JOIN courses course_row
  ON course_row.course_name = course_definition.course_name
 AND course_row.user_id = @seed_admin_id
JOIN seed_chapter_defs chapter_definition
  ON chapter_definition.course_key = definition.course_key
 AND chapter_definition.chapter_key = definition.chapter_key
JOIN chapters chapter_row
  ON chapter_row.course_id = course_row.id
 AND chapter_row.title = chapter_definition.title
ON DUPLICATE KEY UPDATE
    description = VALUES(description),
    created_by = VALUES(created_by),
    updated_at = CURRENT_TIMESTAMP;

-- ⑥ 26 条前置关系 + 4 条易混淆关系，共 30 条。
DROP TEMPORARY TABLE IF EXISTS seed_relation_defs;
CREATE TEMPORARY TABLE seed_relation_defs (
    from_course_key VARCHAR(64) NOT NULL,
    from_name VARCHAR(255) NOT NULL,
    to_course_key VARCHAR(64) NOT NULL,
    to_name VARCHAR(255) NOT NULL,
    relation_type VARCHAR(20) NOT NULL,
    PRIMARY KEY (from_course_key, from_name, to_course_key, to_name, relation_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO seed_relation_defs (
    from_course_key, from_name, to_course_key, to_name, relation_type
)
VALUES
('os_process', '进程', 'java_concurrency', '线程', 'PREREQUISITE'),
('java_concurrency', '线程', 'java_concurrency', '上下文切换', 'PREREQUISITE'),
('os_memory', '虚拟内存', 'os_memory', '页表', 'PREREQUISITE'),
('os_memory', '页表', 'os_memory', '缺页异常', 'PREREQUISITE'),
('os_process', '用户态', 'java_concurrency', '模式切换', 'PREREQUISITE'),
('os_process', '内核态', 'java_concurrency', '模式切换', 'PREREQUISITE'),
('os_process', '用户态', 'os_process', '系统调用', 'PREREQUISITE'),
('os_process', '内核态', 'os_process', '系统调用', 'PREREQUISITE'),
('java_concurrency', '线程', 'java_concurrency', 'CPU调度', 'PREREQUISITE'),
('java_concurrency', '时钟中断', 'java_concurrency', 'CPU调度', 'PREREQUISITE'),
('java_concurrency', '互斥锁', 'java_concurrency', '死锁', 'PREREQUISITE'),
('http_basics', 'HTTP报文', 'http_basics', 'GET方法', 'PREREQUISITE'),
('http_basics', 'HTTP报文', 'http_basics', 'POST方法', 'PREREQUISITE'),
('http_basics', 'HTTP报文', 'http_basics', 'HTTP状态码', 'PREREQUISITE'),
('web_security', 'Cookie', 'web_security', 'Session', 'PREREQUISITE'),
('http_basics', 'HTTP报文', 'web_security', 'JWT', 'PREREQUISITE'),
('web_security', 'Cookie', 'web_security', 'CSRF', 'PREREQUISITE'),
('http_basics', 'HTTP报文', 'web_security', 'XSS', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'tcp_networking', 'TCP三次握手', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'tcp_networking', 'TCP四次挥手', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'tcp_networking', 'TCP序列号', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'tcp_networking', 'Socket', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'tcp_networking', 'TCP慢启动', 'PREREQUISITE'),
('tcp_networking', 'TCP四次挥手', 'tcp_networking', 'TIME_WAIT', 'PREREQUISITE'),
('tcp_networking', 'TCP协议', 'http_performance', 'Keep-Alive', 'PREREQUISITE'),
('http_performance', 'Keep-Alive', 'http_performance', 'HTTP/2多路复用', 'PREREQUISITE'),
('java_concurrency', '上下文切换', 'java_concurrency', '模式切换', 'CONFUSABLE'),
('web_security', 'Session', 'web_security', 'JWT', 'CONFUSABLE'),
('web_security', 'XSS', 'web_security', 'CSRF', 'CONFUSABLE'),
('http_basics', 'GET方法', 'http_basics', 'POST方法', 'CONFUSABLE');

-- MySQL 不能在同一条语句中两次打开同一个临时表，因此复制两份课程定义
-- 分别用于关系的起点课程和终点课程连接。
DROP TEMPORARY TABLE IF EXISTS seed_from_course_defs;
DROP TEMPORARY TABLE IF EXISTS seed_to_course_defs;
CREATE TEMPORARY TABLE seed_from_course_defs LIKE seed_course_defs;
CREATE TEMPORARY TABLE seed_to_course_defs LIKE seed_course_defs;
INSERT INTO seed_from_course_defs SELECT * FROM seed_course_defs;
INSERT INTO seed_to_course_defs SELECT * FROM seed_course_defs;

INSERT INTO knowledge_point_relations (
    from_point_id, to_point_id, relation_type, status, source,
    created_by, reviewed_by, reviewed_at
)
SELECT
    CASE
        WHEN definition.relation_type = 'CONFUSABLE'
            THEN LEAST(from_point.id, to_point.id)
        ELSE from_point.id
    END,
    CASE
        WHEN definition.relation_type = 'CONFUSABLE'
            THEN GREATEST(from_point.id, to_point.id)
        ELSE to_point.id
    END,
    definition.relation_type,
    'ACTIVE',
    'ADMIN',
    @seed_admin_id,
    @seed_admin_id,
    NOW()
FROM seed_relation_defs definition
JOIN seed_from_course_defs from_course_definition
  ON from_course_definition.course_key = definition.from_course_key
JOIN seed_to_course_defs to_course_definition
  ON to_course_definition.course_key = definition.to_course_key
JOIN courses from_course
  ON from_course.course_name = from_course_definition.course_name
 AND from_course.user_id = @seed_admin_id
JOIN courses to_course
  ON to_course.course_name = to_course_definition.course_name
 AND to_course.user_id = @seed_admin_id
JOIN knowledge_points from_point
  ON from_point.course_id = from_course.id
 AND from_point.name = definition.from_name
JOIN knowledge_points to_point
  ON to_point.course_id = to_course.id
 AND to_point.name = definition.to_name
WHERE from_point.id <> to_point.id
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    source = 'ADMIN',
    created_by = @seed_admin_id,
    reviewed_by = @seed_admin_id,
    reviewed_at = NOW();

-- ⑦ 为每门公开课程创建一条学习会话。
INSERT INTO learning_sessions (course_id, user_id, session_title, status)
SELECT
    course_row.id,
    @seed_student_id,
    CONCAT(course_row.course_name, '：种子学习计划'),
    definition.session_status
FROM seed_course_defs definition
JOIN courses course_row
  ON course_row.course_name = definition.course_name
 AND course_row.user_id = @seed_admin_id
LEFT JOIN learning_sessions existing_session
  ON existing_session.course_id = course_row.id
 AND existing_session.user_id = @seed_student_id
 AND existing_session.session_title = CONCAT(course_row.course_name, '：种子学习计划')
WHERE existing_session.id IS NULL;

-- ⑧ 验证结果：应得到 7 门公开课程、15 个章节、35 个知识点、30 条关系和 7 条会话。
SELECT 'seed_users' AS metric, COUNT(*) AS total
FROM `user`
WHERE username IN ('seed_admin', 'seed_student')
UNION ALL
SELECT 'seed_public_courses', COUNT(*)
FROM courses course_row
WHERE course_row.user_id = @seed_admin_id
  AND course_row.course_type = 'PUBLISHED'
  AND course_row.course_name IN (
      '操作系统：进程与系统调用', '操作系统：虚拟内存', 'Java并发：线程与锁',
      '计算机网络：HTTP基础', 'Web安全：会话与常见攻击',
      'Web性能：缓存与HTTP演进', '计算机网络：TCP可靠传输'
  )
UNION ALL
SELECT 'seed_chapters', COUNT(*)
FROM chapters chapter_row
JOIN courses course_row ON course_row.id = chapter_row.course_id
WHERE course_row.user_id = @seed_admin_id
  AND course_row.course_name IN (
      '操作系统：进程与系统调用', '操作系统：虚拟内存', 'Java并发：线程与锁',
      '计算机网络：HTTP基础', 'Web安全：会话与常见攻击',
      'Web性能：缓存与HTTP演进', '计算机网络：TCP可靠传输'
  )
UNION ALL
SELECT 'seed_knowledge_points', COUNT(*)
FROM knowledge_points point_row
JOIN courses course_row ON course_row.id = point_row.course_id
WHERE course_row.user_id = @seed_admin_id
  AND course_row.course_name IN (
      '操作系统：进程与系统调用', '操作系统：虚拟内存', 'Java并发：线程与锁',
      '计算机网络：HTTP基础', 'Web安全：会话与常见攻击',
      'Web性能：缓存与HTTP演进', '计算机网络：TCP可靠传输'
  )
UNION ALL
SELECT 'seed_relations', COUNT(*)
FROM knowledge_point_relations relation_row
JOIN knowledge_points from_point ON from_point.id = relation_row.from_point_id
JOIN knowledge_points to_point ON to_point.id = relation_row.to_point_id
JOIN courses from_course ON from_course.id = from_point.course_id
JOIN courses to_course ON to_course.id = to_point.course_id
WHERE relation_row.created_by = @seed_admin_id
  AND relation_row.status = 'ACTIVE'
  AND from_course.course_name IN (
      '操作系统：进程与系统调用', '操作系统：虚拟内存', 'Java并发：线程与锁',
      '计算机网络：HTTP基础', 'Web安全：会话与常见攻击',
      'Web性能：缓存与HTTP演进', '计算机网络：TCP可靠传输'
  )
  AND to_course.course_name IN (
      '操作系统：进程与系统调用', '操作系统：虚拟内存', 'Java并发：线程与锁',
      '计算机网络：HTTP基础', 'Web安全：会话与常见攻击',
      'Web性能：缓存与HTTP演进', '计算机网络：TCP可靠传输'
  )
UNION ALL
SELECT 'seed_sessions', COUNT(*)
FROM learning_sessions session_row
WHERE session_row.user_id = @seed_student_id
  AND session_row.session_title LIKE '%：种子学习计划';

DROP TEMPORARY TABLE IF EXISTS seed_relation_defs;
DROP TEMPORARY TABLE IF EXISTS seed_from_course_defs;
DROP TEMPORARY TABLE IF EXISTS seed_to_course_defs;
DROP TEMPORARY TABLE IF EXISTS seed_knowledge_point_defs;
DROP TEMPORARY TABLE IF EXISTS seed_chapter_defs;
DROP TEMPORARY TABLE IF EXISTS seed_course_defs;
