package com.zhiyun.billing;

import com.zhiyun.config.ZhiyunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 演示渠道：受理后异步回调到账。delay-ms=0 时同线程结算，方便单测。
 */
@Component
public class MockPaymentChannel implements PaymentChannel {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentChannel.class);

    private final ZhiyunProperties properties;
    private final Executor payExecutor;

    public MockPaymentChannel(ZhiyunProperties properties, @Qualifier("payExecutor") Executor payExecutor) {
        this.properties = properties;
        this.payExecutor = payExecutor;
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public void charge(PaymentIntent intent, PaidCallback callback) {
        String txn = "mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int delay = properties.getPay().getMockDelayMs();
        if (delay <= 0) {
            callback.onPaid(intent.orderNo(), txn);
            return;
        }
        payExecutor.execute(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(delay);
                callback.onPaid(intent.orderNo(), txn);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("mock pay interrupted for {}", intent.orderNo());
            } catch (Exception e) {
                log.warn("mock pay callback failed for {}: {}", intent.orderNo(), e.getMessage());
            }
        });
    }
}
