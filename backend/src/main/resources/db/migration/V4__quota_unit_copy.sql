UPDATE plan SET description = '¥29 到账 36 额度，比按元充值多 7 额度。偶尔核验引用够用。'
WHERE code = 'starter';

UPDATE plan SET description = '¥99 到账 130 额度，比按元充值多 31 额度。投稿前反复完整审校更划算。'
WHERE code = 'pro';

UPDATE plan SET description = '¥299 到账 420 额度，比按元充值多 121 额度。多篇论文并行审校。'
WHERE code = 'lab';

UPDATE plan SET description = '¥999 到账 1500 额度，比按元充值多 501 额度。课程班或实验室批量审校。'
WHERE code = 'team';
