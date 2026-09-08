-- 当前sql用来测试索引的效果

-- 生成测试表
USE learning_agent;

DROP TABLE IF EXISTS documents_test;

CREATE TABLE documents_test (
                                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                kb_id BIGINT UNSIGNED NOT NULL COMMENT '知识库ID',
                                filename VARCHAR(255) NOT NULL,
                                status ENUM('UPLOADED','PARSING','READY','FAILED') NOT NULL DEFAULT 'UPLOADED',
                                upload_time DATETIME NOT NULL,
                                file_size INT UNSIGNED NOT NULL,
                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 生成10万条测试数据
DELIMITER //

CREATE PROCEDURE generate_test_data()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE random_kb BIGINT;
    DECLARE random_status VARCHAR(20);
    DECLARE random_time DATETIME;

    -- 关闭自动提交，批量插入更快
    SET autocommit = 0;

    WHILE i <= 100000 DO
            -- 随机生成 kb_id (1~100)
            SET random_kb = FLOOR(1 + RAND() * 100);

            -- 随机生成 status
            SET random_status = ELT(FLOOR(1 + RAND() * 4), 'UPLOADED', 'PARSING', 'READY', 'FAILED');

            -- 随机生成 upload_time（过去一年内）
            SET random_time = DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 365) DAY);

            INSERT INTO documents_test (kb_id, filename, status, upload_time, file_size)
            VALUES (
                       random_kb,
                       CONCAT('file_', i, '.pdf'),
                       random_status,
                       random_time,
                       FLOOR(1000 + RAND() * 100000)
                   );

            -- 每1000条提交一次
            IF i % 1000 = 0 THEN
                COMMIT;
            END IF;

            SET i = i + 1;
        END WHILE;

    COMMIT;
    SET autocommit = 1;
END //

DELIMITER ;

-- 执行（需要约10~30秒，看机器性能）
CALL generate_test_data();

-- 验证数据量
SELECT COUNT(*) FROM documents_test;

-- 无索引
-- 查询：找 kb_id=50 的所有文档，按上传时间倒序，取前10条
EXPLAIN
SELECT id, filename, upload_time
FROM documents_test
WHERE kb_id = 50
ORDER BY upload_time DESC
LIMIT 10;
-- 实际执行（看真实耗时）
EXPLAIN ANALYZE
SELECT id, filename, upload_time
FROM documents_test
WHERE kb_id = 50
ORDER BY upload_time DESC
LIMIT 10;
-- 无索引 type：ALL。rows：99767。Extra：Using where; Using filesort。actual time：19.8毫秒 结论：；全表扫描耗时较长


-- 加单列索引
CREATE INDEX idx_kb ON documents_test(kb_id);

-- 同样的查询，再 EXPLAIN 一次
EXPLAIN
SELECT id, filename, upload_time
FROM documents_test
WHERE kb_id = 50
ORDER BY upload_time DESC
LIMIT 10;
-- 加单列索引 type：ref。rows：966。Extra：Using filesort。actual time：2.05毫秒 快了8倍 单列索引减少了rows数量，加快了查询速度相较于无索引快了8倍

-- 加联合索引
DROP INDEX idx_kb ON documents_test;
CREATE INDEX idx_kb_time ON documents_test(kb_id, upload_time);

-- 同样的查询
EXPLAIN
SELECT id, filename, upload_time
FROM documents_test
WHERE kb_id = 50
ORDER BY upload_time DESC
LIMIT 10;
-- 加联合索引 type：const。rows：966。Extra：Backward index scan。actual time：0.35毫秒 快了56倍 结论：type变成const，无需额外排序。

-- 覆盖索引和回表
-- 查询A：SELECT *（需要回表）
EXPLAIN FORMAT=TRADITIONAL
-- 看耗时用ANALYZE
SELECT * FROM documents_test WHERE kb_id = 50 LIMIT 10;
-- 实际耗时0.319毫秒 没有Using index

-- 查询B：只查索引列（覆盖索引，不回表）
EXPLAIN FORMAT=TRADITIONAL
-- 看耗时用ANALYZE
SELECT id, kb_id, upload_time FROM documents_test WHERE kb_id = 50 LIMIT 10;
-- 实际耗时0.04毫秒 有Using index  耗时差10倍
-- 结论：回表会增加io次数，减慢查询速度。


-- 函数导致索引失效
-- 索引存在：idx_kb_time(kb_id, upload_time)

-- 查询A：对列用函数（失效）
EXPLAIN
SELECT id, filename
FROM documents_test
WHERE  YEAR(upload_time) = 2026;

-- 查询B：改写成范围（有效）
EXPLAIN
SELECT id, filename
FROM documents_test
WHERE id=50 AND upload_time >= '2026-01-01' AND upload_time < '2027-01-01';
--  查询A的type是：ALL，key是null。查询B的type是ALL，key是null。查询A是全表扫描
-- 结论：函数导致索引失效，会退化成全表扫描

-- 跳过中间列
-- 建一个三列联合索引
CREATE INDEX idx_kb_status_time ON documents_test(kb_id, status, upload_time);

-- 查询：跳过中间列 status
EXPLAIN
SELECT id, filename
FROM documents_test
WHERE kb_id = 50 AND upload_time > '2026-01-01';
-- 不跳过
EXPLAIN
SELECT id, filename
FROM documents_test
WHERE kb_id = 50 AND status = 'READY' AND upload_time > '2026-01-01';
-- 跳过：key：没有使用idx_kb_status_time。Extra有ICP标志。rows大概扫描了646行。
-- 不跳过：key：idx_kb_status_time。Extra：有ICP标志。rows大概扫描了166行。不跳过的情况下rows更少
-- 结论：跳过中间列会增加rows的条数，联合索引失效。