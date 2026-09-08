package com.zhiyun.billing;

/**
 * 可替换支付渠道。订单与额度仍以 {@link BillingService} 为准；渠道只负责受理并回调到账。
 * 演示使用 mock，不要接微信/支付宝进件。
 */
public interface PaymentChannel {
    String id();

    void charge(PaymentIntent intent, PaidCallback callback);

    record PaymentIntent(String orderNo, long tenantId, long userId, int amountCents, int quotaAmount) {
    }

    @FunctionalInterface
    interface PaidCallback {
        void onPaid(String orderNo, String txnId);
    }
}
