package com.zhiyun.billing;

import com.zhiyun.common.BusinessNos;

/** 对外业务单号：ZY + 时间 + 随机，全局唯一，不是数据库自增。 */
public final class OrderNos {
    private OrderNos() {
    }

    public static String next() {
        return BusinessNos.nextOrder();
    }

    public static boolean looksNumericPk(String raw) {
        return BusinessNos.looksNumericPk(raw);
    }
}
