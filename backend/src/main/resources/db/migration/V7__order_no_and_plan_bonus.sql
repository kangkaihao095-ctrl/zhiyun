ALTER TABLE app_order
    ADD COLUMN order_no VARCHAR(32) NULL AFTER id;

UPDATE app_order
SET order_no = CONCAT('ZY', LPAD(id, 16, '0'))
WHERE order_no IS NULL OR order_no = '';

ALTER TABLE app_order
    MODIFY COLUMN order_no VARCHAR(32) NOT NULL;

ALTER TABLE app_order
    ADD UNIQUE KEY uk_order_no (order_no);

-- 套餐额外额度降到约 10%（相对 1 元 = 1 额度的灵活充值）。已支付订单不重算。
UPDATE plan SET
    quota_amount = 32,
    description = '¥29 到账 32 额度，比按元充值多 3 额度。偶尔核验引用够用。'
WHERE code = 'starter';

UPDATE plan SET
    quota_amount = 110,
    description = '¥99 到账 110 额度，比按元充值多 11 额度。投稿前反复完整审校更划算。'
WHERE code = 'pro';

UPDATE plan SET
    quota_amount = 329,
    description = '¥299 到账 329 额度，比按元充值多 30 额度。多篇论文并行审校。'
WHERE code = 'lab';

UPDATE plan SET
    quota_amount = 1099,
    description = '¥999 到账 1099 额度，比按元充值多 100 额度。课程班或实验室批量审校。'
WHERE code = 'team';
