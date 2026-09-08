package com.zhiyun.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelFanoutTest {
    @Test
    void looksUpMultipleDoisOnWorkerThreads() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            ParallelFanout fanout = new ParallelFanout(pool, pool);
            Set<String> threads = ConcurrentHashMap.newKeySet();
            List<String> dois = List.of("10.1/a", "10.1/b", "10.1/c", "10.1/d");
            List<String> out = fanout.mapCitation(dois, doi -> {
                threads.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return doi.toUpperCase();
            });
            assertThat(out).containsExactly("10.1/A", "10.1/B", "10.1/C", "10.1/D");
            assertThat(threads.size()).isGreaterThan(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mapsFiguresInOrderWithoutEmptyFutures() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ParallelFanout fanout = new ParallelFanout(pool, pool);
            List<Integer> pages = List.of(1, 2, 3);
            List<String> out = fanout.mapFigure(pages, page -> "p" + page);
            assertThat(out).containsExactly("p1", "p2", "p3");
            assertThat(fanout.mapFigure(List.of(), page -> "x")).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
