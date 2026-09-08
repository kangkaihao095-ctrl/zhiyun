package com.zhiyun.billing;

import com.zhiyun.domain.AppOrder;
import com.zhiyun.domain.Codes;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PaymentService {
    private final BillingService billingService;
    private final PaymentChannel paymentChannel;

    public PaymentService(BillingService billingService, PaymentChannel paymentChannel) {
        this.billingService = billingService;
        this.paymentChannel = paymentChannel;
    }

    /** 演示入口：走可替换渠道，不直连微信/支付宝。 */
    public Map<String, Object> chargeMock(String orderKey) {
        AppOrder order = billingService.requireOwned(orderKey);
        if (Codes.ORDER_PAID.equals(order.getStatus())) {
            return billingService.myOrder(orderKey);
        }
        billingService.attachChannel(order, paymentChannel.id());
        PaymentChannel.PaymentIntent intent = new PaymentChannel.PaymentIntent(
                order.publicId(),
                order.getTenantId(),
                order.getUserId(),
                order.getAmountCents() == null ? 0 : order.getAmountCents(),
                order.getQuotaAmount() == null ? 0 : order.getQuotaAmount()
        );
        paymentChannel.charge(intent, billingService::settlePaid);
        return billingService.myOrder(orderKey);
    }
}
