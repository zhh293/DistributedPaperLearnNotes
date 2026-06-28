/*
package com.zhh.handsome.自定义线程池;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

*/
/**
 * 面试专用手写线程池（覆盖所有核心考点）
 *//*

public class MyThreadPool {
    // ===================== 核心考点1：线程池状态管理 =====================
    // ctl：高3位存状态，低29位存工作线程数（面试高频考点：用一个原子整数管理两个状态）
    private final AtomicInteger ctl = new AtomicInteger(0);
    // 状态常量（高3位）
    private static final int RUNNING = 0; // 运行中：接受新任务+处理队列任务
    private static final int SHUTDOWN = 1 << 29; // 关闭中：不接受新任务，但处理队列任务
    private static final int STOP = 2 << 29; // 停止：不接受新任务，不处理队列任务，中断正在执行的任务
    private static final int TERMINATED = 3 << 29; // 终止：所有任务完成，所有线程退出

    // ===================== 核心考点2：线程池核心参数 =====================
    private final int corePoolSize; // 核心线程数
    private final int maximumPoolSize; // 最大线程数
    private final long keepAliveTime; // 非核心线程空闲超时时间
    private final TimeUnit unit; // 时间单位
    private final Deque<Runnable> workQueue; // 任务队列（阻塞队列）
    private final RejectedExecutionHandler handler; // 拒绝策略

    // 工作线程集合（存储所有Worker）
    private final Set<Worker> workers = new HashSet<>();
    // 锁和条件（保证线程安全）
    private final ReentrantLock mainLock = new ReentrantLock();
    private final Condition condition = mainLock.newCondition();

    // ===================== 构造方法（初始化核心参数） =====================
    public MyThreadPool(int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            Deque<Runnable> workQueue,
            RejectedExecutionHandler handler) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        this.handler = handler;
        // 初始化状态为RUNNING，线程数为0
        this.ctl.set(RUNNING);
    }

    // 便捷构造方法（默认任务队列和拒绝策略）
    public MyThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit,
                new ArrayDeque<>(), new AbortPolicy());
    }

    // ===================== 核心考点3：任务提交核心逻辑（execute方法） =====================
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException("任务不能为空");

        int c = ctl.get();
        // 1. 工作线程数 < 核心线程数：创建新核心线程执行任务
        if (getWorkerCount(c) < corePoolSize) {
            if (addWorker(task, true))
                return;
            c = ctl.get(); // 重新获取ctl，避免并发修改
        }

        // 2. 线程池运行中 + 任务队列未满：加入队列
        if (isRunning(c) && workQueue.offer(task)) {
            int recheck = ctl.get();
            // 二次检查：如果线程池已非运行状态，移除任务并执行拒绝策略
            if (!isRunning(recheck) && removeTask(task)) {
                reject(task);
            }
            // 无工作线程：创建非核心线程
            else if (getWorkerCount(recheck) == 0) {
                addWorker(null, false);
            }
        }

        // 3. 队列满：创建非核心线程（直到最大线程数）
        else if (!addWorker(task, false)) {
            // 4. 最大线程数已满：执行拒绝策略
            reject(task);
        }
    }

    // ===================== 核心考点4：线程复用（Worker内部类） =====================
    private class Worker implements Runnable {
        private final Thread thread; // 工作线程
        private Runnable firstTask; // 第一个任务（初始化后复用）

        public Worker(Runnable firstTask) {
            this.firstTask = firstTask;
            this.thread = new Thread(this); // 绑定当前Worker
        }

        @Override
        public void run() {
            // 核心：循环获取任务（实现线程复用）
            Runnable task = firstTask;
            firstTask = null;
            while (task != null || (task = getTask()) != null) {
                try {
                    task.run(); // 执行任务
                } finally {
                    task = null;
                    // 任务执行完，更新线程数（减少）
                    decrementWorkerCount();
                }
            }
            // 无任务可执行：移除当前Worker，标记线程退出
            mainLock.lock();
            try {
                workers.remove(this);
                // 检查是否所有线程都退出，更新状态为TERMINATED
                if (workers.isEmpty() && !isRunning(ctl.get())) {
                    ctl.set(TERMINATED);
                }
            } finally {
                mainLock.unlock();
            }
        }
    }

    // ===================== 辅助方法：创建工作线程 =====================
    private boolean addWorker(Runnable firstTask, boolean isCore) {
        mainLock.lock();
        try {
            int c = ctl.get();
            // 线程池已关闭/停止：不创建新线程
            if (!isRunning(c))
                return false;

            int workerCount = getWorkerCount(c);
            // 判断是否超过核心/最大线程数
            int limit = isCore ? corePoolSize : maximumPoolSize;
            if (workerCount >= limit)
                return false;

            // 创建Worker并加入集合
            Worker worker = new Worker(firstTask);
            workers.add(worker);
            // 增加工作线程数
            incrementWorkerCount();
            // 启动线程
            worker.thread.start();
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    // ===================== 辅助方法：获取任务（实现线程复用的核心） =====================
    private Runnable getTask() {
        boolean timedOut = false; // 是否超时
        while (true) {
            int c = ctl.get();
            // 线程池停止，或SHUTDOWN且队列为空：返回null，线程退出
            if ((!isRunning(c) && isShutdown(c)) || (isStop(c) && workQueue.isEmpty())) {
                return null;
            }

            int workerCount = getWorkerCount(c);
            // 非核心线程：超时回收；核心线程：一直等待
            boolean timed = workerCount > corePoolSize;

            // 超时且线程数>1 或 队列空：返回null，线程退出
            if ((timed && timedOut) || (workerCount > maximumPoolSize)) {
                return null;
            }

            try {
                // 从队列取任务（超时/阻塞）
                Runnable task = timed ? workQueue.poll() : // 非核心线程：超时等待
                        workQueue.take(); // 核心线程：阻塞等待
                if (task != null)
                    return task;
                timedOut = true; // 超时未取到任务
            } catch (InterruptedException e) {
                timedOut = false;
                Thread.currentThread().interrupt(); // 重置中断状态
            }
        }
    }

    // ===================== 核心考点5：拒绝策略（4种经典实现） =====================
    public interface RejectedExecutionHandler {
        void rejectedExecution(Runnable r, MyThreadPool pool);
    }

    // 1. 抛出异常（默认）
    public static class AbortPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, MyThreadPool pool) {
            throw new RejectedExecutionException("任务 " + r + " 被拒绝，线程池状态：" + pool.getState());
        }
    }

    // 2. 调用者执行（由提交任务的线程执行）
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, MyThreadPool pool) {
            if (!pool.isShutdown()) {
                r.run(); // 调用者线程执行
            }
        }
    }

    // 3. 丢弃最新任务（队列满时丢弃当前任务）
    public static class DiscardPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, MyThreadPool pool) {
            // 空实现：直接丢弃
        }
    }

    // 4. 丢弃最旧任务（丢弃队列头部任务，加入当前任务）
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, MyThreadPool pool) {
            if (!pool.isShutdown()) {
                pool.workQueue.poll(); // 丢弃队首
                pool.execute(r); // 重新提交当前任务
            }
        }
    }

    // 执行拒绝策略
    private void reject(Runnable task) {
        handler.rejectedExecution(task, this);
    }

    // ===================== 核心考点6：线程池关闭 =====================
    // 优雅关闭：不接受新任务，处理完队列任务
    public void shutdown() {
        mainLock.lock();
        try {
            // 更新状态为SHUTDOWN
            ctl.set(SHUTDOWN);
            // 中断空闲线程（等待队列任务的线程）
            for (Worker worker : workers) {
                Thread thread = worker.thread;
                if (!thread.isInterrupted()) {
                    thread.interrupt();
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    // 强制关闭：不接受新任务，不处理队列任务，中断所有线程
    public void shutdownNow() {
        mainLock.lock();
        try {
            // 更新状态为STOP
            ctl.set(STOP);
            // 中断所有线程（包括正在执行任务的）
            for (Worker worker : workers) {
                Thread thread = worker.thread;
                if (!thread.isInterrupted()) {
                    thread.interrupt();
                }
            }
            // 清空任务队列
            workQueue.clear();
        } finally {
            mainLock.unlock();
        }
    }

    // ===================== 辅助方法：状态和线程数操作 =====================
    // 获取线程数（低29位）
    private int getWorkerCount(int c) {
        return c & ((1 << 29) - 1);
    }

    // 获取状态（高3位）
    private int getState(int c) {
        return c & (~((1 << 29) - 1));
    }

    // 判断是否运行中
    private boolean isRunning(int c) {
        return getState(c) == RUNNING;
    }

    // 判断是否SHUTDOWN
    private boolean isShutdown(int c) {
        return getState(c) == SHUTDOWN;
    }

    // 判断是否STOP
    private boolean isStop(int c) {
        return getState(c) == STOP;
    }

    // 增加线程数
    private void incrementWorkerCount() {
        ctl.incrementAndGet();
    }

    // 减少线程数
    private void decrementWorkerCount() {
        ctl.decrementAndGet();
    }

    // 移除任务
    private boolean removeTask(Runnable task) {
        return workQueue.remove(task);
    }

    // 获取当前状态（对外展示）
    public String getState() {
        int c = ctl.get();
        if (isRunning(c))
            return "RUNNING";
        if (isShutdown(c))
            return "SHUTDOWN";
        if (isStop(c))
            return "STOP";
        return "TERMINATED";
    }

    // 测试方法（面试时可手写简单测试）
    public static void main(String[] args) {
        // 创建线程池：核心2，最大4，空闲10秒，默认队列和拒绝策略
        MyThreadPool pool = new MyThreadPool(2, 4, 10, TimeUnit.SECONDS);

        // 提交5个任务（第5个触发拒绝策略）
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            pool.execute(() -> {
                System.out.println("任务" + finalI + "执行，线程：" + Thread.currentThread().getName());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 关闭线程池
        pool.shutdown();
    }
}
*/
