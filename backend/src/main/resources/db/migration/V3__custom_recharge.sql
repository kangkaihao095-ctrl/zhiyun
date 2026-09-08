ALTER TABLE app_order
    MODIFY COLUMN plan_id BIGINT NULL,
    ADD COLUMN quota_amount INT NOT NULL DEFAULT 0 AFTER plan_id;

UPDATE app_order o
    INNER JOIN plan p ON o.plan_id = p.id
    SET o.quota_amount = p.quota_amount
    WHERE o.quota_amount = 0;

UPDATE plan SET
    name = '轻量套餐',
    quota_amount = 36,
    price_cents = 2900,
    description = '¥29 到账 36 点，比按元充值多 7 点。偶尔核验引用够用。'
WHERE code = 'starter';

UPDATE plan SET
    name = '常用套餐',
    quota_amount = 130,
    price_cents = 9900,
    description = '¥99 到账 130 点，比按元充值多 31 点。投稿前反复完整审校更划算。'
WHERE code = 'pro';

UPDATE plan SET
    name = '课题组套餐',
    quota_amount = 420,
    price_cents = 29900,
    description = '¥299 到账 420 点，比按元充值多 121 点。多篇论文并行审校。'
WHERE code = 'lab';

UPDATE plan SET
    name = '实验室套餐',
    quota_amount = 1500,
    price_cents = 99900,
    description = '¥999 到账 1500 点，比按元充值多 501 点。课程班或实验室批量审校。'
WHERE code = 'team';
