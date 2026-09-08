package com.zhiyun.cs;

import com.zhiyun.billing.BillingService;
import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.AppOrder;
import com.zhiyun.domain.AppUser;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.InboxMessage;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.Plan;
import com.zhiyun.domain.QuotaLedger;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.UserAgentModel;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.llm.UserModelService;
import com.zhiyun.rag.RagService;
import com.zhiyun.repo.InboxMessageRepo;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.OrderRepo;
import com.zhiyun.repo.ProjectRepo;
import com.zhiyun.repo.QuotaLedgerRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.UserAgentModelRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.common.BusinessNos;
import com.zhiyun.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云笺只读 Tool。身份只信 JWT 解析出的 AuthUser，请求体不能覆盖租户。
 */
@Service
public class ReadOnlyTools {
    static final String MISSING_ID = "本账户没有该 id";
    static final String ID_HINT = "可提供任务 ID、稿件 ID 或订单号";
    static final int RECENT_ORDERS = 5;
    static final int RECENT_LEDGER = 8;

    private final ReviewTaskRepo reviewTaskRepo;
    private final ManuscriptRepo manuscriptRepo;
    private final ProjectRepo projectRepo;
    private final OrderRepo orderRepo;
    private final BillingService billingService;
    private final ArtifactStore artifactStore;
    private final RagService ragService;
    private final QuotaLedgerRepo quotaLedgerRepo;
    private final InboxMessageRepo inboxMessageRepo;
    private final UserRepo userRepo;
    private final UserAgentModelRepo userAgentModelRepo;
    private final ZhiyunProperties properties;
    private final Clock clock;

    public ReadOnlyTools(ReviewTaskRepo reviewTaskRepo, OrderRepo orderRepo, BillingService billingService,
                         ArtifactStore artifactStore, RagService ragService, ManuscriptRepo manuscriptRepo,
                         ProjectRepo projectRepo, QuotaLedgerRepo quotaLedgerRepo, InboxMessageRepo inboxMessageRepo,
                         UserRepo userRepo, UserAgentModelRepo userAgentModelRepo, ZhiyunProperties properties) {
        this.reviewTaskRepo = reviewTaskRepo;
        this.orderRepo = orderRepo;
        this.billingService = billingService;
        this.artifactStore = artifactStore;
        this.ragService = ragService;
        this.manuscriptRepo = manuscriptRepo;
        this.projectRepo = projectRepo;
        this.quotaLedgerRepo = quotaLedgerRepo;
        this.inboxMessageRepo = inboxMessageRepo;
        this.userRepo = userRepo;
        this.userAgentModelRepo = userAgentModelRepo;
        this.properties = properties;
        this.clock = Clock.system(CsLocalTimes.SHANGHAI);
    }

    public Map<String, Object> taskStatus(AuthUser user, Long taskId) {
        return taskStatus(user, taskId == null ? null : Long.toString(taskId));
    }

    public Map<String, Object> taskStatus(AuthUser user, String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", false);
            out.put("message", "请提供任务 ID 或稿件 ID");
            out.put("hint", ID_HINT);
            out.put("tasks", listMyTasks(user));
            return out;
        }
        ReviewTask task = findOwnedTask(user, taskKey)
                .orElseThrow(() -> ApiException.notFound(MISSING_ID));
        return toTaskMap(task, manuscriptOf(user, task.getManuscriptId()));
    }

    public List<Map<String, Object>> listMyTasks(AuthUser user) {
        List<ReviewTask> rows = reviewTaskRepo.findByTenantIdAndUserIdOrderByIdDesc(user.tenantId(), user.userId());
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (ReviewTask task : rows) {
            if (n++ >= 20) {
                break;
            }
            out.add(toTaskMap(task, manuscriptOf(user, task.getManuscriptId())));
        }
        return out;
    }

    public Map<String, Object> lookupPaper(AuthUser user, Long id) {
        return lookupPaper(user, id == null ? null : Long.toString(id));
    }

    public Map<String, Object> lookupPaper(AuthUser user, String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (key == null || key.isBlank()) {
            out.put("found", false);
            out.put("message", "请提供任务 ID 或稿件 ID");
            out.put("hint", ID_HINT);
            out.put("tasks", listMyTasks(user));
            out.put("manuscripts", listMyManuscripts(user));
            return out;
        }
        var taskHit = findOwnedTask(user, key);
        if (taskHit.isPresent()) {
            ReviewTask task = taskHit.get();
            out.put("found", true);
            out.put("matched", "taskId");
            out.put("task", toTaskMap(task, manuscriptOf(user, task.getManuscriptId())));
            return out;
        }
        if (BusinessNos.looksNumericPk(key)) {
            long id = Long.parseLong(key.trim());
            var msHit = manuscriptRepo.findByIdAndTenantId(id, user.tenantId());
            if (msHit.isPresent()) {
                Manuscript ms = msHit.get();
                out.put("found", true);
                out.put("matched", "manuscriptId");
                out.put("manuscript", toManuscriptMap(user, ms));
                out.put("tasks", tasksOfManuscript(user, ms.getId()));
                return out;
            }
        }
        throw ApiException.notFound(MISSING_ID);
    }

    public Map<String, Object> getManuscript(AuthUser user, Long manuscriptId) {
        if (manuscriptId == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", false);
            out.put("message", "请提供稿件 ID");
            out.put("hint", ID_HINT);
            out.put("manuscripts", listMyManuscripts(user));
            return out;
        }
        Manuscript ms = manuscriptRepo.findByIdAndTenantId(manuscriptId, user.tenantId())
                .orElseThrow(() -> ApiException.notFound(MISSING_ID));
        Map<String, Object> out = new LinkedHashMap<>(toManuscriptMap(user, ms));
        out.put("tasks", tasksOfManuscript(user, ms.getId()));
        return out;
    }

    public List<Map<String, Object>> listMyManuscripts(AuthUser user) {
        List<Manuscript> rows = manuscriptRepo.findByTenantIdOrderByIdDesc(user.tenantId());
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Manuscript ms : rows) {
            if (n++ >= 20) {
                break;
            }
            out.add(toManuscriptMap(user, ms));
        }
        return out;
    }

    public Map<String, Object> usage(AuthUser user) {
        Map<String, Object> usage = accountSnapshot(user);
        usage.put("ledger", recentLedger(user));
        return usage;
    }

    public List<Map<String, Object>> recentLedger(AuthUser user) {
        return ledgerQuery(user, CsOrderQuery.none());
    }

    public List<Map<String, Object>> ledgerQuery(AuthUser user, CsOrderQuery query) {
        CsOrderQuery q = query == null ? CsOrderQuery.none() : query;
        CsDateRange range = q.range(clock);
        int limit = q.resolvedLimit(range != null);
        List<QuotaLedger> rows = quotaLedgerRepo.findAll(ledgerSpec(user, range), Sort.by(Sort.Direction.DESC, "id"));
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (QuotaLedger row : rows) {
            if (n++ >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.publicId());
            item.put("delta", row.getDelta());
            item.put("reason", row.getReason());
            item.put("refId", row.getRefId() == null ? "" : row.getRefId());
            item.put("createdAt", CsLocalTimes.format(row.getCreatedAt()));
            out.add(item);
        }
        return out;
    }

    public List<Plan> plans() {
        return billingService.plans();
    }

    public List<Map<String, Object>> listMyOrders(AuthUser user) {
        return listMyOrders(user, 20);
    }

    public List<Map<String, Object>> listMyOrders(AuthUser user, int limit) {
        List<AppOrder> rows = orderRepo.findByTenantIdAndUserIdOrderByIdDesc(user.tenantId(), user.userId());
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        int cap = Math.max(1, limit);
        for (AppOrder order : rows) {
            if (n++ >= cap) {
                break;
            }
            out.add(toOrderMap(order));
        }
        return out;
    }

    public Map<String, Object> orderQuery(AuthUser user, String orderId) {
        return orderQuery(user, CsOrderQuery.byOrderId(orderId));
    }

    public Map<String, Object> orderQuery(AuthUser user, CsOrderQuery query) {
        CsOrderQuery q = query == null ? CsOrderQuery.none() : query;
        if (q.orderId() != null && !q.orderId().isBlank()) {
            AppOrder order = billingService.findOwned(user.tenantId(), user.userId(), q.orderId())
                    .orElseThrow(() -> ApiException.notFound(MISSING_ID));
            return toOrderMap(order);
        }
        if (q.hasFilter(clock)) {
            return filteredOrders(user, q);
        }
        return accountSnapshot(user);
    }

    public Map<String, Object> accountSnapshot(AuthUser user) {
        List<AppOrder> rows = orderRepo.findByTenantIdAndUserIdOrderByIdDesc(user.tenantId(), user.userId());
        int totalPaidCents = 0;
        int totalPendingCents = 0;
        int rechargeCount = 0;
        for (AppOrder order : rows) {
            int cents = order.getAmountCents() == null ? 0 : order.getAmountCents();
            String status = order.getStatus() == null ? "" : order.getStatus();
            boolean demo = cents <= 0;
            if (Codes.ORDER_PAID.equals(status) && !demo) {
                totalPaidCents += cents;
                rechargeCount++;
            } else if (Codes.ORDER_PENDING.equals(status)) {
                totalPendingCents += cents;
            }
        }
        Map<String, Object> usage = billingService.usage(user.tenantId(), user.userId());
        int balance = usage.get("balance") instanceof Number n ? n.intValue() : 0;
        Integer consumedRaw = quotaLedgerRepo.sumConsumed(user.tenantId(), user.userId());
        int consumed = consumedRaw == null ? 0 : consumedRaw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPaidYuan", totalPaidCents / 100);
        out.put("totalPendingYuan", totalPendingCents / 100);
        out.put("rechargeCount", rechargeCount);
        out.put("quotaBalance", balance);
        out.put("balance", balance);
        out.put("quotaConsumed", consumed);
        out.put("recentLimit", RECENT_ORDERS);
        out.put("totalOrders", rows.size());
        out.put("truncated", rows.size() > RECENT_ORDERS);
        out.put("orders", listMyOrders(user, RECENT_ORDERS));
        return out;
    }

    public Map<String, Object> unreadInbox(AuthUser user) {
        List<InboxMessage> rows = inboxMessageRepo.findByTenantIdAndUserIdAndReadAtIsNull(
                user.tenantId(), user.userId());
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 0;
        for (InboxMessage row : rows) {
            if (n++ >= 10) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("kind", row.getKind());
            item.put("title", row.getTitle());
            item.put("refId", row.getRefId() == null ? "" : row.getRefId());
            item.put("createdAt", CsLocalTimes.format(row.getCreatedAt()));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unreadCount", rows.size());
        out.put("items", items);
        return out;
    }

    public Map<String, Object> accountProfile(AuthUser user) {
        AppUser row = userRepo.findByIdAndTenantId(user.userId(), user.tenantId()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", row != null && row.getDisplayName() != null ? row.getDisplayName() : user.displayName());
        out.put("email", row != null && row.getEmail() != null ? row.getEmail() : user.email());
        out.put("userId", user.userId());
        return out;
    }

    public Map<String, Object> modelConfig(AuthUser user) {
        var llm = properties.getLlm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("csModel", llm.getCsModel());
        out.put("csLocked", true);
        out.put("paperModel", llm.getPaperModel());
        out.put("visionModel", llm.getVisionModel());
        List<Map<String, Object>> agents = new ArrayList<>();
        List<UserAgentModel> rows = userAgentModelRepo.findByTenantIdAndUserId(user.tenantId(), user.userId());
        Map<String, UserAgentModel> byAgent = new LinkedHashMap<>();
        for (UserAgentModel row : rows) {
            byAgent.put(row.getAgentId(), row);
        }
        for (String id : UserModelService.PAPER_AGENTS) {
            UserAgentModel row = byAgent.get(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            boolean byok = row != null && Boolean.TRUE.equals(row.getEnabled());
            item.put("mode", byok ? "BYOK" : "PLATFORM");
            item.put("modelId", byok ? row.getModelId() : llm.getPaperModel());
            item.put("apiKeyMasked", byok && row.getApiKeySuffix() != null ? "sk-****" + row.getApiKeySuffix() : "");
            agents.add(item);
        }
        out.put("agents", agents);
        return out;
    }

    public Map<String, Object> citationResult(AuthUser user, Long taskId) {
        return citationResult(user, taskId == null ? null : Long.toString(taskId));
    }

    public Map<String, Object> citationResult(AuthUser user, String taskKey) {
        ReviewTask task = findOwnedTask(user, taskKey)
                .orElseThrow(() -> ApiException.notFound(MISSING_ID));
        var evidence = artifactStore.body(task.getId(), AgentIds.CITATION, "Evidence");
        var issues = artifactStore.body(task.getId(), AgentIds.CITATION, "ReviewIssue");
        return Map.of("taskId", task.publicId(), "evidence", evidence.toString(), "issues", issues.toString());
    }

    public List<String> knowledge(String query) {
        return ragService.retrievePublic(query).stream().map(RagService.Retrieved::content).toList();
    }

    private List<Map<String, Object>> tasksOfManuscript(AuthUser user, Long manuscriptId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewTask task : reviewTaskRepo.findByManuscriptIdAndTenantIdOrderByIdDesc(manuscriptId, user.tenantId())) {
            if (task.getUserId() != null && task.getUserId() != user.userId()) {
                continue;
            }
            out.add(toTaskMap(task, manuscriptOf(user, manuscriptId)));
        }
        return out;
    }

    private Manuscript manuscriptOf(AuthUser user, Long manuscriptId) {
        if (manuscriptId == null) {
            return null;
        }
        return manuscriptRepo.findByIdAndTenantId(manuscriptId, user.tenantId()).orElse(null);
    }

    private Map<String, Object> toTaskMap(ReviewTask task, Manuscript ms) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.publicId());
        row.put("status", task.getStatus() == null ? "" : task.getStatus());
        row.put("workflow", task.getWorkflow() == null ? "" : task.getWorkflow());
        row.put("targetVenue", task.getTargetVenue() == null ? "" : task.getTargetVenue());
        row.put("checkpoint", task.getCheckpointAgent() == null ? "" : task.getCheckpointAgent());
        row.put("manuscriptId", task.getManuscriptId());
        row.put("manuscriptTitle", ms == null || ms.getTitle() == null ? "" : ms.getTitle());
        row.put("createdAt", CsLocalTimes.format(task.getCreatedAt()));
        row.put("updatedAt", CsLocalTimes.format(task.getUpdatedAt()));
        return row;
    }

    private Map<String, Object> toManuscriptMap(AuthUser user, Manuscript ms) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ms.getId());
        row.put("title", ms.getTitle() == null ? "" : ms.getTitle());
        row.put("currentVersion", ms.getCurrentVersion() == null ? 1 : ms.getCurrentVersion());
        row.put("projectId", ms.getProjectId());
        String projectName = "";
        if (ms.getProjectId() != null) {
            projectName = projectRepo.findByIdAndTenantId(ms.getProjectId(), user.tenantId())
                    .map(ResearchProject::getName)
                    .orElse("");
        }
        row.put("projectName", projectName);
        return row;
    }

    private Map<String, Object> filteredOrders(AuthUser user, CsOrderQuery query) {
        CsDateRange range = query.range(clock);
        String status = query.whitelistStatus();
        int limit = query.resolvedLimit(range != null);
        List<AppOrder> matched = orderRepo.findAll(orderSpec(user, range, status), Sort.by(Sort.Direction.DESC, "id"));
        int totalPaidCents = 0;
        int totalPendingCents = 0;
        int rechargeCount = 0;
        for (AppOrder order : matched) {
            int cents = order.getAmountCents() == null ? 0 : order.getAmountCents();
            String st = order.getStatus() == null ? "" : order.getStatus();
            boolean demo = cents <= 0;
            if (Codes.ORDER_PAID.equals(st) && !demo) {
                totalPaidCents += cents;
                rechargeCount++;
            } else if (Codes.ORDER_PENDING.equals(st)) {
                totalPendingCents += cents;
            }
        }
        Map<String, Object> usage = billingService.usage(user.tenantId(), user.userId());
        int balance = usage.get("balance") instanceof Number n ? n.intValue() : 0;
        Integer consumedRaw = quotaLedgerRepo.sumConsumed(user.tenantId(), user.userId());
        int consumed = consumedRaw == null ? 0 : consumedRaw;
        List<Map<String, Object>> orders = new ArrayList<>();
        int n = 0;
        for (AppOrder order : matched) {
            if (n++ >= limit) {
                break;
            }
            orders.add(toOrderMap(order));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPaidYuan", totalPaidCents / 100);
        out.put("totalPendingYuan", totalPendingCents / 100);
        out.put("rechargeCount", rechargeCount);
        out.put("quotaBalance", balance);
        out.put("balance", balance);
        out.put("quotaConsumed", consumed);
        out.put("recentLimit", limit);
        out.put("matchedCount", matched.size());
        out.put("totalOrders", matched.size());
        out.put("truncated", matched.size() > limit);
        if (range != null) {
            out.put("range", range.toMap());
        }
        if (status != null) {
            out.put("status", status);
        }
        out.put("orders", orders);
        return out;
    }

    private Specification<AppOrder> orderSpec(AuthUser user, CsDateRange range, String status) {
        return (root, cq, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.equal(root.get("tenantId"), user.tenantId()));
            parts.add(cb.equal(root.get("userId"), user.userId()));
            if (range != null) {
                Instant from = range.fromInclusive();
                Instant to = range.toExclusive();
                parts.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
                parts.add(cb.lessThan(root.get("createdAt"), to));
            }
            if (status != null) {
                parts.add(cb.equal(root.get("status"), status));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
    }

    private Specification<QuotaLedger> ledgerSpec(AuthUser user, CsDateRange range) {
        return (root, cq, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.equal(root.get("tenantId"), user.tenantId()));
            parts.add(cb.equal(root.get("userId"), user.userId()));
            if (range != null) {
                parts.add(cb.greaterThanOrEqualTo(root.get("createdAt"), range.fromInclusive()));
                parts.add(cb.lessThan(root.get("createdAt"), range.toExclusive()));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
    }

    private java.util.Optional<ReviewTask> findOwnedTask(AuthUser user, String key) {
        if (key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        String s = key.trim();
        var byNo = reviewTaskRepo.findByTaskNoAndTenantId(s, user.tenantId());
        if (byNo.isPresent()) {
            ReviewTask task = byNo.get();
            if (task.getUserId() != null && task.getUserId() != user.userId()) {
                return java.util.Optional.empty();
            }
            return byNo;
        }
        if (BusinessNos.looksNumericPk(s)) {
            var byPk = reviewTaskRepo.findByIdAndTenantId(Long.parseLong(s), user.tenantId());
            if (byPk.isPresent()) {
                ReviewTask task = byPk.get();
                if (task.getUserId() != null && task.getUserId() != user.userId()) {
                    return java.util.Optional.empty();
                }
            }
            return byPk;
        }
        return java.util.Optional.empty();
    }

    private static Map<String, Object> toOrderMap(AppOrder order) {
        Map<String, Object> row = new LinkedHashMap<>();
        int cents = order.getAmountCents() == null ? 0 : order.getAmountCents();
        row.put("orderNo", order.publicId());
        row.put("amountCents", cents);
        row.put("amountYuan", cents / 100);
        row.put("quotaAmount", order.getQuotaAmount() == null ? 0 : order.getQuotaAmount());
        row.put("status", order.getStatus() == null ? "" : order.getStatus());
        row.put("payChannel", order.getPayChannel() == null ? "" : order.getPayChannel());
        row.put("createdAt", CsLocalTimes.format(order.getCreatedAt()));
        row.put("kind", order.getPlanId() == null ? "CUSTOM" : "PACKAGE");
        row.put("demo", cents <= 0);
        return row;
    }
}
