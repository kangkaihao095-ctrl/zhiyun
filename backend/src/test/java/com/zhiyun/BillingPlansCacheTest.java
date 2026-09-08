package com.zhiyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.billing.BillingService;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.Plan;
import com.zhiyun.repo.OrderRepo;
import com.zhiyun.repo.PlanRepo;
import com.zhiyun.repo.QuotaAccountRepo;
import com.zhiyun.repo.QuotaLedgerRepo;
import com.zhiyun.workflow.WorkflowCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingPlansCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void cachesPlanListJsonWithTtlAndReadsItBack() throws Exception {
        PlanRepo planRepo = mock(PlanRepo.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ObjectProvider<StringRedisTemplate> provider = providerOf(stubRedis(ops));

        Plan plan = new Plan();
        plan.setId(3L);
        plan.setCode("starter");
        plan.setName("轻量套餐");
        plan.setQuotaAmount(32);
        plan.setPriceCents(2900);
        plan.setDescription("test");
        when(planRepo.findAll()).thenReturn(List.of(plan));
        when(ops.get(BillingService.PLANS_CACHE_KEY)).thenReturn(null);

        ObjectMapper mapper = new ObjectMapper();
        ZhiyunProperties properties = new ZhiyunProperties();
        BillingService service = new BillingService(
                planRepo, mock(OrderRepo.class), mock(QuotaAccountRepo.class), mock(QuotaLedgerRepo.class),
                provider, properties, new WorkflowCatalog(properties), mapper);

        List<Plan> first = service.plans();
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getCode()).isEqualTo("starter");
        verify(ops).set(eq(BillingService.PLANS_CACHE_KEY), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("starter") && json.contains("轻量套餐") && !json.equals("1")), eq(Duration.ofMinutes(10)));

        String cached = mapper.writeValueAsString(first);
        when(ops.get(BillingService.PLANS_CACHE_KEY)).thenReturn(cached);
        List<Plan> second = service.plans();
        assertThat(second.get(0).getName()).isEqualTo("轻量套餐");
        assertThat(second.get(0).getQuotaAmount()).isEqualTo(32);
        verify(planRepo, times(1)).findAll();
        verify(ops, times(2)).get(BillingService.PLANS_CACHE_KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresPlaceholderCacheValueAndReloadsMysql() {
        PlanRepo planRepo = mock(PlanRepo.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ObjectProvider<StringRedisTemplate> provider = providerOf(stubRedis(ops));
        when(ops.get(BillingService.PLANS_CACHE_KEY)).thenReturn("1");
        Plan plan = new Plan();
        plan.setCode("lab");
        when(planRepo.findAll()).thenReturn(List.of(plan));

        ZhiyunProperties properties = new ZhiyunProperties();
        BillingService service = new BillingService(
                planRepo, mock(OrderRepo.class), mock(QuotaAccountRepo.class), mock(QuotaLedgerRepo.class),
                provider, properties, new WorkflowCatalog(properties), new ObjectMapper());
        assertThat(service.plans().get(0).getCode()).isEqualTo("lab");
        verify(planRepo).findAll();
        verify(ops).set(eq(BillingService.PLANS_CACHE_KEY), any(), eq(Duration.ofMinutes(10)));
    }

    /** Java 26 下 Mockito inline 无法 mock StringRedisTemplate；只覆盖 opsForValue。 */
    private static StringRedisTemplate stubRedis(ValueOperations<String, String> ops) {
        return new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };
    }

    /** ObjectProvider 继承 JDK Iterable，Java 26 下 Byte Buddy 无法 instrument。 */
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate redis) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() throws BeansException {
                return redis;
            }

            @Override
            public StringRedisTemplate getObject(Object... args) throws BeansException {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfAvailable() throws BeansException {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfUnique() throws BeansException {
                return redis;
            }
        };
    }
}
