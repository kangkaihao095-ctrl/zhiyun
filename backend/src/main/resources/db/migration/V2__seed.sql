INSERT INTO tenant (id, name) VALUES (1, 'Demo Lab');

INSERT INTO app_user (id, tenant_id, email, password_hash, display_name)
VALUES (1, 1, 'demo@zhiyun.dev', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5G5p5b5n5nYw0vOq2n2mYk1yG6vOa2', 'Demo User');
-- password is placeholder; application seed runner overwrites with BCrypt of demo123456 on first boot if needed

INSERT INTO research_project (id, tenant_id, name) VALUES (1, 1, 'Sample NLP Paper');

INSERT INTO plan (code, name, quota_amount, price_cents, description) VALUES
('starter', 'Starter', 10, 0, '10 review credits. Free trial for Citation-only and Quick Review. One credit per review task.'),
('pro', 'Pro', 50, 9900, '50 credits at ¥99. Personal FULL_REVIEW loops before submission.'),
('lab', 'Lab', 200, 29900, '200 credits at ¥299. Shared tenant for a research group.'),
('team', 'Team', 1000, 99900, '1000 credits at ¥999. Course or lab-scale batch reviews.');

INSERT INTO quota_account (tenant_id, user_id, balance, version) VALUES (1, 1, 10, 0);
