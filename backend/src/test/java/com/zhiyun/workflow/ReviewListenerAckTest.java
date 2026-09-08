package com.zhiyun.workflow;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReviewListenerAckTest {
    @Test
    void acksAfterExecuteReturnsIncludingBusinessFailure() throws Exception {
        ReviewOrchestrator orchestrator = mock(ReviewOrchestrator.class);
        Channel channel = mock(Channel.class);
        ReviewListener listener = new ReviewListener(orchestrator);

        listener.onMessage(Map.of("taskId", 7L), channel, 42L);

        verify(orchestrator).execute(7L);
        verify(channel).basicAck(42L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void nacksAndRequeuesWhenExecuteThrowsSoLeaseCanExpire() throws Exception {
        ReviewOrchestrator orchestrator = mock(ReviewOrchestrator.class);
        doThrow(new RuntimeException("task leased by another worker")).when(orchestrator).execute(8L);
        Channel channel = mock(Channel.class);
        ReviewListener listener = new ReviewListener(orchestrator);

        listener.onMessage(Map.of("taskId", 8), channel, 9L);

        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void dropsPoisonMessageWithoutRequeue() throws Exception {
        ReviewOrchestrator orchestrator = mock(ReviewOrchestrator.class);
        Channel channel = mock(Channel.class);
        ReviewListener listener = new ReviewListener(orchestrator);

        listener.onMessage(Map.of("oops", "no-id"), channel, 3L);

        verify(orchestrator, never()).execute(anyLong());
        verify(channel).basicNack(3L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
