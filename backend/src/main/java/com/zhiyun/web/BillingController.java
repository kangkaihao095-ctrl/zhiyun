package com.zhiyun.web;

import com.zhiyun.billing.BillingService;
import com.zhiyun.billing.PaymentService;
import com.zhiyun.domain.Plan;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BillingController {
    private final BillingService billingService;
    private final PaymentService paymentService;

    public BillingController(BillingService billingService, PaymentService paymentService) {
        this.billingService = billingService;
        this.paymentService = paymentService;
    }

    public record CreateOrderReq(Long planId, Integer amountYuan) {
    }

    @GetMapping("/plans")
    public List<Plan> plans() {
        return billingService.plans();
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody CreateOrderReq req) {
        return billingService.createOrder(req.planId(), req.amountYuan());
    }

    @PostMapping("/orders/{id}/mock-pay")
    public Map<String, Object> mockPay(@PathVariable String id) {
        return paymentService.chargeMock(id);
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return billingService.myOrders(q, page, size);
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> order(@PathVariable String id) {
        return billingService.myOrder(id);
    }

    @GetMapping("/ledger")
    public Map<String, Object> ledger(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return billingService.myLedger(q, page, size);
    }
}
