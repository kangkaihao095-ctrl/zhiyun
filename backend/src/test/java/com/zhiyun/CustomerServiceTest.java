package com.zhiyun;

import com.zhiyun.auth.AuthService;
import com.zhiyun.billing.BillingService;
import com.zhiyun.cs.CustomerService;
import com.zhiyun.cs.CsIntent;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CustomerServiceTest {
    @Autowired
    CustomerService customerService;
    @Autowired
    AuthService authService;
    @Autowired
    UserRepo userRepo;
    @Autowired
    PlanRepo planRepo;
    @Autowired
    ProjectRepo projectRepo;
    @Autowired
    ManuscriptRepo manuscriptRepo;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    BillingService billingService;
    @Autowired
    OrderRepo orderRepo;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void personalQuotaIsAccountBalanceNotStarterPack() {
        seedPlans();
        AuthUser user = login("cs-balance@zhiyun.dev");
        String answer = customerService.reply(user, "我的额度还剩多少？", List.of());
        assertThat(answer).contains("3");
        assertThat(answer).contains("账户余额");
        assertThat(answer).contains("dry-run");
        assertThat(answer).doesNotContain("¥99");
        assertThat(answer).doesNotContain("usage_query=");
    }

    @Test
    void starterPriceComesFromCatalog() {
        seedPlans();
        AuthUser user = login("cs-plan@zhiyun.dev");
        Plan sample = planRepo.findAll().get(0);
        String answer = customerService.reply(user, sample.getName() + " 多少钱", List.of());
        assertThat(answer).contains("¥" + (sample.getPriceCents() / 100));
        assertThat(answer).contains(sample.getQuotaAmount() + " 额度");
        assertThat(answer).contains("不是你的当前余额");
        assertThat(answer).doesNotContain("你当前剩余 10 额度");
    }

    @Test
    void neuripsRejectsWordAndAclPrefersA4() {
        AuthUser user = login("cs-venue@zhiyun.dev");
        String neurips = customerService.reply(user, "NeurIPS 能不能交 Word", List.of());
        assertThat(neurips.contains("停用") || neurips.contains("LaTeX")).isTrue();
        String acl = customerService.reply(user, "ACL 用 A4 还是 Letter", List.of());
        assertThat(acl).contains("A4");
    }

    @Test
    void rechargeHistoryUsesOrderQueryAndIsItemized() {
        AuthUser user = login("cs-recharge-" + System.nanoTime() + "@zhiyun.dev");
        String answer = customerService.reply(user, "查一下我的历史充值记录", List.of());
        assertThat(answer).doesNotContain("没有订单查询工具");
        assertThat(answer).containsAnyOf("没有充值订单", "订单号");
        assertThat(answer).contains("\n");
        assertThat(answer).contains("-");
    }

    @Test
    void paperIdLooksUpOwnTaskAndRejectsUnknown() {
        AuthUser user = login("cs-paper-" + System.nanoTime() + "@zhiyun.dev");
        ReviewTask task = seedTask(user, "云笺查稿");
        String found = customerService.reply(user, "查一下论文 " + task.getId(), List.of());
        assertThat(found).contains("任务 ID");
        assertThat(found).contains(String.valueOf(task.getId()));
        assertThat(found).contains(Codes.DONE);
        assertThat(found).contains("\n-");

        String missing = customerService.reply(user, "查一下 999999001", List.of());
        assertThat(missing).contains("本账户没有该 ID");
        assertThat(missing).contains("任务 ID");
        assertThat(missing).contains("稿件 ID");
    }

    @Test
    void spendAndOrderHistoryAreDifferentTemplates() {
        seedPlans();
        AuthUser user = login("cs-spend-" + System.nanoTime() + "@zhiyun.dev");
        TenantContext.set(user);
        Map<String, Object> paid = billingService.createOrder(null, 10);
        billingService.mockPay(String.valueOf(paid.get("id")));
        billingService.createOrder(null, 10);
        billingService.consumeOneCredit(user.tenantId(), user.userId(), "task-eval");

        String spent = customerService.reply(user, "看下我过去花了多少钱了", List.of());
        String history = customerService.reply(user, "帮我查下我的历史订单消费情况", List.of());
        assertThat(CsIntent.classify("看下我过去花了多少钱了")).contains(CsIntent.SPEND);
        assertThat(CsIntent.classify("帮我查下我的历史订单消费情况")).contains(CsIntent.ORDERS);
        assertThat(spent).contains("¥10");
        assertThat(spent).contains("额度消耗");
        assertThat(spent).doesNotContain("订单号");
        assertThat(history).contains("¥10");
        assertThat(history).contains("充值");
        assertThat(history).isNotEqualTo(spent);
        assertThat(countNeedle(history, "订单号")).isLessThanOrEqualTo(5);
        assertThat(spent).contains("\n-");
        assertThat(history).contains("\n-");
    }

    @Test
    void followUpOrderQuestionUsesPriorTurn() {
        AuthUser user = login("cs-follow-" + System.nanoTime() + "@zhiyun.dev");
        List<CustomerService.ChatTurn> prior = List.of(
                new CustomerService.ChatTurn("user", "我的额度还剩多少？"),
                new CustomerService.ChatTurn("assistant", "还剩 3 额度")
        );
        assertThat(CsIntent.classify("那昨天的订单", prior)).contains(CsIntent.ORDERS);
        String answer = customerService.reply(user, "那昨天的订单", prior);
        assertThat(answer).containsAnyOf("订单", "充值");
        assertThat(answer).doesNotContain("还剩 3 额度");
    }

    @Test
    void modelConfigHowToPointsToSettingsNotSalesApi() {
        AuthUser user = login("cs-models-" + System.nanoTime() + "@zhiyun.dev");
        assertThat(CsIntent.classify("模型配置怎么操作")).contains(CsIntent.MODELS);
        String answer = customerService.reply(user, "模型配置怎么操作", List.of());
        assertThat(answer).contains("设置");
        assertThat(answer).contains("模型配置");
        assertThat(answer).contains("/account?panel=models");
        assertThat(answer).doesNotContain("联系商务");
        assertThat(answer).doesNotContain("通用 API Key 未上线");
        assertThat(answer).doesNotContain("企业 API");
    }

    @Test
    void todayAndYesterdayOrdersUseRangeNotRecentDump() {
        seedPlans();
        AuthUser user = login("cs-range-" + System.nanoTime() + "@zhiyun.dev");
        TenantContext.set(user);
        var oldPaid = billingService.createOrder(null, 10);
        billingService.mockPay(String.valueOf(oldPaid.get("id")));
        var yesterdayPending = billingService.createOrder(null, 10);
        TenantContext.clear();

        java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate today = java.time.LocalDate.now(shanghai);
        java.time.LocalDate yesterday = today.minusDays(1);
        AppOrder old = orderRepo.findByOrderNo(String.valueOf(oldPaid.get("id"))).orElseThrow();
        old.setCreatedAt(today.minusDays(5).atTime(10, 0).atZone(shanghai).toInstant());
        orderRepo.saveAndFlush(old);
        AppOrder pending = orderRepo.findByOrderNo(String.valueOf(yesterdayPending.get("id"))).orElseThrow();
        pending.setCreatedAt(yesterday.atTime(11, 1).atZone(shanghai).toInstant());
        orderRepo.saveAndFlush(pending);

        String answer = customerService.reply(user, "查一下今天和昨天的订单", List.of());
        assertThat(answer).contains(yesterday.toString());
        assertThat(answer).contains(today.toString());
        assertThat(answer).contains(pending.publicId());
        assertThat(answer).contains("PENDING");
        assertThat(answer).doesNotContain("最近");
        assertThat(answer).doesNotContain(old.publicId());
        assertThat(answer).contains("\n-");
    }

    private static int countNeedle(String text, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    private ReviewTask seedTask(AuthUser user, String title) {
        ResearchProject project = new ResearchProject();
        project.setTenantId(user.tenantId());
        project.setName("课题甲");
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
        task.setWorkflow(Codes.FULL_REVIEW);
        task.setStatus(Codes.DONE);
        task.setTargetVenue("ACL");
        return reviewTaskRepo.save(task);
    }

    private AuthUser login(String email) {
        authService.register(email, "demo123456", "Cs", null);
        AppUser row = userRepo.findByEmail(email).orElseThrow();
        AuthUser user = new AuthUser(row.getId(), row.getTenantId(), row.getEmail(), row.getDisplayName());
        TenantContext.set(user);
        return user;
    }

    private void seedPlans() {
        if (planRepo.count() > 0) {
            return;
        }
        savePlan("starter", "Starter", 10, 0);
        savePlan("pro", "Pro", 50, 9900);
    }

    private void savePlan(String code, String name, int quota, int cents) {
        Plan plan = new Plan();
        plan.setCode(code);
        plan.setName(name);
        plan.setQuotaAmount(quota);
        plan.setPriceCents(cents);
        plan.setDescription(name);
        planRepo.save(plan);
    }
}
