UPDATE app_user
SET display_name = '一只用户'
WHERE email = 'demo@zhiyun.dev';

UPDATE tenant
SET name = '个人实验室'
WHERE id = 1 AND name = 'Demo Lab';

UPDATE research_project
SET name = '我的论文'
WHERE tenant_id = 1 AND name = 'Sample NLP Paper';
