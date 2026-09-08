package com.zhiyun.workflow;

import com.rabbitmq.client.Channel;
import com.zhiyun.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 审校任务消费者。不用 {@code @ConditionalOnBean(RabbitTemplate)}：用户 {@code @Component}
 * 会在 Rabbit 自动配置之前求值，条件永远为 false，监听器不会注册，任务会一直 PENDING。
 * 测试 profile 排除 Rabbit 自动配置，因此本 Bean 在 test 下不加载。
 *
 * <p>手动 ACK，与 {@code LeaseService} fencing / checkpoint / 一次结算对齐：
 * <ul>
 *   <li>成功：{@code execute} 正常返回（含业务 FAILED，走 /retry）→ ACK。</li>
 *   <li>execute 抛错：NACK 且 requeue。lease 过期后其他 worker 用新 fencing token 从 checkpoint 续跑。</li>
 *   <li>ACK 前进程崩溃：消息未确认被重投；lease TTL 后 acquire；artifact 唯一键跳过已完成节点；
 *       {@code settleUsage} 按 {@code task-{id}} 只扣一次。</li>
 *   <li>无 taskId 的毒消息：NACK 且不重入队，避免死循环。</li>
 * </ul>
 * 不要在 checkpoint 后提前 ACK：未跑完的任务仍靠 lease 过期 + 重投，而不是半段确认。
 */
@Component
@Profile("!test")
public class ReviewListener {
    private static final Logger log = LoggerFactory.getLogger(ReviewListener.class);

    private final ReviewOrchestrator orchestrator;

    public ReviewListener(ReviewOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
        log.info("ReviewListener registered; queue={}", RabbitConfig.REVIEW_QUEUE);
    }

    @RabbitListener(queues = RabbitConfig.REVIEW_QUEUE, ackMode = "MANUAL")
    public void onMessage(Map<String, Object> message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Object rawId = message == null ? null : message.get("taskId");
        if (!(rawId instanceof Number)) {
            log.error("invalid review message, drop: {}", message);
            nack(channel, deliveryTag, false);
            return;
        }
        long taskId = ((Number) rawId).longValue();
        log.info("Review task {} picked up from queue", taskId);
        try {
            orchestrator.execute(taskId);
            ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("review {} listener failed; nack requeue (lease/fencing will fence stale worker): {}",
                    taskId, e.getMessage());
            nack(channel, deliveryTag, true);
        }
    }

    private static void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("basicAck failed tag={}: {}; unacked redelivery + fencing covers this", deliveryTag, e.getMessage());
        }
    }

    private static void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.warn("basicNack failed tag={}: {}", deliveryTag, e.getMessage());
        }
    }
}
