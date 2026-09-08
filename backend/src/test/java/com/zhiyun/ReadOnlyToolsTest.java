package com.zhiyun;

import com.zhiyun.auth.AuthService;
import com.zhiyun.billing.BillingService;
import com.zhiyun.common.ApiException;
import com.zhiyun.cs.CsOrderQuery;
import com.zhiyun.cs.ReadOnlyTools;
import com.zhiyun.domain.AppOrder;
import com.zhiyun.domain.AppUser;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.Plan;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.OrderRepo;
import com.zhiyun.repo.PlanRepo;
import com.zhiyun.repo.ProjectRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReadOnlyToolsTest {
    @Autowired
    ReadOnlyTools tools;
    @Autowired
    AuthService authService;
    @Autowired
    UserRepo userRepo;
    @Autowired
    ProjectRepo projectRepo;
    @Autowired
    ManuscriptRepo manuscriptRepo;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    PlanRepo planRepo;
    @Autowired
    BillingService billingService;
    @Autowired
    OrderRepo orderRepo;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void ownerCanReadOwnOrderAndTaskOtherTenant404() {
        seedPlan();
        AuthUser alice = login("tools-alice-" + System.nanoTime() + "@zhiyun.dev");
        ReviewTask task = seedTask(alice, "Alice paper");
        TenantContext.set(alice);
        Map<String, Object> order = billingService.createOrder(null, 10);
        TenantContext.clear();
        String orderNo = String.valueOf(order.get("id"));

        assertThat(task.publicId()).startsWith("ZYT");
        assertThat(task.publicId()).doesNotMatch("^\\d+$");
        Map<String, Object> ownTask = tools.taskStatus(alice, task.publicId());
        assertThat(ownTask.get("taskId")).isEqualTo(task.publicId());
        assertThat(ownTask.get("status")).isEqualTo(Codes.DONE);
        assertThat(ownTask.get("manuscriptTitle")).isEqualTo("Alice paper");

        Map<String, Object> byMs = tools.getManuscript(alice, task.getManuscriptId());
        assertThat(byMs.get("id")).isEqualTo(task.getManuscriptId());
        Map<String, Object> looked = tools.lookupPaper(alice, task.publicId());
        assertThat(looked.get("matched")).isEqualTo("taskId");

        Object orders = tools.orderQuery(alice, CsOrderQuery.none());
        assertThat(orders).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> listed = (Map<String, Object>) orders;
        assertThat(listed.get("totalPaidYuan")).isNotNull();
        assertThat(listed.get("quotaBalance")).isNotNull();
        assertThat(listed.get("quotaConsumed")).isNotNull();
        assertThat(listed.get("orders")).isInstanceOf(List.class);
        assertThat((List<?>) listed.get("orders")).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> one = (Map<String, Object>) tools.orderQuery(alice, orderNo);
        assertThat(one.get("orderNo")).isEqualTo(orderNo);
        assertThat(((Number) one.get("amountCents")).intValue()).isEqualTo(1000);
        assertThat(String.valueOf(one.get("createdAt"))).doesNotContain("T");
        assertThat(String.valueOf(one.get("createdAt"))).doesNotContain("Z");

        Map<String, Object> usage = tools.usage(alice);
        assertThat(usage.get("balance")).isNotNull();
        assertThat(usage.get("quotaBalance")).isEqualTo(usage.get("balance"));
        assertThat(usage.get("quotaConsumed")).isNotNull();
        assertThat(usage.get("ledger")).isInstanceOf(List.class);
        TenantContext.set(alice);
        billingService.mockPay(orderNo);
        TenantContext.clear();
        List<?> ledger = (List<?>) tools.usage(alice).get("ledger");
        assertThat(ledger).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> ledgerRow = (Map<String, Object>) ledger.get(0);
        assertThat(String.valueOf(ledgerRow.get("id"))).startsWith("ZYL");
        assertThat(String.valueOf(ledgerRow.get("id"))).doesNotMatch("^\\d+$");

        AuthUser bob = login("tools-bob-" + System.nanoTime() + "@zhiyun.dev");
        assertThatThrownBy(() -> tools.taskStatus(bob, task.publicId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("本账户没有该 id");
        assertThatThrownBy(() -> tools.lookupPaper(bob, task.getManuscriptId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本账户没有该 id");
        assertThatThrownBy(() -> tools.orderQuery(bob, orderNo))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本账户没有该 id");
        @SuppressWarnings("unchecked")
        Map<String, Object> bobOrders = (Map<String, Object>) tools.orderQuery(bob, CsOrderQuery.none());
        assertThat(((Number) bobOrders.get("totalOrders")).intValue()).isZero();
    }

    @Test
    void orderQueryDateFilterUsesRangeAndIsolatesTenant() {
        seedPlan();
        AuthUser alice = login("tools-range-" + System.nanoTime() + "@zhiyun.dev");
        TenantContext.set(alice);
        String oldPaid = String.valueOf(billingService.createOrder(null, 10).get("id"));
        billingService.mockPay(oldPaid);
        String yesterdayPending = String.valueOf(billingService.createOrder(null, 10).get("id"));
        TenantContext.clear();

        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(shanghai);
        LocalDate yesterday = today.minusDays(1);
        LocalDate oldDay = today.minusDays(5);
        backdate(oldPaid, oldDay.atTime(10, 0).atZone(shanghai).toInstant());
        backdate(yesterdayPending, yesterday.atTime(11, 1).atZone(shanghai).toInstant());

        Map<String, Object> ranged = tools.orderQuery(alice, new CsOrderQuery(
                null, yesterday.toString(), today.toString(), null, null));
        assertThat(ranged.get("range")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) ranged.get("range");
        assertThat(range.get("from")).isEqualTo(yesterday.toString());
        assertThat(range.get("to")).isEqualTo(today.toString());
        assertThat(((Number) ranged.get("matchedCount")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) ranged.get("orders");
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("orderNo")).isEqualTo(yesterdayPending);
        assertThat(orders.get(0).get("status")).isEqualTo(Codes.ORDER_PENDING);
        assertThat(orders.stream().map(o -> String.valueOf(o.get("orderNo"))).toList()).doesNotContain(oldPaid);

        AuthUser bob = login("tools-range-bob-" + System.nanoTime() + "@zhiyun.dev");
        Map<String, Object> bobRanged = tools.orderQuery(bob, new CsOrderQuery(
                null, yesterday.toString(), today.toString(), null, null));
        assertThat(((Number) bobRanged.get("matchedCount")).intValue()).isZero();
        assertThat((List<?>) bobRanged.get("orders")).isEmpty();
    }

    private void backdate(String orderNo, java.time.Instant when) {
        AppOrder row = orderRepo.findByOrderNo(orderNo).orElseThrow();
        row.setCreatedAt(when);
        orderRepo.saveAndFlush(row);
    }

    private ReviewTask seedTask(AuthUser user, String title) {
        ResearchProject project = new ResearchProject();
        project.setTenantId(user.tenantId());
        project.setName("课题乙");
        project = projectRepo.save(project);
        Manuscript ms = new Manuscript();
        ms.setTenantId(user.tenantId());
        ms.setProjectId(project.getId());
        ms.setTitle(title);
        ms.setCurrentVersion(1);
        ms = manuscriptRepo.save(ms);
        ReviewTask task = new ReviewTask();
        task.setTenantId(user.tenantId());
        task.setUserId(user.userId());
        task.setManuscriptId(ms.getId());
        task.setWorkflow(Codes.QUICK_REVIEW);
        task.setStatus(Codes.DONE);
        task.setTargetVenue("NeurIPS");
        return reviewTaskRepo.save(task);
    }

    private AuthUser login(String email) {
        authService.register(email, "demo123456", "Tools", null);
        AppUser row = userRepo.findByEmail(email).orElseThrow();
        return new AuthUser(row.getId(), row.getTenantId(), row.getEmail(), row.getDisplayName());
    }

    private void seedPlan() {
        if (planRepo.count() > 0) {
            return;
        }
        Plan plan = new Plan();
        plan.setCode("starter");
        plan.setName("Starter");
        plan.setQuotaAmount(10);
        plan.setPriceCents(0);
        plan.setDescription("test");
        planRepo.save(plan);
    }
}
