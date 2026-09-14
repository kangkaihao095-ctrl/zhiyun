package com.zhiyun.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.common.ApiException;
import com.zhiyun.common.Pages;
import com.zhiyun.common.SearchLabels;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.AppOrder;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.Plan;
import com.zhiyun.domain.QuotaAccount;
import com.zhiyun.domain.QuotaLedger;
import com.zhiyun.repo.OrderRepo;
import com.zhiyun.repo.PlanRepo;
import com.zhiyun.repo.QuotaAccountRepo;
import com.zhiyun.repo.QuotaLedgerRepo;
import com.zhiyun.security.TenantContext;
import com.zhiyun.workflow.WorkflowCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    public static final String PLANS_CACHE_KEY = "zhiyun:plans";
    private static final Duration PLANS_TTL = Duration.ofMinutes(10);

    private final PlanRepo planRepo;
    private final OrderRepo orderRepo;
    private final QuotaAccountRepo quotaAccountRepo;
    private final QuotaLedgerRepo quotaLedgerRepo;
    private final StringRedisTemplate redis;
    private final ZhiyunProperties properties;
    private final WorkflowCatalog workflowCatalog;
    private final ObjectMapper objectMapper;

    public BillingService(PlanRepo planRepo, OrderRepo orderRepo, QuotaAccountRepo quotaAccountRepo,
                          QuotaLedgerRepo quotaLedgerRepo, ObjectProvider<StringRedisTemplate> redis,
                          ZhiyunProperties properties, WorkflowCatalog workflowCatalog, ObjectMapper objectMapper) {
        this.planRepo = planRepo;
        this.orderRepo = orderRepo;
        this.quotaAccountRepo = quotaAccountRepo;
        this.quotaLedgerRepo = quotaLedgerRepo;
        this.redis = redis.getIfAvailable();
        this.properties = properties;
        this.workflowCatalog = workflowCatalog;
        this.objectMapper = objectMapper;
    }

    public List<Plan> plans() {
        List<Plan> cached = readPlanCache();
        if (cached != null) {
            return cached;
        }
        List<Plan> plans = planRepo.findAll();
        writePlanCache(plans);
        return plans;
    }

    private List<Plan> readPlanCache() {
        if (redis == null) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(PLANS_CACHE_KEY);
            if (json == null || json.isBlank() || "1".equals(json)) {
                return null;
            }
            List<Plan> plans = objectMapper.readValue(json, new TypeReference<List<Plan>>() {
            });
            return plans == null || plans.isEmpty() ? null : plans;
        } catch (Exception e) {
            log.debug("plans cache read skipped: {}", e.getMessage());
            return null;
        }
    }

    private void writePlanCache(List<Plan> plans) {
        if (redis == null || plans == null) {
            return;
        }
        try {
            redis.opsForValue().set(PLANS_CACHE_KEY, objectMapper.writeValueAsString(plans), PLANS_TTL);
        } catch (Exception e) {
            log.debug("plans cache write skipped: {}", e.getMessage());
        }
    }

    public static final int MIN_RECHARGE_YUAN = 10;

    @Transactional
    public Map<String, Object> createOrder(Long planId, Integer amountYuan) {
        if (planId != null) {
            Plan plan = planRepo.findById(planId).orElseThrow(() -> ApiException.notFound("套餐不存在"));
            return toOrderMap(persistOrder(plan.getId(), plan.getPriceCents(), plan.getQuotaAmount()), planIndex());
        }
        if (amountYuan == null) {
            throw ApiException.bad("请选择套餐，或输入充值金额");
        }
        if (amountYuan < MIN_RECHARGE_YUAN) {
            throw ApiException.bad("最低充值 " + MIN_RECHARGE_YUAN + " 元");
        }
        if (amountYuan > 100_000) {
            throw ApiException.bad("单笔充值过大");
        }
        return toOrderMap(persistOrder(null, amountYuan * 100, amountYuan), planIndex());
    }

    private AppOrder persistOrder(Long planId, int amountCents, int quotaAmount) {
        AppOrder order = new AppOrder();
        order.setTenantId(TenantContext.tenantId());
        order.setUserId(TenantContext.userId());
        order.setPlanId(planId);
        order.setStatus(Codes.ORDER_PENDING);
        order.setAmountCents(amountCents);
        order.setQuotaAmount(quotaAmount);
        order.setOrderNo(OrderNos.next());
        return orderRepo.save(order);
    }

    @Transactional
    public void attachChannel(AppOrder order, String channel) {
        order.setPayChannel(channel);
        orderRepo.save(order);
    }

    /** 渠道回调入账。异步线程没有 JWT，按业务单号查找，不信请求体 tenantId。 */
    @Transactional
    public void settlePaid(String orderNo, String txnId) {
        AppOrder order = orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
        if (Codes.ORDER_PAID.equals(order.getStatus())) {
            return;
        }
        int points = order.getQuotaAmount() == null ? 0 : order.getQuotaAmount();
        if (points <= 0 && order.getPlanId() != null) {
            Plan plan = planRepo.findById(order.getPlanId()).orElseThrow(() -> ApiException.notFound("套餐不存在"));
            points = plan.getQuotaAmount();
        }
        if (points <= 0) {
            throw ApiException.bad("订单额度无效");
        }
        credit(order.getTenantId(), order.getUserId(), points, "PURCHASE", order.ledgerRef());
        order.setStatus(Codes.ORDER_PAID);
        order.setPayTxnId(txnId);
        if (order.getPayChannel() == null || order.getPayChannel().isBlank()) {
            order.setPayChannel("mock");
        }
        if (order.getQuotaAmount() == null || order.getQuotaAmount() <= 0) {
            order.setQuotaAmount(points);
        }
        orderRepo.save(order);
    }

    @Transactional
    public Map<String, Object> mockPay(String orderKey) {
        AppOrder order = requireOwned(orderKey);
        if (Codes.ORDER_PAID.equals(order.getStatus())) {
            return toOrderMap(order, planIndex());
        }
        settlePaid(order.publicId(), "legacy-sync");
        return toOrderMap(requireOwned(orderKey), planIndex());
    }

    public Map<String, Object> myOrders(String q, Integer page, Integer size) {
        int p = Pages.page(page);
        int s = Pages.size(size);
        boolean blank = Pages.blank(q);
        List<String> statuses = SearchLabels.orderStatuses(q);
        var result = orderRepo.search(
                TenantContext.tenantId(), TenantContext.userId(),
                Pages.flag(blank), Pages.needle(q), Pages.flag(!statuses.isEmpty()),
                Pages.orDummy(statuses), Pages.of(p, s));
        Map<Long, Plan> plans = planIndex();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AppOrder order : result.getContent()) {
            items.add(toOrderMap(order, plans));
        }
        return Pages.wrap(items, result.getTotalElements(), p, s);
    }

    public Map<String, Object> myOrder(String orderKey) {
        AppOrder order = requireOwned(orderKey);
        Map<String, Object> row = toOrderMap(order, planIndex());
        List<QuotaLedger> ledgers = loadOrderLedgers(order);
        List<Map<String, Object>> ledgerOut = new ArrayList<>();
        Instant paidAt = null;
        for (QuotaLedger ledger : ledgers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ledger.publicId());
            item.put("delta", ledger.getDelta());
            item.put("reason", ledger.getReason());
            item.put("refId", ledger.getRefId());
            item.put("createdAt", ledger.getCreatedAt());
            ledgerOut.add(item);
            if (paidAt == null) {
                paidAt = ledger.getCreatedAt();
            }
        }
        if (Codes.ORDER_PAID.equals(order.getStatus()) && paidAt == null) {
            paidAt = order.getCreatedAt();
        }
        row.put("paidAt", Codes.ORDER_PAID.equals(order.getStatus()) ? paidAt : null);
        row.put("ledger", ledgerOut);
        return row;
    }

    public AppOrder requireOwned(String orderKey) {
        return findOwned(TenantContext.tenantId(), TenantContext.userId(), orderKey)
                .orElseThrow(() -> ApiException.notFound("订单不存在"));
    }

    public Optional<AppOrder> findOwned(long tenantId, long userId, String orderKey) {
        if (orderKey == null || orderKey.isBlank()) {
            return Optional.empty();
        }
        String key = orderKey.trim();
        var byNo = orderRepo.findByOrderNoAndTenantIdAndUserId(key, tenantId, userId);
        if (byNo.isPresent()) {
            return byNo;
        }
        if (OrderNos.looksNumericPk(key)) {
            try {
                long pk = Long.parseLong(key);
                return orderRepo.findByIdAndTenantIdAndUserId(pk, tenantId, userId);
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Map<Long, Plan> planIndex() {
        Map<Long, Plan> plans = new LinkedHashMap<>();
        for (Plan plan : planRepo.findAll()) {
            plans.put(plan.getId(), plan);
        }
        return plans;
    }

    private Map<String, Object> toOrderMap(AppOrder order, Map<Long, Plan> plans) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", order.publicId());
        row.put("planId", order.getPlanId());
        row.put("kind", order.getPlanId() == null ? "CUSTOM" : "PACKAGE");
        Plan plan = order.getPlanId() == null ? null : plans.get(order.getPlanId());
        row.put("planName", plan == null ? "灵活充值" : plan.getName());
        row.put("quotaAmount", order.getQuotaAmount());
        row.put("amountCents", order.getAmountCents());
        row.put("status", order.getStatus());
        row.put("payChannel", order.getPayChannel() == null ? "" : order.getPayChannel());
        row.put("payTxnId", order.getPayTxnId() == null ? "" : order.getPayTxnId());
        row.put("createdAt", order.getCreatedAt());
        return row;
    }

    private List<QuotaLedger> loadOrderLedgers(AppOrder order) {
        Set<Long> seen = new LinkedHashSet<>();
        List<QuotaLedger> out = new ArrayList<>();
        for (String ref : List.of(order.ledgerRef(), order.legacyLedgerRef())) {
            for (QuotaLedger ledger : quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                    order.getTenantId(), order.getUserId(), ref)) {
                if (seen.add(ledger.getId())) {
                    out.add(ledger);
                }
            }
        }
        return out;
    }

    public Map<String, Object> myLedger(String q, Integer page, Integer size) {
        int p = Pages.page(page);
        int s = Pages.size(size);
        boolean blank = Pages.blank(q);
        List<String> reasons = SearchLabels.ledgerReasons(q);
        var result = quotaLedgerRepo.search(
                TenantContext.tenantId(), TenantContext.userId(),
                Pages.flag(blank), Pages.needle(q), Pages.flag(!reasons.isEmpty()),
                Pages.orDummy(reasons), Pages.of(p, s));
        List<Map<String, Object>> items = result.getContent().stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row.publicId());
                    m.put("delta", row.getDelta());
                    m.put("reason", row.getReason());
                    m.put("refId", row.getRefId());
                    m.put("createdAt", row.getCreatedAt());
                    return m;
                })
                .toList();
        return Pages.wrap(items, result.getTotalElements(), p, s);
    }

    @Transactional
    public void requireMinBalance(long tenantId, long userId, int min) {
        QuotaAccount account = quotaAccountRepo.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> ApiException.conflict("quota account missing"));
        if (account.getBalance() < min) {
            throw ApiException.conflict("额度不够了，请先去充值");
        }
    }

    @Transactional
    public int settleUsage(long tenantId, long userId, String workflow, int tokens, String refId) {
        return settleUsage(tenantId, userId, workflow, tokens, refId, 0);
    }

    /**
     * 与 {@link #settleUsage} 同一套口径：自备模型收技能费，否则 ceil(tokens/每点) 再封顶。
     * 用户页预计额度必须走这里，不要在前端另算。
     */
    public int quotePoints(String workflow, int tokens, int byokAgents) {
        if (byokAgents > 0) {
            return skillFee(workflow);
        }
        int per = Math.max(1, properties.getQuota().getTokensPerPoint());
        int cap = workflowCatalog.cap(workflow);
        int points = Math.max(1, (int) Math.ceil(Math.max(0, tokens) / (double) per));
        return Math.min(points, cap);
    }

    /** 已按 task-ZYT… 入账则返回已扣点数；没有 REVIEW_USAGE / SKILL_FEE 流水则 empty。 */
    public Optional<Integer> settledPoints(long tenantId, long userId, String refId) {
        if (refId == null || refId.isBlank()) {
            return Optional.empty();
        }
        boolean hit = false;
        int points = 0;
        for (QuotaLedger row : quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(tenantId, userId, refId)) {
            String reason = row.getReason();
            if (!"REVIEW_USAGE".equals(reason) && !"SKILL_FEE".equals(reason)) {
                continue;
            }
            hit = true;
            int delta = row.getDelta() == null ? 0 : row.getDelta();
            if (delta < 0) {
                points += -delta;
            }
        }
        return hit ? Optional.of(points) : Optional.empty();
    }

    @Transactional
    public int settleUsage(long tenantId, long userId, String workflow, int tokens, String refId, int byokAgents) {
        if (quotaLedgerRepo.existsByReasonAndRefId("REVIEW_USAGE", refId)
                || quotaLedgerRepo.existsByReasonAndRefId("SKILL_FEE", refId)) {
            return 0;
        }
        int points = quotePoints(workflow, tokens, byokAgents);
        String reason = byokAgents > 0 ? "SKILL_FEE" : "REVIEW_USAGE";
        QuotaAccount account = quotaAccountRepo.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> ApiException.conflict("quota account missing"));
        points = Math.min(points, Math.max(0, account.getBalance()));
        if (points <= 0) {
            return 0;
        }
        account.setBalance(account.getBalance() - points);
        quotaAccountRepo.save(account);
        ledger(tenantId, userId, -points, reason, refId);
        return points;
    }

    public int skillFee(String workflow) {
        if (Codes.FULL_REVIEW.equals(workflow)) {
            return 2;
        }
        return 1;
    }

    @Transactional
    public void consumeCredits(long tenantId, long userId, int amount, String reason, String refId) {
        if (amount <= 0) {
            return;
        }
        QuotaAccount account = quotaAccountRepo.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> ApiException.conflict("quota account missing"));
        if (account.getBalance() < amount) {
            throw ApiException.conflict("额度不够了，请先去充值");
        }
        account.setBalance(account.getBalance() - amount);
        quotaAccountRepo.save(account);
        ledger(tenantId, userId, -amount, reason, refId);
    }

    @Transactional
    public void consumeOneCredit(long tenantId, long userId, String refId) {
        consumeCredits(tenantId, userId, 1, "REVIEW_CONSUME", refId);
    }

    @Transactional
    public void credit(long tenantId, long userId, int amount, String reason, String refId) {
        QuotaAccount account = quotaAccountRepo.findByTenantIdAndUserId(tenantId, userId)
                .orElseGet(() -> {
                    QuotaAccount created = new QuotaAccount();
                    created.setTenantId(tenantId);
                    created.setUserId(userId);
                    created.setBalance(0);
                    return created;
                });
        account.setBalance(account.getBalance() + amount);
        quotaAccountRepo.save(account);
        ledger(tenantId, userId, amount, reason, refId);
    }

    public Map<String, Object> usage(long tenantId, long userId) {
        int balance = quotaAccountRepo.findByTenantIdAndUserId(tenantId, userId)
                .map(QuotaAccount::getBalance).orElse(0);
        return Map.of("tenantId", tenantId, "userId", userId, "balance", balance);
    }

    private void ledger(long tenantId, long userId, int delta, String reason, String refId) {
        QuotaLedger row = new QuotaLedger();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setDelta(delta);
        row.setReason(reason);
        row.setRefId(refId);
        quotaLedgerRepo.save(row);
    }
}
