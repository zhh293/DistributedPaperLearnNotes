# ScheduledThreadPoolExecutor 深度解析

> 本文基于 JDK 8 / JDK 11 源码，从设计动机、整体架构、核心源码逐行分析三个层面，彻底讲透 ScheduledThreadPoolExecutor 的工作原理。适合有一定 Java 基础、想深入理解并发调度机制的读者。

---

## 一、为什么需要 ScheduledThreadPoolExecutor

### 1.1 从 Timer 的缺陷说起

在 JDK 早期（1.3），如果你想实现"每隔 5 秒刷新一次缓存"或"延迟 10 秒执行一个任务"，只能使用 `java.util.Timer` + `TimerTask`。Timer 的用法很简单：

```java
Timer timer = new Timer();
timer.schedule(new TimerTask() {
    @Override
    public void run() {
        System.out.println("执行定时任务");
    }
}, 1000, 5000); // 延迟1秒后，每5秒执行一次
```

看起来很方便，但 Timer 有三个致命缺陷：

**缺陷一：单线程执行所有任务**

Timer 内部只有一个线程（`TimerThread`），所有被 schedule 的 TimerTask 都排在同一个队列里，由这一个线程依次执行。假设你往 Timer 里塞了任务 A（耗时 10 秒）和任务 B（每 1 秒执行一次），那么任务 B 在 A 执行期间完全被阻塞，调度精度完全无法保证。

```java
Timer timer = new Timer();
// 任务A：耗时10秒
timer.schedule(new TimerTask() {
    public void run() {
        try { Thread.sleep(10000); } catch (Exception e) {}
        System.out.println("任务A完成");
    }
}, 0);
// 任务B：希望每1秒执行一次，但会被任务A堵死
timer.schedule(new TimerTask() {
    public void run() {
        System.out.println("任务B执行 " + System.currentTimeMillis());
    }
}, 0, 1000);
```

运行后你会发现，任务 B 在前 10 秒内一次都没有执行，因为唯一的线程被任务 A 占着。

**缺陷二：一个任务抛异常，整个 Timer 崩溃**

Timer 内部的 `TimerThread.run()` 方法结构大致如下：

```java
// Timer源码简化
public void run() {
    try {
        mainLoop(); // 不断从队列取任务执行
    } finally {
        // 线程结束，Timer彻底停摆
    }
}
```

`mainLoop()` 内部没有对单个任务的异常做 try-catch。一旦某个 TimerTask 的 `run()` 抛出了未捕获的 RuntimeException，`mainLoop()` 直接退出，`TimerThread` 死亡，所有后续任务永远不会被执行——即使那些任务本身完全正常。

```java
Timer timer = new Timer();
timer.schedule(new TimerTask() {
    public void run() {
        throw new RuntimeException("我炸了！");
    }
}, 0);
// 这个正常任务永远不会执行
timer.schedule(new TimerTask() {
    public void run() {
        System.out.println("我是正常任务，但我也完蛋了");
    }
}, 1000, 1000);
```

**缺陷三：时间基准受系统时钟影响**

Timer 内部使用 `System.currentTimeMillis()` 来计算任务的下次执行时间。这个方法返回的是"墙上时钟"，如果运维人员手动调了系统时间（比如 NTP 同步导致时间回拨），任务的调度就会出现混乱——可能突然一堆任务同时触发，也可能任务被推迟很久才执行。

### 1.2 ScheduledThreadPoolExecutor 的定位

为了解决上述三大缺陷，Doug Lea 在 JDK 5 引入了 `java.util.concurrent.ScheduledThreadPoolExecutor`，它的定位非常明确：**基于线程池的定时/周期任务调度器**。

它如何解决上述三个问题：

**多线程执行**：继承自 `ThreadPoolExecutor`，拥有线程池的所有能力。你可以配置核心线程数（corePoolSize），多个任务可以被不同线程并发执行，互不阻塞。

**任务隔离**：每个任务在 `Worker` 线程中被独立的 try-catch 包裹（这是 ThreadPoolExecutor 的基础能力），一个任务抛异常不影响其他任务的执行。

**单调时钟**：内部使用 `System.nanoTime()` 作为时间基准。`nanoTime()` 返回的是 JVM 启动以来经过的纳秒数，它是单调递增的，不受系统时钟修改影响，调度精度更可靠。

### 1.3 继承关系

```
java.util.concurrent.Executor                    （最顶层接口）
    ↓
java.util.concurrent.ExecutorService              （增加生命周期管理）
    ↓
java.util.concurrent.AbstractExecutorService      （模板实现）
    ↓
java.util.concurrent.ThreadPoolExecutor           （通用线程池实现）
    ↓
java.util.concurrent.ScheduledThreadPoolExecutor  （定时调度增强）
```

`ScheduledThreadPoolExecutor` 同时实现了 `ScheduledExecutorService` 接口，这个接口定义了四个核心调度方法：

```java
public interface ScheduledExecutorService extends ExecutorService {
    // 延迟执行一次
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);
    // 延迟执行一次，带返回值
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);
    // 固定频率周期执行
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);
    // 固定延迟周期执行
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
```

关键理解点：`ScheduledThreadPoolExecutor` 并不是从零开始写的全新调度器，它是在 `ThreadPoolExecutor` 的框架上，通过**替换工作队列（workQueue）**和**包装任务对象（ScheduledFutureTask）**来实现定时调度能力的。这种设计复用了线程池的线程管理、拒绝策略、关闭机制等所有基础能力，非常优雅。

---

## 二、整体架构设计

### 2.1 三大核心组件

ScheduledThreadPoolExecutor 的核心设计可以用三个组件来概括：

```
+-------------------------------------------+
|    ScheduledThreadPoolExecutor            |
|    (继承 ThreadPoolExecutor)               |
|                                           |
|  ┌─────────────────────────────────────┐  |
|  │  DelayedWorkQueue（延迟工作队列）     │  |
|  │  ┌─────────────────────────────┐    │  |
|  │  │ ScheduledFutureTask[0]      │    │  |
|  │  │ (time=100, period=5000)     │    │  |
|  │  ├─────────────────────────────┤    │  |
|  │  │ ScheduledFutureTask[1]      │    │  |
|  │  │ (time=200, period=0)        │    │  |
|  │  ├─────────────────────────────┤    │  |
|  │  │ ScheduledFutureTask[2]      │    │  |
|  │  │ (time=300, period=-3000)    │    │  |
|  │  └─────────────────────────────┘    │  |
|  │  （小顶堆，堆顶是最早要执行的任务）   │  |
|  └─────────────────────────────────────┘  |
|                                           |
|  Worker线程1 ──> 从堆顶take() ──> 执行任务 |
|  Worker线程2 ──> 从堆顶take() ──> 执行任务 |
|  Worker线程3 ──> 等待...                   |
+-------------------------------------------+
```

**组件一：ScheduledFutureTask**——任务的包装器。用户提交的 Runnable/Callable 会被包装成 `ScheduledFutureTask`，它记录了"何时执行"、"是否周期性"、"周期是多少"等关键信息。

**组件二：DelayedWorkQueue**——延迟工作队列。这是一个基于数组实现的小顶堆（最小堆），堆顶永远是"最早应该执行"的任务。Worker 线程从堆顶取任务，如果堆顶任务还没到执行时间，线程就会 park 等待。

**组件三：ScheduledThreadPoolExecutor 本身**——继承 ThreadPoolExecutor，在构造函数中将 workQueue 指定为 DelayedWorkQueue，将任务包装为 ScheduledFutureTask，其他线程管理逻辑完全复用父类。

### 2.2 构造函数：一切从这里开始

```java
// ScheduledThreadPoolExecutor 的构造函数
public ScheduledThreadPoolExecutor(int corePoolSize) {
    // 调用父类 ThreadPoolExecutor 的构造函数
    super(corePoolSize,             // 核心线程数
          Integer.MAX_VALUE,        // 最大线程数（实际上不会用到，后面解释）
          DEFAULT_KEEPALIVE_MILLIS, // 非核心线程存活时间
          MILLISECONDS,            // 时间单位
          new DelayedWorkQueue()); // ★ 关键：使用自定义的延迟队列
}
```

这里有一个微妙之处：`maximumPoolSize` 被设为 `Integer.MAX_VALUE`，但这并不意味着线程数会无限增长。因为 `DelayedWorkQueue` 是一个**无界队列**（容量无限），`ThreadPoolExecutor` 的逻辑是：只有当队列满了才会创建超过 corePoolSize 的线程。既然队列永远不满，那么线程数永远不会超过 corePoolSize。所以实际上，ScheduledThreadPoolExecutor 的线程数就是 corePoolSize。

### 2.3 三者的协作流程

当你调用 `scheduleAtFixedRate(task, 0, 5, SECONDS)` 时，完整的执行流程如下：

```
1. 用户调用 scheduleAtFixedRate(task, 0, 5, SECONDS)
       ↓
2. 创建 ScheduledFutureTask，设置：
   - time = now() + 0 = now()  （立即执行）
   - period = 5秒（正数，代表fixedRate）
       ↓
3. 调用 delayedExecute(task)，将 task 放入 DelayedWorkQueue
       ↓
4. DelayedWorkQueue.offer(task)：
   - 将 task 加入小顶堆
   - 如果 task 成了堆顶（最早要执行的），唤醒等待的线程
       ↓
5. Worker 线程调用 DelayedWorkQueue.take()：
   - 查看堆顶：到时间了吗？
   - 到了 → 取出来执行
   - 没到 → park 等待（精确等待到堆顶任务的触发时间）
       ↓
6. Worker 线程执行 ScheduledFutureTask.run()：
   - 执行用户的 task
   - 如果是周期任务：计算下次执行时间 → 重新放回队列
       ↓
7. 回到第5步，Worker 继续取下一个任务
```

---

## 三、ScheduledFutureTask 源码深度分析

`ScheduledFutureTask` 是 ScheduledThreadPoolExecutor 的内部类，也是整个调度机制的灵魂。每一个被提交的定时任务，最终都会被包装成一个 `ScheduledFutureTask` 对象。

### 3.1 核心字段

```java
private class ScheduledFutureTask<V>
        extends FutureTask<V> implements RunnableScheduledFuture<V> {

    /** 
     * 序列号，用于在两个任务的触发时间(time)完全相同时，
     * 保证先提交的任务排在前面（FIFO）。
     * 这是一个全局递增的AtomicLong，每创建一个任务就+1。
     */
    private final long sequenceNumber;

    /** 
     * 任务应该被执行的时间点，单位是纳秒。
     * 使用 System.nanoTime() 作为基准。
     * 
     * 比如你调用 schedule(task, 5, SECONDS)，
     * 那么 time = System.nanoTime() + 5_000_000_000L
     */
    private volatile long time;

    /**
     * 周期，单位是纳秒。这个字段的符号有特殊含义：
     * 
     * period > 0：表示 fixedRate 模式
     *   下次时间 = 本次计划时间 + period
     * 
     * period < 0：表示 fixedDelay 模式
     *   下次时间 = 本次完成时间 + |period|
     * 
     * period == 0：表示一次性任务（不是周期任务）
     */
    private final long period;

    /** 
     * 当前任务在 DelayedWorkQueue 堆数组中的索引位置。
     * 
     * 这个字段是为了优化取消操作：
     * 普通堆要取消一个元素，需要先遍历找到它（O(n)），
     * 再做堆调整（O(logN)），总共 O(n)。
     * 
     * 有了 heapIndex，取消时直接定位到位置（O(1)），
     * 再做堆调整（O(logN)），总共 O(logN)。
     */
    int heapIndex;

    /** 
     * 周期性任务在 reExecutePeriodic 时使用的"下次执行版本"。
     * 即 outerTask = this，这样 re-schedule 时放回队列的还是同一个对象。
     */
    RunnableScheduledFuture<V> outerTask = this;
}
```

### 3.2 构造函数

```java
ScheduledFutureTask(Runnable r, V result, long triggerTime, long period,
                    long sequenceNumber) {
    super(r, result);                    // 调用 FutureTask 的构造函数
    this.time = triggerTime;             // 设置触发时间
    this.period = period;                // 设置周期（0=一次性，>0=fixedRate，<0=fixedDelay）
    this.sequenceNumber = sequenceNumber;// 设置序列号
}
```

`triggerTime` 的计算在外部完成：

```java
// ScheduledThreadPoolExecutor 中
private long triggerTime(long delay, TimeUnit unit) {
    return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
}

long triggerTime(long delay) {
    // now() 返回的是 System.nanoTime()
    // 如果 delay < Long.MAX_VALUE/2，直接 now + delay
    // 否则做溢出检查（防止 long 溢出）
    return now() +
        ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
}
```

### 3.3 compareTo() 方法：决定堆中的排列顺序

DelayedWorkQueue 是一个小顶堆，堆的排序规则由 `compareTo()` 决定。这个方法直接决定了"哪个任务先被执行"：

```java
public int compareTo(Delayed other) {
    if (other == this) // 和自己比，肯定相等
        return 0;
    if (other instanceof ScheduledFutureTask) {
        ScheduledFutureTask<?> x = (ScheduledFutureTask<?>)other;
        long diff = time - x.time;
        
        // 规则1：触发时间早的排在前面
        if (diff < 0)
            return -1;      // this 比 other 早 → this 排前面
        else if (diff > 0)
            return 1;       // this 比 other 晚 → other 排前面
        
        // 规则2：触发时间相同，序列号小的排在前面（先提交的先执行，FIFO）
        else if (sequenceNumber < x.sequenceNumber)
            return -1;      // this 先提交 → this 排前面
        else
            return 1;       // other 先提交 → other 排前面
    }
    
    // 如果不是 ScheduledFutureTask，退化为比较 getDelay()
    long diff = (getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS));
    return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
}
```

简单总结排序规则：**先按触发时间排，时间相同按提交顺序排**。这保证了堆顶永远是"最早应该执行的任务"，如果时间相同则先到先得。

### 3.4 getDelay() 方法：计算距离触发还有多久

```java
public long getDelay(TimeUnit unit) {
    // time 是绝对触发时间（nanoTime基准）
    // now() 是当前 nanoTime
    // 差值就是"还要等多久"
    return unit.convert(time - now(), NANOSECONDS);
}
```

当 `getDelay()` 返回 <= 0 时，说明任务到期了，可以执行。`DelayedWorkQueue.take()` 内部就是靠这个方法判断堆顶任务是否到期。

### 3.5 run() 方法：核心中的核心

`run()` 方法是 ScheduledFutureTask 最关键的方法。当 Worker 线程从队列中取到一个任务时，就会调用这个方法。它需要处理两种情况：一次性任务和周期性任务。

```java
public void run() {
    // 第一步：判断是否是周期性任务
    boolean periodic = isPeriodic();  // period != 0 就是周期性任务
    
    // 第二步：检查当前线程池状态是否允许执行这个任务
    // 如果线程池已经 shutdown 了，根据配置决定是否继续执行
    // canRunInCurrentRunState 会检查：
    //   - 对于一次性任务：executeExistingDelayedTasksAfterShutdown（默认true）
    //   - 对于周期任务：continueExistingPeriodicTasksAfterShutdown（默认false）
    if (!canRunInCurrentRunState(periodic))
        cancel(false);  // 不允许执行 → 取消
    
    // 第三步：非周期性任务（一次性任务）
    else if (!periodic)
        // 直接调用父类 FutureTask.run()
        // 这会执行用户的 Callable/Runnable，并设置 Future 的结果
        super.run();
    
    // 第四步：周期性任务
    else if (super.runAndReset()) {
        // runAndReset() 和 run() 的区别：
        //   - run()：执行完后将 FutureTask 状态设为 COMPLETED（不能再次执行）
        //   - runAndReset()：执行完后将状态重置回 NEW（可以再次执行）
        // 
        // runAndReset() 返回 true 表示执行成功（没有抛异常）
        // 如果执行过程中抛了未捕获异常，返回 false，不再重新调度
        // ★ 这就是为什么周期任务内部不 catch 异常会导致后续调度终止的原因
        
        setNextRunTime();          // 计算下次执行时间
        reExecutePeriodic(outerTask); // 将任务重新放回队列
    }
    // 如果 runAndReset() 返回 false（任务抛异常了），
    // 就不会进入这个 if 分支，也就不会重新入队 → 周期任务自动终止
}
```

这段代码虽然不长，但信息量极大。让我一个个拆开分析。

**关于 `runAndReset()` 和异常的关系：**

```java
// FutureTask 的 runAndReset() 源码
protected boolean runAndReset() {
    // 只有在 NEW 状态才能执行
    if (state != NEW ||
        !RUNNER.compareAndSet(this, null, Thread.currentThread()))
        return false;
    boolean ran = false;
    int s = state;
    try {
        Callable<V> c = callable;
        if (c != null && s == NEW) {
            try {
                c.call();     // 执行用户的任务
                ran = true;   // 标记执行成功
            } catch (Throwable ex) {
                // ★ 异常被捕获了，ran 仍然是 false
                setException(ex);  // 将异常设置到 FutureTask 中
            }
        }
    } finally {
        runner = null;
        s = state;
        if (s >= INTERRUPTING)
            handlePossibleCancellationInterrupt(s);
    }
    // ★ ran = true 时返回 true（状态保持 NEW，可以再次执行）
    // ★ ran = false 时返回 false（任务不会再被调度）
    return ran && s == NEW;
}
```

这就解释了一个非常重要的行为：**如果你的周期性任务抛出了未捕获的异常，整个周期调度会静悄悄地终止，不会有任何提示**。这是 ScheduledThreadPoolExecutor 最容易踩的坑之一，后面在实战部分会详细讨论。

### 3.6 setNextRunTime()：计算下次执行时间

```java
private void setNextRunTime() {
    long p = period;
    
    if (p > 0)
        // ★ fixedRate 模式：
        // 下次时间 = 本次"计划"时间 + 周期
        // 注意是 time（计划时间），不是 now()（实际完成时间）
        // 这意味着如果本次执行耗时超过了 period，
        // 计算出的下次时间可能已经过去了（time + p < now()），
        // 那么任务重新入队后会立即被取出执行（getDelay() <= 0）
        time += p;
    else
        // ★ fixedDelay 模式（p < 0）：
        // 下次时间 = 当前时间 + |period|
        // 注意是 triggerTime(-p)，内部用的是 now() + delay
        // 这意味着下次执行时间是从"本次执行结束"开始算的
        time = triggerTime(-p);
}
```

这两行代码虽短，却是 `fixedRate` 和 `fixedDelay` 行为差异的根源。我们在第四节会用具体例子展开讲。

---

## 四、scheduleAtFixedRate vs scheduleWithFixedDelay 深度对比

### 4.1 方法签名

```java
// 固定频率
public ScheduledFuture<?> scheduleAtFixedRate(
    Runnable command,       // 要执行的任务
    long initialDelay,      // 首次执行的延迟时间
    long period,            // 两次执行的"开始时间"之间的间隔
    TimeUnit unit           // 时间单位
)

// 固定延迟
public ScheduledFuture<?> scheduleWithFixedDelay(
    Runnable command,       // 要执行的任务
    long initialDelay,      // 首次执行的延迟时间
    long delay,             // 一次执行"结束"到下一次执行"开始"之间的间隔
    TimeUnit unit           // 时间单位
)
```

### 4.2 内部实现对比

```java
// scheduleAtFixedRate 的实现
public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                              long initialDelay,
                                              long period,
                                              TimeUnit unit) {
    if (command == null || unit == null) throw new NullPointerException();
    if (period <= 0L) throw new IllegalArgumentException();
    
    ScheduledFutureTask<Void> sft =
        new ScheduledFutureTask<Void>(command, null,
                                      triggerTime(initialDelay, unit),
                                      unit.toNanos(period),         // ★ period 为正数
                                      sequencer.getAndIncrement());
    RunnableScheduledFuture<Void> t = decorateTask(command, sft);
    sft.outerTask = t;
    delayedExecute(t);  // 提交到队列
    return t;
}

// scheduleWithFixedDelay 的实现
public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                 long initialDelay,
                                                 long delay,
                                                 TimeUnit unit) {
    if (command == null || unit == null) throw new NullPointerException();
    if (delay <= 0L) throw new IllegalArgumentException();
    
    ScheduledFutureTask<Void> sft =
        new ScheduledFutureTask<Void>(command, null,
                                      triggerTime(initialDelay, unit),
                                      -unit.toNanos(delay),         // ★ period 为负数
                                      sequencer.getAndIncrement());
    RunnableScheduledFuture<Void> t = decorateTask(command, sft);
    sft.outerTask = t;
    delayedExecute(t);  // 提交到队列
    return t;
}
```

两段代码唯一的区别在于构造 `ScheduledFutureTask` 时，`period` 参数的符号：`fixedRate` 传正数，`fixedDelay` 传负数。这个正负号会在 `setNextRunTime()` 中被检测，从而走不同的分支。

### 4.3 时间线对比（假设 period/delay = 5秒）

**场景一：任务执行时间（2秒）< period/delay（5秒）**

```
scheduleAtFixedRate(task, 0, 5, SECONDS)
时间线：
0s         5s         10s        15s
|--执行2s--|          |--执行2s--|          |--执行2s--|
  (task1)    空闲3s    (task2)    空闲3s    (task3)

下次时间计算：
task1: time = 0, 完成时间 = 2s, 下次 time = 0 + 5 = 5s  ✓
task2: time = 5, 完成时间 = 7s, 下次 time = 5 + 5 = 10s ✓

scheduleWithFixedDelay(task, 0, 5, SECONDS)  
时间线：
0s         7s         14s
|--执行2s--|          |--执行2s--|          |--执行2s--|
  (task1)   空闲5s     (task2)   空闲5s     (task3)

下次时间计算：
task1: 完成时间 = 2s, 下次 time = 2 + 5 = 7s  ✓
task2: 完成时间 = 9s, 下次 time = 9 + 5 = 14s ✓
```

在任务执行时间小于周期时，两者的差异体现在：fixedRate 的执行间隔是精确的 5 秒（从开始到开始），而 fixedDelay 的执行间隔是 7 秒（任务 2 秒 + 等待 5 秒）。

**场景二：任务执行时间（8秒）> period/delay（5秒）**

这是两者差异最明显的场景：

```
scheduleAtFixedRate(task, 0, 5, SECONDS)
时间线：
0s                   8s                   16s                  24s
|------执行8s--------|------执行8s--------|------执行8s--------|
  (task1)              (task2)              (task3)
       ↑ 5s时到了下次计划时间,    
         但task1还没完成,
         task2在task1完成后立即开始

下次时间计算：
task1: time = 0,  完成时间 = 8s,  下次 time = 0 + 5 = 5s
       → 8s时task1才完成，此时5s已经过了，getDelay()=-3s（已过期）
       → task2 入队后被立即取出执行（无间隔！）
task2: time = 5,  完成时间 = 16s, 下次 time = 5 + 5 = 10s
       → 同理，16s时完成，10s早过了
       → task3 立即执行

★ 关键点：fixedRate 不会并行执行同一任务！
  即使计划时间已过，也是等上一次完成后"紧接着"执行下一次。
  不是多个线程同时执行同一个任务。

scheduleWithFixedDelay(task, 0, 5, SECONDS)
时间线：
0s                   8s              13s                  21s
|------执行8s--------|    空闲5s     |------执行8s--------|
  (task1)                             (task2)

下次时间计算：
task1: 完成时间 = 8s,  下次 time = 8 + 5 = 13s
task2: 完成时间 = 21s, 下次 time = 21 + 5 = 26s

★ fixedDelay 永远保证两次执行之间有 5 秒的间隔
```

### 4.4 为什么 fixedRate 不会并行执行？

有人可能会问：如果 fixedRate 的计划时间已经过了，为什么不会用另一个线程同时执行这个任务？

答案在 `run()` 方法的设计中：任务在执行完毕之后（`runAndReset()` 返回后），才会调用 `setNextRunTime()` + `reExecutePeriodic()` 重新入队。在执行期间，这个任务对象不在队列里，不可能被其他线程拿到。这是一个精心设计的"同一任务互斥执行"的保证。

### 4.5 各自的适用场景

**scheduleAtFixedRate 适合：**

任务本身执行很快（远小于 period），且希望保持恒定的触发频率。典型场景如每秒采集一次系统指标（CPU、内存等）、每分钟记录一次心跳。即使偶尔某次执行慢了，后续会"追赶"回来。

**scheduleWithFixedDelay 适合：**

任务执行时间不确定或可能较长，且不希望任务堆积。典型场景如定期同步数据库（执行时间取决于数据量）、定期拉取外部 API 数据。fixedDelay 保证每次执行之间都有固定的"休息时间"，不会出现连续执行的情况。

---

## 五、DelayedWorkQueue 源码深度分析

### 5.1 为什么不用现成的 DelayQueue？

Java 并发包中有一个现成的 `java.util.concurrent.DelayQueue`，它也是基于优先级队列实现的延迟队列。那为什么 ScheduledThreadPoolExecutor 不直接用它，而是自己实现了一个 `DelayedWorkQueue`？

原因有两个：

**原因一：heapIndex 优化**

`DelayQueue` 内部使用 `PriorityQueue`，PriorityQueue 不会在元素中维护其堆索引。当你要取消（remove）一个任务时，需要先遍历整个堆找到它（`O(n)`），然后再做堆调整（`O(logN)`），总复杂度是 `O(n)`。

`DelayedWorkQueue` 在每个 `ScheduledFutureTask` 中维护了 `heapIndex` 字段，记录该任务在堆数组中的位置。取消任务时直接通过 `heapIndex` 定位到位置（`O(1)`），然后做堆调整（`O(logN)`），总复杂度降低到 `O(logN)`。

在高频取消任务的场景下（比如超时控制：提交一个延迟任务，如果在超时前完成了就取消它），这个优化非常有价值。

**原因二：类型特化**

`DelayedWorkQueue` 只接受 `RunnableScheduledFuture` 类型的元素，在内部可以直接操作 `heapIndex` 等特有字段，避免了泛型带来的类型检查开销。

### 5.2 数据结构

```java
static class DelayedWorkQueue extends AbstractQueue<Runnable>
    implements BlockingQueue<Runnable> {

    // 初始容量
    private static final int INITIAL_CAPACITY = 16;

    // ★ 核心数据结构：数组实现的小顶堆
    // 堆顶（queue[0]）永远是触发时间最早的任务
    private RunnableScheduledFuture<?>[] queue =
        new RunnableScheduledFuture<?>[INITIAL_CAPACITY];

    // 可重入锁，保护堆的所有操作
    private final ReentrantLock lock = new ReentrantLock();

    // 堆中元素数量
    private int size;

    // ★ Leader-Follower 模式中的 leader 线程
    // 只有 leader 线程会精确等待堆顶任务到期，其他线程无限期等待
    private Thread leader;

    // 条件变量，用于线程的等待/唤醒
    private final Condition available = lock.newCondition();
}
```

### 5.3 offer() 方法：入堆

当 `delayedExecute()` 提交一个任务时，最终会调用 `offer()` 方法将任务放入堆：

```java
public boolean offer(Runnable x) {
    if (x == null) throw new NullPointerException();
    RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;
    
    final ReentrantLock lock = this.lock;
    lock.lock();  // 加锁，因为堆是共享数据结构
    try {
        int i = size;
        
        // 如果数组满了，扩容50%
        if (i >= queue.length)
            grow();
        
        size = i + 1;
        
        if (i == 0) {
            // 堆为空，直接放在位置0（堆顶）
            queue[0] = e;
            setIndex(e, 0);  // 设置 heapIndex = 0
        } else {
            // 堆不为空，放在末尾然后上浮调整
            siftUp(i, e);
        }
        
        // ★ 关键判断：如果新插入的任务成了堆顶（最早要执行的）
        if (queue[0] == e) {
            // 清空 leader：因为之前的 leader 可能在等一个更晚的任务，
            // 现在有了更早的任务，需要重新竞争 leader
            leader = null;
            // 唤醒一个等待的线程，让它来处理这个更早的任务
            available.signal();
        }
    } finally {
        lock.unlock();
    }
    return true;  // 无界队列，永远返回 true
}
```

为什么新任务成为堆顶时要 `signal()`？

设想这个场景：堆里原来有一个任务 A（10 秒后触发），leader 线程正在精确等待 10 秒。这时候来了一个新任务 B（2 秒后触发），B 上浮到堆顶。如果不唤醒 leader，leader 还要继续等 10 秒，导致任务 B 被延迟 8 秒执行。所以必须唤醒 leader，让它重新检查堆顶，发现只需等 2 秒。

### 5.4 take() 方法：出堆（最核心）

`take()` 是 Worker 线程获取任务的入口。这个方法有阻塞语义：如果没有到期的任务，线程会一直等待。

```java
public RunnableScheduledFuture<?> take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();  // 可中断地获取锁
    try {
        // ★ 自旋循环，直到成功获取一个到期的任务
        for (;;) {
            // 第一步：看堆顶
            RunnableScheduledFuture<?> first = queue[0];
            
            if (first == null) {
                // 堆为空，没有任何任务
                // 无限期等待，直到有新任务被 offer() 进来并 signal()
                available.await();
            } else {
                // 堆不为空，检查堆顶任务是否到期
                long delay = first.getDelay(NANOSECONDS);
                
                if (delay <= 0L) {
                    // ★ 任务到期了！取出来
                    return finishPoll(first);
                    // finishPoll 会：
                    //   1. 将堆末尾元素移到堆顶
                    //   2. siftDown 下沉调整
                    //   3. 设置取出元素的 heapIndex = -1
                    //   4. size--
                }
                
                // 任务还没到期，需要等待
                // ★ 先释放对 first 的引用（为什么？）
                // 因为线程在等待期间不应该持有任务的引用，
                // 避免阻止 GC 回收已取消的任务
                first = null; // don't retain ref while waiting
                
                // ★★★ Leader-Follower 模式的核心逻辑 ★★★
                if (leader != null) {
                    // 已经有 leader 在等了
                    // 当前线程作为 follower，无限期等待
                    // （直到 leader 取走任务后 signal 唤醒它）
                    available.await();
                } else {
                    // 没有 leader，当前线程自己当 leader
                    Thread thisThread = Thread.currentThread();
                    leader = thisThread;
                    try {
                        // leader 精确等待到堆顶任务的到期时间
                        available.awaitNanos(delay);
                    } finally {
                        // 等待结束后（到期或被唤醒），清除 leader 身份
                        if (leader == thisThread)
                            leader = null;
                    }
                }
                // 无论是 leader 等到了时间，还是 follower 被唤醒，
                // 都回到 for 循环开头，重新检查堆顶
            }
        }
    } finally {
        // ★ 当一个线程成功取出任务后（或被中断），
        // 如果堆里还有任务且没有其他 leader，
        // 唤醒一个等待的线程来当新的 leader
        if (leader == null && queue[0] != null)
            available.signal();
        lock.unlock();
    }
}
```

### 5.5 Leader-Follower 模式详解

Leader-Follower 模式是 `take()` 方法中最精妙的设计，它解决的问题是：**避免惊群效应（Thundering Herd）**。

想象一下，如果没有 Leader-Follower，代码会怎么写？

```java
// 没有 Leader-Follower 的天真实现（有问题的）
for (;;) {
    first = queue[0];
    if (first == null) {
        available.await();
    } else {
        long delay = first.getDelay(NANOSECONDS);
        if (delay <= 0) {
            return finishPoll(first);
        }
        // 所有线程都精确等待 delay 时间
        available.awaitNanos(delay);
    }
}
```

问题在哪里？假设有 10 个 Worker 线程，堆顶任务 5 秒后到期。10 个线程全部执行 `awaitNanos(5秒)`，5 秒后它们几乎同时醒来，然后竞争锁。最终只有一个线程能取到任务，其他 9 个线程白白醒来、竞争锁、发现没有自己的任务、又回去睡觉。这种"无意义的唤醒"就是惊群效应，浪费 CPU 资源。

Leader-Follower 模式的做法：

```
线程1（leader）：精确等待 5 秒          → 醒来取走任务
线程2（follower）：无限期等待            → 不会被无故唤醒
线程3（follower）：无限期等待            → 不会被无故唤醒
...
线程10（follower）：无限期等待           → 不会被无故唤醒

线程1取走任务后，signal() 唤醒线程2
线程2成为新的 leader，精确等待下一个堆顶任务
```

这样，任何时刻只有一个线程在做精确等待，其他线程静静地等着。每次只有一个线程被唤醒，没有惊群。

完整的流程图：

```
┌─── Worker线程调用 take() ───┐
│                              │
│  获取锁                      │
│  ↓                           │
│  堆为空？ ─→ 是 ─→ await()   │
│  ↓ 否                        │
│  堆顶到期？ ─→ 是 ─→ 取走，返回│
│  ↓ 否                        │
│  有leader吗？                │
│  ├── 是 → await()（作为follower无限期等）
│  │                           │
│  └── 否 → 自己当leader       │
│           awaitNanos(delay)  │
│           （精确等到到期）     │
│           ↓                  │
│           回到循环开头        │
│           堆顶到期→取走，返回  │
│                              │
│  finally: signal() 唤醒下一个 │
└──────────────────────────────┘
```

### 5.6 siftUp / siftDown 堆操作

堆是 DelayedWorkQueue 的核心数据结构，`siftUp`（上浮）和 `siftDown`（下沉）是维护堆性质的两个基本操作。

```java
/**
 * 上浮操作：新元素插入到堆末尾后，向上调整到正确位置
 * 
 * 堆性质：父节点 <= 子节点（小顶堆）
 * 父节点索引 = (子节点索引 - 1) / 2
 */
private void siftUp(int k, RunnableScheduledFuture<?> key) {
    while (k > 0) {
        // 找到父节点
        int parent = (k - 1) >>> 1;  // 等价于 (k-1)/2，但用无符号右移避免溢出
        RunnableScheduledFuture<?> e = queue[parent];
        
        // 如果 key >= parent，说明当前位置满足堆性质，停止上浮
        if (key.compareTo(e) >= 0)
            break;
        
        // key < parent，不满足小顶堆性质
        // 把 parent 下移到 k 的位置
        queue[k] = e;
        setIndex(e, k);  // 更新 parent 的 heapIndex
        
        // k 上移到 parent 的位置，继续比较
        k = parent;
    }
    // key 放在最终位置
    queue[k] = key;
    setIndex(key, k);  // 设置 key 的 heapIndex
}

/**
 * 下沉操作：堆顶元素被取走后，将末尾元素放到堆顶，然后向下调整
 * 
 * 左子节点索引 = 2 * 父节点索引 + 1
 * 右子节点索引 = 2 * 父节点索引 + 2
 */
private void siftDown(int k, RunnableScheduledFuture<?> key) {
    int half = size >>> 1;  // 只有索引 < half 的节点才有子节点
    
    while (k < half) {
        // 先假设左子节点更小
        int child = (k << 1) + 1;  // 左子节点索引 = 2k + 1
        RunnableScheduledFuture<?> c = queue[child];
        int right = child + 1;     // 右子节点索引 = 2k + 2
        
        // 如果右子节点存在且比左子节点更小，选右子节点
        if (right < size && c.compareTo(queue[right]) > 0)
            c = queue[child = right];
        
        // 如果 key <= 更小的子节点，满足堆性质，停止下沉
        if (key.compareTo(c) <= 0)
            break;
        
        // key > 子节点，不满足小顶堆性质
        // 把更小的子节点上移到 k 的位置
        queue[k] = c;
        setIndex(c, k);  // 更新子节点的 heapIndex
        
        // k 下移到子节点的位置，继续比较
        k = child;
    }
    // key 放在最终位置
    queue[k] = key;
    setIndex(key, k);  // 设置 key 的 heapIndex
}
```

**举例说明 siftUp：**

假设当前堆状态（按 time 排列）：

```
        [3]           索引0
       /    \
     [5]    [7]       索引1, 2
    /   \
  [10]  [8]           索引3, 4
```

现在插入一个 time=2 的新元素，先放在末尾（索引5）：

```
        [3]           索引0
       /    \
     [5]    [7]       索引1, 2
    /   \   /
  [10]  [8] [2]       索引3, 4, 5
```

siftUp 过程：
- k=5，parent=(5-1)/2=2，queue[2]=[7]，2<7，交换 → [2]和[7]互换位置
- k=2，parent=(2-1)/2=0，queue[0]=[3]，2<3，交换 → [2]和[3]互换位置
- k=0，到堆顶了，停止

最终：

```
        [2]           索引0
       /    \
     [5]    [3]       索引1, 2
    /   \   /
  [10]  [8] [7]       索引3, 4, 5
```

### 5.7 remove() 方法：利用 heapIndex 高效删除

```java
public boolean remove(Object x) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        // ★ 利用 heapIndex 直接定位元素在堆中的位置
        int i = indexOf(x);
        if (i < 0)
            return false;

        // 将要删除的元素的 heapIndex 设为 -1（表示不在堆中了）
        setIndex(queue[i], -1);
        int s = --size;  // 堆大小减1
        
        // 取出堆末尾的元素
        RunnableScheduledFuture<?> replacement = queue[s];
        queue[s] = null;  // 清空末尾位置
        
        if (s != i) {
            // 用末尾元素替换被删除的位置，然后调整堆
            // 先尝试下沉
            siftDown(i, replacement);
            // 如果没动（replacement 比子节点都小），再尝试上浮
            if (queue[i] == replacement)
                siftUp(i, replacement);
        }
        return true;
    } finally {
        lock.unlock();
    }
}

// indexOf 利用 heapIndex 实现 O(1) 定位
private int indexOf(Object x) {
    if (x != null) {
        if (x instanceof ScheduledFutureTask) {
            int i = ((ScheduledFutureTask) x).heapIndex;
            // 验证索引有效且确实指向这个元素
            if (i >= 0 && i < size && queue[i] == x)
                return i;
        } else {
            // 非 ScheduledFutureTask 类型，退化为线性搜索
            for (int i = 0; i < size; i++)
                if (x.equals(queue[i]))
                    return i;
        }
    }
    return -1;
}
```

可以看到，`indexOf()` 对 `ScheduledFutureTask` 做了特殊处理：直接通过 `heapIndex` 字段获取索引，验证后返回，时间复杂度 `O(1)`。加上堆调整的 `O(logN)`，整个 remove 操作是 `O(logN)` 的。

### 5.8 finishPoll()：取走堆顶元素

```java
/**
 * 取走堆顶元素，并用堆末尾元素替代堆顶，然后下沉调整
 */
private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
    int s = --size;          // 堆大小减1
    RunnableScheduledFuture<?> x = queue[s]; // 取出末尾元素
    queue[s] = null;         // 清空末尾
    if (s != 0)
        siftDown(0, x);      // 末尾元素放到堆顶，下沉调整
    setIndex(f, -1);         // 被取走的元素 heapIndex 设为 -1
    return f;                // 返回被取走的堆顶元素
}
```

---

## 六、周期任务的重新入队机制

### 6.1 reExecutePeriodic() 方法

当一个周期性任务执行完一次后，需要重新计算下次执行时间并放回队列。这个逻辑由 `reExecutePeriodic()` 完成：

```java
void reExecutePeriodic(RunnableScheduledFuture<?> task) {
    // 第一步：检查线程池状态是否允许继续调度
    if (canRunInCurrentRunState(task)) {
        // 第二步：将任务重新放入队列
        super.getQueue().add(task);
        
        // 第三步：再次检查状态（防止在 add 期间线程池被 shutdown 了）
        // 如果不允许了，就从队列移除并取消
        if (canRunInCurrentRunState(task) || !remove(task)) {
            // 如果仍然允许运行，或者移除失败（说明已经被取走了），
            // 那就确保有线程来处理这个任务
            ensurePrestart();
        }
    } else {
        // 线程池不允许继续执行周期任务了
        task.cancel(false);
    }
}
```

`ensurePrestart()` 确保线程池中有活跃的 Worker 线程来处理新入队的任务：

```java
void ensurePrestart() {
    int wc = workerCountOf(ctl.get());
    if (wc < corePoolSize)
        addWorker(null, true);  // 如果线程数不够，创建新的核心线程
    else if (wc == 0)
        addWorker(null, false); // 至少保证有一个线程
}
```

### 6.2 为什么在任务执行完之后才重新入队？

这是一个设计选择，核心目的是**避免同一个周期任务被多个线程同时执行**。

我们回顾 `ScheduledFutureTask.run()` 的流程：

```
1. 从队列取出 task       → task 不在队列中了
2. 执行 runAndReset()     → 用户代码在运行
3. setNextRunTime()       → 计算下次时间
4. reExecutePeriodic()    → 重新放回队列
```

在第 2 步执行期间，task 不在队列中，所以不可能有其他线程取到这同一个 task。即使有多个 Worker 线程，它们取到的一定是不同的 task。

如果改成"先入队再执行"的设计，就有可能出现：task 还在执行，但新的 time 已经到了，另一个线程把同一个 task 取出来又执行了一次，导致并发问题。

### 6.3 shutdown 之后周期任务的处理

ScheduledThreadPoolExecutor 提供了两个参数来控制 shutdown 后的行为：

```java
// 默认 true：shutdown 后继续执行已提交的延迟（一次性）任务
private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

// 默认 false：shutdown 后不继续执行已提交的周期任务
private volatile boolean continueExistingPeriodicTasksAfterShutdown = false;
```

这意味着默认情况下，当你调用 `executor.shutdown()` 后：

- 已提交的一次性延迟任务：**会继续执行**
- 已提交的周期任务：**不会继续执行**，下次轮到它时 `canRunInCurrentRunState()` 返回 false，任务被取消

你可以通过 setter 方法修改这些行为：

```java
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4);

// 设置 shutdown 后继续执行周期任务
executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);

// 设置 shutdown 后不执行延迟任务
executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
```

`canRunInCurrentRunState()` 的源码：

```java
boolean canRunInCurrentRunState(RunnableScheduledFuture<?> task) {
    if (!isShutdown())
        return true;  // 线程池还没 shutdown，当然允许
    if (isStopped())
        return false; // 线程池已经 stop（shutdownNow），不允许
    // 线程池处于 SHUTDOWN 状态，根据任务类型和配置决定
    return task.isPeriodic()
        ? continueExistingPeriodicTasksAfterShutdown
        : (executeExistingDelayedTasksAfterShutdown
           || task.isZero());  // isZero: delay=0 的任务特殊处理
}
```

---

## 七、任务取消机制

### 7.1 cancel() 方法

当你拿到一个 `ScheduledFuture<?>` 后，可以调用 `cancel()` 来取消它：

```java
ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, 0, 5, SECONDS);

// 取消任务
// mayInterruptIfRunning = true：如果任务正在执行，尝试中断执行线程
// mayInterruptIfRunning = false：如果任务正在执行，让它执行完这一次
future.cancel(false);
```

`ScheduledFutureTask.cancel()` 的源码：

```java
public boolean cancel(boolean mayInterruptIfRunning) {
    // 第一步：调用父类 FutureTask.cancel()
    // 这会将 FutureTask 的状态从 NEW 改为 CANCELLED 或 INTERRUPTING
    boolean cancelled = super.cancel(mayInterruptIfRunning);
    
    // 第二步：如果取消成功 且 配置了 removeOnCancel
    // 则从队列中物理删除这个任务
    if (cancelled && removeOnCancel && heapIndex >= 0)
        remove(this);
    
    return cancelled;
}
```

### 7.2 removeOnCancel 参数

`removeOnCancel` 是 ScheduledThreadPoolExecutor 的一个属性，默认为 `false`：

```java
// 默认 false：取消任务时不从队列中物理删除
private volatile boolean removeOnCancel;

// 设置方法
public void setRemoveOnCancelPolicy(boolean value) {
    removeOnCancel = value;
}
```

**当 removeOnCancel = false（默认）时：**

取消的任务仍然留在堆中。当它"到期"后被 `take()` 取出，Worker 线程会执行 `ScheduledFutureTask.run()`，`run()` 中 `canRunInCurrentRunState()` 会检查任务是否已取消，如果已取消就直接跳过。

这种方式的优点是取消操作非常快（`O(1)`），只需修改状态。缺点是如果大量任务被取消但没有被清理，堆会变得很大，浪费内存。

**当 removeOnCancel = true 时：**

取消时会调用 `remove(this)` 从堆中物理删除任务（`O(logN)`）。优点是堆不会膨胀，缺点是每次取消都要做堆调整。

在大量使用 `schedule()` + `cancel()` 的场景下（比如超时控制），建议开启 `removeOnCancel`：

```java
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4);
executor.setRemoveOnCancelPolicy(true);
```

### 7.3 如果不删除，任务在出队时怎么处理

让我们跟踪一下不删除的情况下，一个已取消的周期任务的命运：

```
1. 用户调用 future.cancel(false)
   → FutureTask 状态变为 CANCELLED
   → removeOnCancel = false，不从队列删除

2. 到达触发时间，Worker 线程通过 take() 取出这个任务

3. Worker 线程调用 ScheduledFutureTask.run()

4. run() 第一步：canRunInCurrentRunState(periodic)
   → 内部检查 FutureTask.isCancelled() → true
   → 方法返回（什么都不做）
   → 不会调用 runAndReset()
   → 不会调用 reExecutePeriodic()（不会重新入队）
   
5. 任务结束，不会再出现在队列中
```

---

## 八、与 Timer 的全面对比

| 维度 | Timer | ScheduledThreadPoolExecutor |
|:---|:---|:---|
| **线程模型** | 单个 TimerThread，所有任务串行执行 | 可配置的线程池，多任务并行执行 |
| **异常处理** | 一个 TimerTask 抛异常，整个 Timer 崩溃，所有后续任务永远不执行 | 一个任务抛异常只影响该任务自身（周期任务会停止调度，但不影响其他任务） |
| **时间基准** | `System.currentTimeMillis()`（墙上时钟，受 NTP/手动修改影响） | `System.nanoTime()`（单调时钟，不受系统时间修改影响） |
| **任务取消** | `TimerTask.cancel()` 只是标记，不从队列删除，且调用时机有限制 | `Future.cancel()` + `removeOnCancel` 策略，可选物理删除 |
| **任务类型** | 只支持 Runnable（TimerTask） | 支持 Runnable 和 Callable（可获取返回值） |
| **返回值** | 无，`schedule()` 返回 void | 返回 `ScheduledFuture`，可以获取结果、取消、检查状态 |
| **调度策略** | `schedule()`（fixedDelay）和 `scheduleAtFixedRate()` | 同样支持，且语义更清晰 |
| **关闭策略** | `cancel()` 后不能再使用 | 精细的 shutdown 策略：可配置 shutdown 后是否继续执行延迟/周期任务 |
| **底层数据结构** | `TaskQueue`：基于数组的小顶堆 | `DelayedWorkQueue`：优化的小顶堆（heapIndex 支持高效取消） |
| **并发安全** | 内部有 synchronized，但单线程执行 | 内部使用 ReentrantLock + Condition，多线程安全 |
| **内存泄漏风险** | TimerTask 持有外部引用可能导致泄漏 | 有 `removeOnCancel` + `purge()` 方法清理 |

结论非常明确：在任何需要定时/周期执行任务的场景中，都应该使用 `ScheduledThreadPoolExecutor`，而不是 `Timer`。Timer 在 JDK 5 之后基本就是历史遗留了。

---

## 九、实战场景与最佳实践

### 9.1 定时心跳 / 健康检查

```java
ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);

// 使用 fixedDelay：确保每次心跳之间有固定间隔
// 如果心跳发送网络延迟大，不会导致心跳堆积
scheduler.scheduleWithFixedDelay(() -> {
    try {
        sendHeartbeat();
    } catch (Exception e) {
        // ★ 必须 catch 所有异常！否则后续心跳不再发送
        log.error("心跳发送失败", e);
    }
}, 0, 30, TimeUnit.SECONDS);
```

### 9.2 缓存定时刷新

```java
ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);

// 使用 fixedRate：希望每隔固定时间就刷新一次，保持数据新鲜度
scheduler.scheduleAtFixedRate(() -> {
    try {
        refreshCache();
    } catch (Exception e) {
        log.error("缓存刷新失败", e);
    }
}, 0, 5, TimeUnit.MINUTES);
```

### 9.3 超时控制

```java
ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
// ★ 超时场景会频繁 schedule + cancel，建议开启 removeOnCancel
scheduler.setRemoveOnCancelPolicy(true);

public void doWithTimeout(Runnable action, long timeout, TimeUnit unit) {
    // 提交一个延迟任务：超时后中断执行线程
    Thread currentThread = Thread.currentThread();
    ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
        currentThread.interrupt();
    }, timeout, unit);
    
    try {
        action.run();
    } finally {
        // 执行完毕，取消超时任务
        timeoutFuture.cancel(false);
    }
}
```

### 9.4 创建方式的选择

**方式一：`Executors.newScheduledThreadPool()`**

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
```

优点：简单。缺点：无法配置拒绝策略、线程工厂等参数。而且阿里巴巴编码规范明确推荐直接使用 `ThreadPoolExecutor`（或其子类），因为 `Executors` 工厂方法隐藏了重要参数。

**方式二：直接 new（推荐）**

```java
ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
    4,                                    // 核心线程数
    new ThreadFactory() {                 // 自定义线程工厂
        private AtomicInteger count = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "scheduler-" + count.incrementAndGet());
            t.setDaemon(true);  // 设为守护线程，不阻止 JVM 退出
            return t;
        }
    },
    new ThreadPoolExecutor.AbortPolicy()  // 拒绝策略
);

// 配置取消策略
scheduler.setRemoveOnCancelPolicy(true);
```

### 9.5 最重要的注意事项：任务内必须 catch 异常

这是使用 ScheduledThreadPoolExecutor 最容易踩的坑。前面分析 `run()` 方法时提到，周期性任务是通过 `runAndReset()` 执行的，如果 `runAndReset()` 返回 false（任务抛了异常），就不会调用 `reExecutePeriodic()` 重新入队，这意味着**整个周期调度静悄悄地终止了，没有任何提示**。

错误示例：

```java
scheduler.scheduleAtFixedRate(() -> {
    // 如果 processData() 抛出 RuntimeException，
    // 整个调度静悄悄地终止，没有日志、没有提示
    processData();
}, 0, 10, TimeUnit.SECONDS);
```

正确示例：

```java
scheduler.scheduleAtFixedRate(() -> {
    try {
        processData();
    } catch (Throwable t) {
        // ★★★ 捕获所有异常（包括 Error），保证调度不中断 ★★★
        log.error("定时任务执行异常", t);
        // 根据业务需求决定：
        // - 打日志后继续（上面的做法）
        // - 或者重新抛出以终止调度：throw t;
    }
}, 0, 10, TimeUnit.SECONDS);
```

为什么用 `Throwable` 而不是 `Exception`？因为有些错误是 `Error`（比如 `OutOfMemoryError`），用 `Exception` 捕获不到。当然，对于 OOM 这种严重错误，即使捕获了也未必能正常继续，但至少能留下日志方便排查。

### 9.6 优雅关闭

```java
// 第一步：调用 shutdown()，不再接受新任务
scheduler.shutdown();

try {
    // 第二步：等待已提交的任务完成（最多等60秒）
    if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
        // 等了60秒还没结束，强制关闭
        scheduler.shutdownNow();
        // 再等15秒
        if (!scheduler.awaitTermination(15, TimeUnit.SECONDS)) {
            log.error("线程池未能正常关闭");
        }
    }
} catch (InterruptedException e) {
    // 当前线程被中断，强制关闭
    scheduler.shutdownNow();
    Thread.currentThread().interrupt();
}
```

---

## 十、常见面试问题

### Q1：ScheduledThreadPoolExecutor 和 Timer 的区别？

**参考回答**：

ScheduledThreadPoolExecutor 和 Timer 都是用于定时任务调度的工具，但 ScheduledThreadPoolExecutor 在线程模型、异常处理、时间基准三个方面全面优于 Timer。

线程模型方面，Timer 内部只有一个 TimerThread，所有 TimerTask 串行执行。一旦某个任务执行时间过长，后面所有任务的调度精度都受影响。ScheduledThreadPoolExecutor 基于线程池，可以配置多个核心线程并行执行任务。

异常处理方面，Timer 的 TimerThread 中没有对单个任务做 try-catch，一个 TimerTask 抛出未捕获异常会导致 TimerThread 死亡，所有后续任务永远不会执行。ScheduledThreadPoolExecutor 的 Worker 线程会处理任务异常（通过 FutureTask 的机制），一个任务的异常不影响其他任务。

时间基准方面，Timer 使用 `System.currentTimeMillis()`，如果系统时间被修改（NTP 同步、手动调整），任务调度会出现混乱。ScheduledThreadPoolExecutor 使用 `System.nanoTime()`，单调递增，不受系统时间影响。

此外，ScheduledThreadPoolExecutor 还提供了更丰富的 API：返回 ScheduledFuture 可获取结果和取消任务、支持 Callable、可配置 shutdown 策略等。

### Q2：scheduleAtFixedRate 和 scheduleWithFixedDelay 的区别？

**参考回答**：

两者的核心区别在于"下次执行时间"的计算方式。

`scheduleAtFixedRate` 是"固定频率"：下次执行时间 = 本次计划开始时间 + period。它关注的是"间隔多久触发一次"，不管任务实际执行了多长时间。如果任务执行时间超过了 period，不会并行执行同一任务，而是上一次完成后立即开始下一次（顺序补偿，无间隔）。

`scheduleWithFixedDelay` 是"固定延迟"：下次执行时间 = 本次实际完成时间 + delay。它关注的是"两次执行之间休息多久"。无论任务执行多长时间，两次执行之间一定有一个固定的间隔。

从源码角度看，两者使用同一个 `ScheduledFutureTask`，区别仅在于 `period` 字段的符号：fixedRate 为正数，fixedDelay 为负数。`setNextRunTime()` 方法根据符号走不同的计算分支。

选择建议：如果任务执行很快且需要稳定的触发频率（如指标采集），用 fixedRate；如果任务执行时间不确定且不希望堆积（如数据同步），用 fixedDelay。

### Q3：DelayedWorkQueue 的底层数据结构是什么？为什么用堆？

**参考回答**：

DelayedWorkQueue 的底层数据结构是一个基于数组实现的小顶堆（最小堆）。堆的排序规则由 `ScheduledFutureTask.compareTo()` 决定：先按触发时间排序，时间相同则按提交顺序（sequenceNumber）排序。

使用堆的原因是它完美匹配了定时任务调度的需求。调度器的核心操作是"取出最早要执行的任务"，这恰好是堆的最高效操作——获取堆顶元素只需 `O(1)`。插入新任务和删除堆顶的复杂度都是 `O(logN)`，这在任务数量较大时也能保持高效。

相比有序链表（插入 `O(N)`）或未排序的列表（查找最小值 `O(N)`），堆在插入和删除操作上达到了很好的平衡。

ScheduledThreadPoolExecutor 没有复用 JDK 已有的 `DelayQueue`（内部也是堆），而是自己实现了 `DelayedWorkQueue`，主要原因是增加了 `heapIndex` 优化：每个 `ScheduledFutureTask` 记录自己在堆数组中的位置，取消任务时可以 `O(1)` 定位 + `O(logN)` 调整，而不用 `O(N)` 遍历查找。

### Q4：如果任务执行时间超过了 period 会怎样？

**参考回答**：

对于 `scheduleAtFixedRate`，如果任务执行时间超过了 period，任务不会被并行执行。因为同一个 ScheduledFutureTask 在执行期间不在队列中（在 `run()` 的 `runAndReset()` 执行完之后才调用 `reExecutePeriodic()` 重新入队），不可能被其他线程取走。

当任务完成后，`setNextRunTime()` 计算的下次时间是 `time + period`，这个时间可能已经过去了（比如当前是第 8 秒，计划是第 5 秒）。任务重新入队后，`getDelay()` 返回负数（表示已过期），Worker 线程在 `take()` 时发现 `delay <= 0`，会立即取出并执行。效果就是"补偿执行"——上一次执行完后没有间隔，紧接着就开始下一次。但始终保持串行，不会并行。

对于 `scheduleWithFixedDelay`，这个问题不存在。因为 fixedDelay 的下次时间是从当前时间 `now()` 开始加上 delay，所以无论任务执行多久，完成后一定会等待一个完整的 delay 时间再执行下一次。

一个延伸问题：如果 fixedRate 的任务持续超时（每次执行时间都超过 period），会不会产生越来越多的"积压"？答案是不会有真正的积压，因为同一任务始终只有一份在执行。只不过每次完成后都会立即开始下一次，没有间隔，看起来像是在"一直跑"。计算出的 time 会不断累加 period，但 `getDelay()` 一直返回负数，所以每次入队后都能被立即取走。

---

## 附录：完整的任务生命周期总结

```
用户调用 scheduleAtFixedRate / scheduleWithFixedDelay
    │
    ▼
创建 ScheduledFutureTask
    │  - time = now + initialDelay
    │  - period = ±周期（正=fixedRate，负=fixedDelay）
    │  - sequenceNumber = 全局自增序号
    │
    ▼
delayedExecute(task)
    │  - 如果线程池正常：将 task 加入 DelayedWorkQueue
    │  - 如果线程池已关闭：执行拒绝策略
    │
    ▼
DelayedWorkQueue.offer(task)
    │  - siftUp 上浮，维护小顶堆
    │  - 如果 task 成为堆顶，唤醒 leader 线程
    │
    ▼
Worker 线程调用 DelayedWorkQueue.take()
    │  - Leader-Follower 模式等待堆顶任务到期
    │  - 到期后 finishPoll()，siftDown 调整堆
    │
    ▼
Worker 线程调用 ScheduledFutureTask.run()
    │
    ├── 一次性任务（period == 0）
    │       │
    │       ▼
    │     super.run()
    │       │  - 执行用户 Callable/Runnable
    │       │  - 设置 FutureTask 结果
    │       ▼
    │     结束（不再入队）
    │
    └── 周期性任务（period != 0）
            │
            ▼
          super.runAndReset()
            │  - 执行用户代码
            │  - 保持 FutureTask 状态为 NEW
            │
            ├── 返回 true（执行成功，无异常）
            │       │
            │       ▼
            │     setNextRunTime()
            │       │  - fixedRate:  time = time + period
            │       │  - fixedDelay: time = now() + |period|
            │       │
            │       ▼
            │     reExecutePeriodic(task)
            │       │  - 将 task 放回 DelayedWorkQueue
            │       │  - 确保有 Worker 线程活着
            │       │
            │       ▼
            │     回到 "Worker 线程调用 take()"
            │
            └── 返回 false（执行时抛了异常）
                    │
                    ▼
                  不入队，周期调度终止（静默失败）
```

---

> 写在最后：ScheduledThreadPoolExecutor 的源码设计非常精巧。它通过继承 ThreadPoolExecutor 复用了线程池的所有基础设施，通过自定义 DelayedWorkQueue 和 ScheduledFutureTask 实现了定时调度能力，通过 Leader-Follower 模式优化了线程等待效率，通过 heapIndex 优化了任务取消性能。理解这些设计思想，对于深入学习 Java 并发编程和系统设计都非常有帮助。
