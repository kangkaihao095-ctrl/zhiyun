-- Agent span 补工具调用 JSON，供 /ops 观测台按 Tool / Agent 聚合。不是 SLA。
-- 若种子脚本已加过列则跳过。

SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_span' AND COLUMN_NAME = 'tool_calls'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE agent_span ADD COLUMN tool_name VARCHAR(64) NULL AFTER error_code, ADD COLUMN tool_calls JSON NULL AFTER tool_name',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
