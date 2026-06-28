package com.zhh.handsome.可见性1;

public class 犹豫模式 {

   /* 线程（或组件）在执行关键操作前，先 “犹豫”（即检查条件、等待信号或确认状态），
    避免因操之过急导致错误（如竞态条件、资源冲突、状态不一致等）。





    为什么需要 “犹豫”？
    在并发场景中，很多错误源于 “未确认状态就行动”：
    比如线程 A 想释放一个资源，但没确认线程 B 是否还在使用该资源，直接释放可能导致 B 操作失败；
    又如线程想获取锁，但没检查锁的持有者是否即将释放，盲目争抢可能导致频繁上下文切换，浪费资源。
            “犹豫模式” 本质是通过 **“先观察，再行动”** 的逻辑，减少这类 “冒进” 带来的问题，尤其适合依赖外部状态或多组件协作的场景。
    典型场景与实现思路
“犹豫” 的核心是在关键操作前增加 “检查 - 确认” 环节，具体实现因场景而异，以下是几个典型例子：
    场景 1：避免 “过早释放资源”
    假设有一个线程池，其中的工作线程在完成任务后需要释放一个共享连接，但必须确保没有其他线程正在使用该连接。此时工作线程不能直接释放，而需要 “犹豫”：先检查连接的引用计数，确认计数为 0（无其他使用者）后再释放。
    示例伪代码：
    java
            运行
    class SharedConnection {
        private AtomicInteger refCount = new AtomicInteger(0); // 引用计数
        private Connection conn;

        // 获取连接（增加引用）
        public Connection acquire() {
            refCount.incrementAndGet();
            return conn;
        }

        // 释放连接（先犹豫检查）
        public void release() {
            // 犹豫：循环检查引用计数，直到确认可以安全释放
            while (true) {
                int current = refCount.get();
                if (current == 0) {
                    // 已经被其他线程释放，无需操作
                    break;
                }
                // 尝试减少引用计数，若减到0则关闭连接
                if (refCount.compareAndSet(current, current - 1)) {
                    if (current - 1 == 0) {
                        conn.close(); // 最终释放
                    }
                    break;
                }
                // 若CAS失败（被其他线程修改），则重试（继续犹豫）
                Thread.yield(); // 让出CPU，减少竞争
            }
        }
    }
    这里的 “犹豫” 体现在：释放前通过循环 + CAS 不断检查引用计数，确保没有其他线程同时操作，避免过早或重复释放。
    场景 2：锁竞争中的 “退避策略”
    当多个线程争抢同一把锁时，若失败就立即重试（如自旋锁），可能导致 CPU 资源浪费。“犹豫” 策略在此处表现为：失败后不立即重试，而是等待一小段时间再尝试，减少无效竞争。
    这本质是 “指数退避”（Exponential Backoff）的思想，常见于分布式锁或高并发锁竞争场景：
    示例伪代码：
    java
            运行
    class HesitantLock {
        private AtomicBoolean locked = new AtomicBoolean(false);

        public void lock() {
            int delay = 1; // 初始延迟时间（毫秒）
            while (true) {
                if (locked.compareAndSet(false, true)) {
                    // 获取锁成功，退出
                    return;
                }
                // 犹豫：获取失败，等待一段时间再重试
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // 延迟时间指数增长（避免长期等待）
                delay = Math.min(delay * 2, 100); // 最大延迟100ms
            }
        }

        public void unlock() {
            locked.set(false);
        }
    }
    这里的 “犹豫” 是主动延迟重试，给其他线程释放锁的时间，减少 CPU 空转。
    场景 3：两阶段终止中的 “确认终止信号”
    在之前讲的 “两阶段终止” 中，目标线程其实也包含 “犹豫” 逻辑：它不会收到终止信号后立即退出，而是先 “犹豫”（检查是否有未完成的任务、是否需要保存状态），确认可以终止后再执行清理。
    例如，线程在循环中检查终止标志时，若标志为true，不会直接 break，而是先判断当前是否在执行关键任务：
    java
            运行
while (!isTerminated) {
        if (hasPendingTask()) { // 犹豫：检查是否有未完成任务
            executeTask(); // 先完成任务
        } else {
            Thread.sleep(100); // 无任务时休眠，减少消耗
        }
    }
// 确认所有任务完成后，再清理退出
    核心特点与注意事项
“犹豫” 不是 “阻塞”：犹豫是主动的 “检查 - 等待 - 重试” 逻辑，而阻塞是被动等待（如sleep、wait）。犹豫的目的是 “确保条件成熟”，而阻塞更多是 “等待事件触发”。
    避免 “过度犹豫”：犹豫的时间或次数需要控制（如设置最大延迟、最大重试次数），否则可能导致响应变慢（比如锁竞争中延迟太久，影响业务效率）。
    依赖原子操作或可见性保障：犹豫过程中检查的状态（如引用计数、锁状态、终止标志）必须是线程安全的（如用volatile、Atomic类），否则 “犹豫” 本身可能因读取旧状态而失效。
    总结
“犹豫模式” 更像是一种并发设计中的 “谨慎原则”：通过在关键操作前增加 “观察 - 确认” 环节，避免因状态不确定导致的错误。它没有固定的实现模板，但核心逻辑是 “延迟决策，确保条件成熟后再行动”，常见于资源管理、锁竞争、协作终止等场景。*/

}
