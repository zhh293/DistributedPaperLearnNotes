package com.zhh.handsome.自定义线程池;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Demo {
    public static void main(String[] args) throws Exception {
        SimpleThreadPoolExecutor pool = new SimpleThreadPoolExecutor(
                2,
                4,
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                SimpleThreadPoolExecutor.defaultThreadFactory(),
                new SimpleThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.execute(() -> System.out.println(Thread.currentThread().getName()));
        Future<Integer> f = pool.submit(new Callable<Integer>() {
            public Integer call() {
                return 42;
            }
        });
        System.out.println(f.get());
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}

