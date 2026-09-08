ALTER TABLE review_task
    ADD COLUMN task_no VARCHAR(32) NULL AFTER id;

UPDATE review_task
SET task_no = CONCAT('ZYT', LPAD(id, 16, '0'))
WHERE task_no IS NULL OR task_no = '';

ALTER TABLE review_task
    MODIFY COLUMN task_no VARCHAR(32) NOT NULL;

ALTER TABLE review_task
    ADD UNIQUE KEY uk_task_no (task_no);

ALTER TABLE quota_ledger
    ADD COLUMN ledger_no VARCHAR(32) NULL AFTER id;

UPDATE quota_ledger
SET ledger_no = CONCAT('ZYL', LPAD(id, 16, '0'))
WHERE ledger_no IS NULL OR ledger_no = '';

ALTER TABLE quota_ledger
    MODIFY COLUMN ledger_no VARCHAR(32) NOT NULL;

ALTER TABLE quota_ledger
    ADD UNIQUE KEY uk_ledger_no (ledger_no);
