package com.zhiyun.billing;

import com.zhiyun.config.ZhiyunProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentChannelTest {
    @Test
    void delayZeroSettlesOnTheSameThread() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getPay().setMockDelayMs(0);
        MockPaymentChannel channel = new MockPaymentChannel(properties, Runnable::run);
        AtomicReference<String> txn = new AtomicReference<>();
        channel.charge(new PaymentChannel.PaymentIntent("ZYTEST", 1, 1, 1000, 10),
                (orderNo, txnId) -> {
                    assertThat(orderNo).isEqualTo("ZYTEST");
                    txn.set(txnId);
                });
        assertThat(channel.id()).isEqualTo("mock");
        assertThat(txn.get()).startsWith("mock-");
    }
}
