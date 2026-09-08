package com.zhiyun.agent;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 短任务 JVM 内并行：多 DOI 引用核验、多图检查。长审校仍走 RabbitMQ，不是第二套编排。
 */
@Component
public class ParallelFanout {
    private final Executor citationExecutor;
    private final Executor figureExecutor;

    public ParallelFanout(@Qualifier("citationExecutor") Executor citationExecutor,
                          @Qualifier("figureExecutor") Executor figureExecutor) {
        this.citationExecutor = citationExecutor;
        this.figureExecutor = figureExecutor;
    }

    public <T, R> List<R> mapCitation(List<T> items, Function<T, R> fn) {
        return map(items, fn, citationExecutor);
    }

    public <T, R> List<R> mapFigure(List<T> items, Function<T, R> fn) {
        return map(items, fn, figureExecutor);
    }

    static <T, R> List<R> map(List<T> items, Function<T, R> fn, Executor executor) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
        for (T item : items) {
            T captured = item;
            futures.add(CompletableFuture.supplyAsync(() -> fn.apply(captured), executor));
        }
        List<R> out = new ArrayList<>(futures.size());
        for (CompletableFuture<R> future : futures) {
            out.add(future.join());
        }
        return out;
    }
}
