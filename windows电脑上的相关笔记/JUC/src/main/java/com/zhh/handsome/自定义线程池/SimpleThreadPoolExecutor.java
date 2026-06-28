package com.zhh.handsome.自定义线程池;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleThreadPoolExecutor {
    public interface RejectedExecutionHandler {
        void rejectedExecution(Runnable r, SimpleThreadPoolExecutor e);
    }

    public static class AbortPolicy implements RejectedExecutionHandler {
        public void rejectedExecution(Runnable r, SimpleThreadPoolExecutor e) {
            throw new RejectedExecutionException();
        }
    }

    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        public void rejectedExecution(Runnable r, SimpleThreadPoolExecutor e) {
            if (!e.isShutdown()) r.run();
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler {
        public void rejectedExecution(Runnable r, SimpleThreadPoolExecutor e) {}
    }

    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public void rejectedExecution(Runnable r, SimpleThreadPoolExecutor e) {
            if (e.isShutdown()) return;
            e.workQueue.poll();
            e.execute(r);
        }
    }

    static class DefaultThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "simple-pool-" + poolNumber.getAndIncrement() + "-thread-";
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            if (t.isDaemon()) t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    static ThreadFactory defaultThreadFactory() {
        return new DefaultThreadFactory();
    }

    private final AtomicInteger workerCount = new AtomicInteger();
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;
    private volatile long keepAliveTimeNanos;
    private volatile boolean allowCoreThreadTimeOut;
    final BlockingQueue<Runnable> workQueue;
    private volatile ThreadFactory threadFactory;
    private volatile RejectedExecutionHandler handler;
    private final ReentrantLock mainLock = new ReentrantLock();
    private final Condition termination = mainLock.newCondition();
    private final HashSet<Worker> workers = new HashSet<>();
    private volatile boolean shutdown;
    private volatile boolean stopped;
    private long completedTaskCount;

    public SimpleThreadPoolExecutor(int corePoolSize,
                                    int maximumPoolSize,
                                    long keepAliveTime,
                                    TimeUnit unit,
                                    BlockingQueue<Runnable> workQueue,
                                    ThreadFactory threadFactory,
                                    RejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException();
        }
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTimeNanos = unit.toNanos(keepAliveTime);
        this.workQueue = workQueue;
        this.threadFactory = threadFactory == null ? defaultThreadFactory() : threadFactory;
        this.handler = handler == null ? new AbortPolicy() : handler;
    }

    public SimpleThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, int queueCapacity) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, new LinkedBlockingQueue<>(queueCapacity), defaultThreadFactory(), new AbortPolicy());
    }

    public void execute(Runnable command) {
        if (command == null) throw new NullPointerException();
        if (isRunning() && workerCount.get() < corePoolSize && addWorker(command, true)) {
            return;
        }
        if (isRunning() && workQueue.offer(command)) {
            if (!isRunning() && remove(command)) reject(command);
            else if (workerCount.get() == 0) addWorker(null, false);
        } else if (!addWorker(command, false)) {
            reject(command);
        }
    }

    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) throw new NullPointerException();
        FutureTask<T> f = new FutureTask<>(task);
        execute(f);
        return f;
    }

    public Future<?> submit(Runnable task) {
        if (task == null) throw new NullPointerException();
        FutureTask<?> f = new FutureTask<>(task, null);
        execute(f);
        return f;
    }

    public <T> Future<T> submit(Runnable task, T result) {
        if (task == null) throw new NullPointerException();
        FutureTask<T> f = new FutureTask<>(task, result);
        execute(f);
        return f;
    }

    public void shutdown() {
        mainLock.lock();
        try {
            shutdown = true;
            interruptIdleWorkers();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    public List<Runnable> shutdownNow() {
        mainLock.lock();
        try {
            stopped = true;
            List<Runnable> tasks = drainQueue();
            interruptWorkers();
            tryTerminate();
            return tasks;
        } finally {
            mainLock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown || stopped;
    }

    public boolean isTerminated() {
        return (shutdown || stopped) && workerCount.get() == 0;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        mainLock.lock();
        try {
            while (!isTerminated()) {
                if (nanos <= 0L) return false;
                nanos = termination.awaitNanos(nanos);
            }
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int size) {
        if (size < 0) throw new IllegalArgumentException();
        int delta = size - this.corePoolSize;
        this.corePoolSize = size;
        if (workerCount.get() > size) interruptIdleWorkers();
        else if (delta > 0) addWorker(null, true);
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int size) {
        if (size <= 0 || size < corePoolSize) throw new IllegalArgumentException();
        this.maximumPoolSize = size;
    }

    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTimeNanos, TimeUnit.NANOSECONDS);
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) throw new IllegalArgumentException();
        this.keepAliveTimeNanos = unit.toNanos(time);
    }

    public void allowCoreThreadTimeOut(boolean value) {
        this.allowCoreThreadTimeOut = value;
        if (value) interruptIdleWorkers();
    }

    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public int getPoolSize() {
        return workerCount.get();
    }

    public int getActiveCount() {
        mainLock.lock();
        try {
            int c = 0;
            for (Worker w : workers) if (w.active) c++;
            return c;
        } finally {
            mainLock.unlock();
        }
    }

    public long getCompletedTaskCount() {
        mainLock.lock();
        try {
            return completedTaskCount;
        } finally {
            mainLock.unlock();
        }
    }

    private boolean isRunning() {
        return !shutdown && !stopped;
    }

    private void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    private boolean remove(Runnable task) {
        return workQueue.remove(task);
    }

    private List<Runnable> drainQueue() {
        List<Runnable> tasks = new ArrayList<>();
        workQueue.drainTo(tasks);
        if (!workQueue.isEmpty()) {
            for (Runnable r : workQueue.toArray(new Runnable[0])) {
                if (workQueue.remove(r)) tasks.add(r);
            }
        }
        return tasks;
    }

    private void interruptWorkers() {
        for (Worker w : workers) {
            w.thread.interrupt();
        }
    }

    private void interruptIdleWorkers() {
        for (Worker w : workers) {
            if (!w.active) w.thread.interrupt();
        }
    }

    private void tryTerminate() {
        if (!isTerminated()) return;
        mainLock.lock();
        try {
            termination.signalAll();
        } finally {
            mainLock.unlock();
        }
    }

    private boolean addWorker(Runnable firstTask, boolean core) {
        for (;;) {
            if (!isRunning() && firstTask != null) return false;
            int wc = workerCount.get();
            int cap = core ? corePoolSize : maximumPoolSize;
            if (wc >= cap) return false;
            if (workerCount.compareAndSet(wc, wc + 1)) break;
        }
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = new Worker(firstTask);
        final Thread t = w.thread;
        mainLock.lock();
        try {
            if (isRunning() || firstTask == null) {
                workers.add(w);
                workerAdded = true;
            }
        } finally {
            mainLock.unlock();
        }
        if (workerAdded) {
            t.start();
            workerStarted = true;
        }
        if (!workerStarted) {
            addWorkerFailed(w);
        }
        return workerStarted;
    }

    private void addWorkerFailed(Worker w) {
        mainLock.lock();
        try {
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }
        workerCount.decrementAndGet();
        tryTerminate();
    }

    private Runnable getTask() {
        boolean timed;
        for (;;) {
            if (stopped) return null;
            int wc = workerCount.get();
            timed = allowCoreThreadTimeOut || wc > corePoolSize;
            try {
                Runnable r = timed ? workQueue.poll(keepAliveTimeNanos, TimeUnit.NANOSECONDS) : workQueue.take();
                if (r != null) return r;
                if (timed) return null;
            } catch (InterruptedException e) {
                if (stopped) return null;
            }
        }
    }

    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        mainLock.lock();
        try {
            workers.remove(w);
            completedTaskCount += w.completedTasks;
        } finally {
            mainLock.unlock();
        }
        workerCount.decrementAndGet();
        tryTerminate();
        if (isRunning()) {
            addWorker(null, false);
        }
    }

    private final class Worker implements Runnable {
        final Thread thread;
        Runnable firstTask;
        volatile boolean active;
        long completedTasks;
        Worker(Runnable firstTask) {
            this.firstTask = firstTask;
            this.thread = threadFactory.newThread(this);
        }
        public void run() {
            runWorker(this);
        }
    }

    private void runWorker(Worker w) {
        Runnable task = w.firstTask;
        w.firstTask = null;
        boolean abrupt = true;
        try {
            while (true) {
                if (task == null) {
                    task = getTask();
                }
                if (task == null) break;
                w.active = true;
                try {
                    task.run();
                } finally {
                    w.active = false;
                    task = null;
                    w.completedTasks++;
                }
            }
            abrupt = false;
        } finally {
            processWorkerExit(w, abrupt);
        }
    }
}

