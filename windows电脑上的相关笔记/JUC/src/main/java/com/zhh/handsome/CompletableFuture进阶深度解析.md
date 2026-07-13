# CompletableFuture 进阶深度解析

> **面向读者**：已了解 CompletableFuture 基本用法，希望深入理解底层原理与生产实践的开发者
> **JDK 版本**：基于 JDK 21 源码分析，兼顾 Java 8/9/17 差异
> **核心观点**：CompletableFuture 是 Java 异步编程的基石，但它的线程池规则、异常传播、底层栈结构充满陷阱，不理解源码就容易踩坑

---

## 目录

- [1. 线程池选择的深层问题](#1-线程池选择的深层问题)
- [2. 超时控制机制](#2-超时控制机制)
- [3. 异常处理完整体系](#3-异常处理完整体系)
- [4. 组合模式进阶](#4-组合模式进阶)
- [5. thenCompose vs thenApply 深层区别](#5-thencompose-vs-thenapply-深层区别)
- [6. 底层实现原理](#6-底层实现原理)
- [7. 与 RxJava/Reactor/Kotlin 协程的对比](#7-与-rxjavareactorkotlin-协程的对比)
- [8. 生产环境最佳实践](#8-生产环境最佳实践)
- [9. 实战代码模板](#9-实战代码模板)
- [10. 常见面试问题](#10-常见面试问题)

---

## 1. 线程池选择的深层问题

### 1.1 commonPool 的陷阱

当你使用 `thenApplyAsync`、`thenComposeAsync` 等带 Async 后缀的方法且**不指定线程池**时，CompletableFuture 默认使用 `ForkJoinPool.commonPool()`。

```java
// JDK 源码 - CompletableFuture.java
// 默认的异步线程池，直接使用 commonPool
private static final Executor ASYNC_POOL = USE_COMMON_POOL ?
    ForkJoinPool.commonPool() : new ThreadPerTaskExecutor();
```

**commonPool 的三大问题**：

| 问题 | 说明 |
|------|------|
| **线程数固定** | 默认核心线程数 = CPU 核心数 - 1，I/O 密集型任务会严重饥饿 |
| **全局共享** | 同一 JVM 中所有使用默认池的 CF、Parallel Stream 共享同一线程池，互相影响 |
| **无法隔离** | 一个慢任务阻塞线程后，其他不相关的异步任务也无法执行 |

```java
// 典型陷阱：I/O 密集型任务打满 commonPool
public class CommonPoolTrapDemo {
    public static void main(String[] args) {
        // 假设 8 核 CPU，commonPool 只有 7 个线程
        // 启动 10 个 I/O 密集型任务
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            // thenApplyAsync 不指定线程池 -> 使用 commonPool
            futures.add(
                CompletableFuture.supplyAsync(() -> {
                    sleep(5000); // 模拟 I/O 阻塞
                    return "result-" + idx;
                }) // ❌ 没有指定线程池，使用 commonPool
            );
        }
        // 第 8~10 个任务必须等前 7 个完成才能获得线程
        // 如果前 7 个任务都阻塞了，整个池子就死了
    }
}
```

**证明 commonPool 被打满**：

```java
// 监控 commonPool 状态
ForkJoinPool commonPool = ForkJoinPool.commonPool();
System.out.println("并行度: " + commonPool.getParallelism());
System.out.println("活跃线程: " + commonPool.getActiveThreadCount());
System.out.println("队列任务数: " + commonPool.getQueuedTaskCount());
System.out.println("窃取任务数: " + commonPool.getStealCount());
```

### 1.2 thenApply vs thenApplyAsync 线程执行规则源码分析

**核心问题**：`thenApply` 不带 Async，它在哪个线程执行？

**结论**：取决于前一个 CF 是否已经完成！

- 如果前一个 CF **已经完成**：在调用 `thenApply` 的线程（调用线程）中同步执行
- 如果前一个 CF **未完成**：在完成前一个 CF 的线程（完成线程）中执行

让我们看源码：

```java
// JDK 源码 - thenApply
public <U> CompletableFuture<U> thenApply(
    Function<? super T,? extends U> fn) {
    // uniApplyStage 传入 executor 为 null
    return uniApplyStage(null, fn);
}

// JDK 源码 - thenApplyAsync（不指定线程池）
public <U> CompletableFuture<U> thenApplyAsync(
    Function<? super T,? extends U> fn) {
    // uniApplyStage 传入 ASYNC_POOL
    return uniApplyStage(ASYNC_POOL, fn);
}

// JDK 源码 - thenApplyAsync（指定线程池）
public <U> CompletableFuture<U> thenApplyAsync(
    Function<? super T,? extends U> fn,
    Executor executor) {
    // uniApplyStage 传入自定义 executor
    return uniApplyStage(screenExecutor(executor), fn);
}
```

接下来看 `uniApplyStage` 的核心逻辑：

```java
// JDK 源码 - uniApplyStage
private <V> CompletableFuture<V> uniApplyStage(
    Executor e, Function<? super T,? extends V> f) {
    if (f == null) throw new NullPointerException();
    // 创建一个新的 CompletableFuture 作为返回值
    CompletableFuture<V> d = new CompletableFuture<V>();
    if (e != null || !d.uniApply(this, f, null)) {
        // ═══════════════════════════════════════════════
        // 关键分支！
        // 条件1: e != null -> 指定了线程池（Async方法）
        // 条件2: d.uniApply(this, f, null) 返回 false
        //        -> 说明前一个 CF (this) 还没完成，无法同步执行
        // 两种情况都需要创建 Completion 对象入栈等待
        // ═══════════════════════════════════════════════
        UniApply<V> c = new UniApply<V>(e, d, this, f);
        push(c); // 压入 Treiber Stack
        c.tryFire(SYNC); // 尝试同步触发一次
    }
    return d;
}
```

再看 `uniApply` 方法——这是同步执行的核心：

```java
// JDK 源码 - uniApply（核心同步执行方法）
final <S> boolean uniApply(CompletableFuture<S> a,
    Function<? super S,? extends T> f, UniApply<S> c) {
    Object r;
    Throwable x;
    // ── 条件1: 前一个 CF 还没有结果，直接返回 false ──
    if (a == null || (r = a.result) == null)
        return false;
    // ── 条件2: 前一个 CF 已经有结果了 ──
    // 尝试为当前 CF 设置结果（CAS）
    if (result == null) {
        try {
            if (c != null && !c.claim())
                return false; // 被线程池 claim 走了，返回 false
            // ── 在当前线程同步执行 fn ──
            // 如果是 thenApply，这里就是在调用线程或完成线程执行
            @SuppressWarnings("unchecked")
            S s = (S) r; // 获取前一个 CF 的结果
            // 如果前一个 CF 正常完成，执行 fn
            if (f != null && s instanceof AltResult) {
                // AltResult 表示前一个 CF 异常完成或结果为 null
                if ((x = ((AltResult)s).ex) != null) {
                    // 前一个 CF 异常完成，直接传播异常
                    completeThrowable(x, s);
                } else {
                    // 前一个 CF 结果为 null，传入 null 给 fn
                    completeValue(f.apply(null));
                }
            } else {
                // 正常结果，执行 fn.apply(s)
                completeValue(f.apply(s));
            }
        } catch (Throwable ex) {
            completeThrowable(ex); // fn 执行异常，捕获
        }
    }
    return true;
}
```

**线程执行规则总结图**：

```
thenApply（不带 Async）:
┌─────────────────────────────────────────────────────┐
│  前一个 CF 已完成？                                   │
│     ├── 是 → 在【调用线程】同步执行 fn                  │
│     └── 否 → fn 被压栈，前一个 CF 完成后               │
│              在【完成前一个 CF 的线程】同步执行 fn        │
└─────────────────────────────────────────────────────┘

thenApplyAsync（带 Async）:
┌─────────────────────────────────────────────────────┐
│  不管前一个 CF 是否完成，fn 都在【指定线程池】中执行      │
│  不指定线程池 → commonPool                            │
│  指定线程池   → 自定义线程池                            │
└─────────────────────────────────────────────────────┘
```

**一个精妙的验证示例**：

```java
public class ThreadExecutionRuleDemo {
    public static void main(String[] args) {
        // 场景1：CF 已经完成，thenApply 在调用线程执行
        CompletableFuture<String> cf1 = CompletableFuture.completedFuture("done");
        cf1.thenApply(s -> {
            System.out.println("场景1 - thenApply 线程: " +
                Thread.currentThread().getName());
            // 输出: main — 在主线程（调用线程）同步执行
            return s + "-transformed";
        });

        // 场景2：CF 未完成，thenApply 在完成线程执行
        CompletableFuture<String> cf2 = new CompletableFuture<>();
        cf2.thenApply(s -> {
            System.out.println("场景2 - thenApply 线程: " +
                Thread.currentThread().getName());
            // 输出: forkjoinpool-worker — 在完成 cf2 的线程执行
            return s + "-transformed";
        });
        // 另一个线程完成 cf2
        new Thread(() -> cf2.complete("done"), "completer-thread").start();

        // 场景3：thenApplyAsync 始终在线程池执行
        CompletableFuture<String> cf3 = CompletableFuture.completedFuture("done");
        cf3.thenApplyAsync(s -> {
            System.out.println("场景3 - thenApplyAsync 线程: " +
                Thread.currentThread().getName());
            // 输出: forkjoinpool-commonPool-worker — 在线程池执行
            return s + "-transformed";
        });
    }
}
```

### 1.3 最佳实践：独立线程池

```java
/**
 * 生产环境推荐：为不同类型的任务使用独立的线程池
 */
public class ThreadPoolBestPractice {

    // CPU 密集型任务线程池 —— 线程数 = CPU 核心数 + 1
    private static final Executor CPU_POOL =
        new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() + 1,
            Runtime.getRuntime().availableProcessors() + 1,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            new ThreadFactoryBuilder().setNameFormat("cpu-pool-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

    // I/O 密集型任务线程池 —— 线程数可以远大于 CPU 核心数
    private static final Executor IO_POOL =
        new ThreadPoolExecutor(
            64,  // 核心线程数
            128, // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2048),
            new ThreadFactoryBuilder().setNameFormat("io-pool-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

    // 使用示例
    public CompletableFuture<String> fetchData(String url) {
        // I/O 任务用 IO_POOL
        return CompletableFuture.supplyAsync(() -> httpClient.get(url), IO_POOL)
            .thenApplyAsync(this::parseResponse, CPU_POOL) // CPU 密集用 CPU_POOL
            .exceptionally(ex -> "fallback");
    }
}
```

---

## 2. 超时控制机制

### 2.1 Java 9+ orTimeout / completeOnTimeout 源码分析

Java 9 引入了两个超时控制方法：

```java
// JDK 9+ 源码 - orTimeout
// 在指定时间后，如果 CF 还没完成，就以 TimeoutException 异常完成
public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
    if (unit == null) throw new NullPointerException();
    if (result == null)
        // 核心实现：使用 ScheduledDelayer 延迟调度超时任务
        whenComplete(new Canceller(
            Delayer.delayedFuture(timeout, unit)));
    return this;
}

// JDK 9+ 源码 - completeOnTimeout
// 在指定时间后，如果 CF 还没完成，就以指定值正常完成
public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
    if (unit == null) throw new NullPointerException();
    if (result == null)
        // 核心实现：使用 ScheduledDelayer 延迟调度超时任务
        whenComplete(new Canceller(
            Delayer.delayedFuture(
                new CompletableFuture<T>().completeAsync(() -> value,
                    DEFAULT_EXECUTOR), // 异步完成
                timeout, unit)));
    return this;
}
```

深入看 `Delayer` 的实现：

```java
// JDK 源码 - Delayer 内部类
// 使用单线程的 ScheduledExecutorService 作为延迟调度器
static final class Delayer {
    // 单线程调度器，全局共享，只负责触发超时，不执行业务逻辑
    static final ScheduledExecutorService delayer =
        new ScheduledThreadPoolExecutor(
            1, // 核心1个线程就够了，只做调度
            new DaemonThreadFactory("CompletableFuture-Delayer"));

    // 创建一个延迟 Future，到时间后自动 complete
    static CompletableFuture<Void> delayedFuture(long delay, TimeUnit unit) {
        CompletableFuture<Void> f = new CompletableFuture<Void>();
        // 延迟 delay 时间后，执行 f.complete(null)
        delayer.schedule(() -> f.complete(null), delay, unit);
        return f;
    }

    // 重载版本：延迟后执行指定的 action
    static <U> CompletableFuture<U> delayedFuture(
        CompletableFuture<U> action, long delay, TimeUnit unit) {
        // 延迟 delay 时间后，执行 action
        delayer.schedule(() -> action.complete(null), delay, unit);
        return action;
    }
}
```

再看 `Canceller` 的实现：

```java
// JDK 源码 - Canceller 内部类
// 当超时触发时，取消原始 CF（以 TimeoutException 异常完成）
static final class Canceller implements BiConsumer<Object, Throwable> {
    final Future<?> f;
    Canceller(Future<?> f) { this.f = f; }
    public void accept(Object ignore, Throwable ex) {
        if (f != null && !f.isDone())
            f.cancel(false); // 取消关联的 Future
    }
}
```

**orTimeout 的实际执行流程**：

```
1. 调用 orTimeout(3, SECONDS)
2. 创建一个延迟 3 秒的 CompletableFuture (delayedFuture)
3. 将 Canceller 注册到原始 CF 和延迟 CF 的 whenComplete
4. 场景A: 原始 CF 在 3 秒内完成
   → whenComplete 触发 Canceller
   → Canceller 取消延迟 CF → 超时不生效
5. 场景B: 原始 CF 在 3 秒内未完成
   → 延迟 CF 到期 complete
   → Canceller 对原始 CF 调用 cancel(false)
   → cancel 内部调用 completeThrowable(new TimeoutException())
   → 原始 CF 以 TimeoutException 异常完成
```

看 `cancel` 方法的源码：

```java
// JDK 源码 - cancel
public boolean cancel(boolean mayInterruptIfRunning) {
    boolean cancelled = (result == null) &&
        // 以 CancellationException 异常完成
        internalComplete(new AltResult(new CancellationException()));
    postComplete(); // 触发后置处理
    return cancelled || isCancelled();
}
```

**注意**：`orTimeout` 抛出的是 `TimeoutException`，但它被包装在 `CompletionException` 中！

```java
// orTimeout 的异常捕获
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
    sleep(10000); // 模拟长时间操作
    return "done";
}).orTimeout(3, TimeUnit.SECONDS)
  .exceptionally(ex -> {
      // ex 是 CompletionException
      // ex.getCause() 是 TimeoutException
      if (ex.getCause() instanceof TimeoutException) {
          System.out.println("超时了！");
      }
      return "timeout-fallback";
  });
```

### 2.2 Java 8 手动超时方案

在 Java 8 中，需要手动实现超时控制：

```java
/**
 * Java 8 手动超时控制方案
 */
public class ManualTimeout {

    /**
     * 方案1：ScheduledFuture + complete
     */
    public static <T> CompletableFuture<T> withTimeout(
        CompletableFuture<T> cf, long timeout, TimeUnit unit) {

        // 创建单线程调度器（实际使用时应共享）
        ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

        // 延迟 timeout 后，尝试以 TimeoutException 完成 cf
        ScheduledFuture<?> timeoutFuture = scheduler.schedule(
            () -> {
                // 如果 cf 还没完成，以 TimeoutException 异常完成
                if (!cf.isDone()) {
                    cf.completeExceptionally(new TimeoutException());
                }
            },
            timeout, unit
        );

        // cf 正常完成后，取消超时任务
        cf.whenComplete((result, ex) -> timeoutFuture.cancel(false));

        return cf;
    }

    /**
     * 方案2：竞争模式 — anyOf + 延迟 CF
     */
    public static <T> CompletableFuture<T> withTimeout2(
        CompletableFuture<T> cf, long timeout, TimeUnit unit) {

        // 创建一个延迟完成的 CF
        CompletableFuture<T> timeoutCf = new CompletableFuture<>();
        ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);
        scheduler.schedule(
            () -> timeoutCf.completeExceptionally(new TimeoutException()),
            timeout, unit
        );

        // anyOf: 谁先完成用谁
        return (CompletableFuture<T>) CompletableFuture.anyOf(cf, timeoutCf)
            .whenComplete((result, ex) -> {
                // 清理：如果另一个 CF 还没完成，取消它
                // 防止资源泄漏
            });
    }

    /**
     * 方案3：Netty 的做法 — 使用 HashedWheelTimer
     * 适用于高并发场景，时间轮比 ScheduledThreadPool 更高效
     */
    // 需要 Netty 依赖，此处省略
}
```

---

## 3. 异常处理完整体系

### 3.1 三个异常处理方法对比

| 方法 | 参数 | 能否感知异常 | 能否修改结果 | 返回值 |
|------|------|------------|------------|--------|
| `exceptionally` | `Function<Throwable, T>` | ✅ | ✅ 降级 | 新 CF |
| `handle` | `BiFunction<T, Throwable, U>` | ✅ | ✅ 降级 | 新 CF |
| `whenComplete` | `BiConsumer<T, Throwable>` | ✅ | ❌ 只读 | 原 CF |

**源码分析**：

```java
// JDK 源码 - exceptionally
public CompletableFuture<T> exceptionally(
    Function<Throwable, ? extends T> fn) {
    return exceptionallyStage(null, fn); // executor 为 null
}

// JDK 源码 - exceptionallyStage
private CompletableFuture<T> exceptionallyStage(
    Executor e, Function<Throwable, ? extends T> fn) {
    if (fn == null) throw new NullPointerException();
    CompletableFuture<T> d = new CompletableFuture<T>();
    // 和 thenApply 类似，先尝试同步执行，失败则入栈
    if (e != null || !d.uniExceptionally(this, fn, null))
        new UniExceptionally<T>(e, d, this, fn).push(this);
    return d;
}

// JDK 源码 - uniExceptionally（核心同步执行方法）
final boolean uniExceptionally(CompletableFuture<T> a,
    Function<Throwable, ? extends T> f, UniExceptionally<T> c) {
    Object r;
    if (a == null || (r = a.result) == null)
        return false; // 前一个 CF 未完成
    // 前一个 CF 已完成
    if (result == null) {
        try {
            if (c != null && !c.claim())
                return false;
            // ═══ 关键：只有前一个 CF 异常时才执行 fn ═══
            Throwable x;
            if (r instanceof AltResult) {
                // AltResult 包装了异常或 null 值
                if ((x = ((AltResult)r).ex) != null) {
                    // 有异常，执行 fn.apply(x) 得到降级值
                    completeValue(f.apply(x));
                } else {
                    // 结果为 null，不是异常，直接传播 null
                    completeValue(null);
                }
            } else {
                // 正常结果（非 AltResult），直接传播
                completeValue((T) r);
            }
        } catch (Throwable ex) {
            completeThrowable(ex);
        }
    }
    return true;
}
```

```java
// JDK 源码 - handle
public <U> CompletableFuture<U> handle(
    BiFunction<? super T, Throwable, ? extends U> fn) {
    return biApplyStage(null, fn);
}

// JDK 源码 - uniHandle（handle 的核心同步执行方法）
final <R,S> boolean uniHandle(CompletableFuture<S> a,
    BiFunction<? super S, Throwable, ? extends R> f,
    UniHandle<S,R> c) {
    Object r;
    if (a == null || (r = a.result) == null)
        return false;
    if (result == null) {
        try {
            if (c != null && !c.claim())
                return false;
            // ═══ 关键：handle 无论正常/异常都会执行 fn ═══
            S s;
            Throwable x;
            if (r instanceof AltResult) {
                x = ((AltResult)r).ex;
                s = null; // 异常时，正常值为 null
            } else {
                x = null; // 正常时，异常为 null
                @SuppressWarnings("unchecked") S ss = (S) r;
                s = ss;
            }
            // fn 接收 (结果, 异常)，两个参数必有一个为 null
            completeValue(f.apply(s, x));
        } catch (Throwable ex) {
            completeThrowable(ex);
        }
    }
    return true;
}
```

```java
// JDK 源码 - whenComplete
public CompletableFuture<T> whenComplete(
    BiConsumer<? super T, ? super Throwable> action) {
    return uniWhenCompleteStage(null, action);
}

// JDK 源码 - uniWhenComplete（核心同步执行方法）
final boolean uniWhenComplete(CompletableFuture<T> a,
    BiConsumer<? super T, ? super Throwable> f,
    UniWhenComplete<T> c) {
    Object r;
    if (a == null || (r = a.result) == null)
        return false;
    if (result == null) {
        try {
            if (c != null && !c.claim())
                return false;
            T t;
            Throwable x = null;
            if (r instanceof AltResult) {
                x = ((AltResult)r).ex;
                t = null;
            } else {
                @SuppressWarnings("unchecked")
                T tt = (T) r;
                t = tt;
            }
            // ═══ 执行 action，但不使用返回值 ═══
            // action 是 Consumer，没有返回值
            f.accept(t, x);
            // ═══ 关键：whenComplete 不改变结果，直接传播 ═══
            if (x == null) {
                completeValue(t); // 正常 → 传播正常值
            } else {
                completeThrowable(x); // 异常 → 传播异常
            }
        } catch (Throwable ex) {
            completeThrowable(ex); // action 本身异常，覆盖原始异常
        }
    }
    return true;
}
```

### 3.2 异常传播规则

**规则1**：异常会沿链式调用向下传播，直到被 `exceptionally`、`handle` 捕获

```java
CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("boom"); // ① 抛出异常
})
.thenApply(s -> s + "2")  // ② 跳过，异常传播
.thenApply(s -> s + "3")  // ③ 跳过，异常传播
.exceptionally(ex -> {    // ④ 捕获异常
    System.out.println(ex -> "recovered") // ② 捕获异常
```

**规则2**：`thenApply`、`thenAccept` 等中间操作遇到上游异常时直接跳过

```java
CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("boom");
})
.thenApply(s -> {
    // ③ 这个步骤被跳过！不会执行！
    System.out.println("我不会被打印");
    return s + "-transformed";
})
.thenAccept(s -> {
    // ④ 这个步骤也被跳过！
    System.out.println("我也不会被打印");
})
.exceptionally(ex -> {
    // ⑤ 直接到这里
    System.out.println("异常: " + ex.getMessage());
    return null;
});
```

**规则3**：`whenComplete` 不吞异常，异常继续向下传播

```java
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
        throw new RuntimeException("boom");
    })
    .whenComplete((result, ex) -> {
        // 能感知异常，但无法吞掉
        System.out.println("异常: " + ex.getMessage());
    });
// cf 的结果仍然是异常！whenComplete 不改变结果
```

### 3.3 CompletionException 包装与解包

**核心问题**：为什么我们捕获的异常经常是 `CompletionException` 而不是原始异常？

```java
// JDK 源码 - completeThrowable
// 当 CF 内部捕获到异常时，会调用此方法
final boolean completeThrowable(Throwable x) {
    // CAS 设置 result
    return internalComplete(new AltResult(
        // ═══ 关键：如果 x 不是 CompletionException，包装一层 ═══
        (x instanceof CompletionException) ? x :
            new CompletionException(x)
    ));
}
```

**异常包装规则**：

```
原始异常类型               存储在 result 中的类型
─────────────────────────────────────────────────
CompletionException        → CompletionException（不重复包装）
CancellationException      → CancellationException（不包装）
其他 RuntimeException      → CompletionException(原始异常)
其他 Exception             → CompletionException(原始异常)
```

**正确解包姿势**：

```java
/**
 * 解包 CompletionException，获取原始异常
 */
public static Throwable unwrapCompletionException(Throwable ex) {
    Throwable cause = ex;
    // 循环解包，防止多层嵌套
    while (cause instanceof CompletionException && cause.getCause() != null) {
        cause = cause.getCause();
    }
    return cause;
}

// 使用示例
cf.exceptionally(ex -> {
    Throwable realCause = unwrapCompletionException(ex);
    if (realCause instanceof TimeoutException) {
        return "timeout-fallback";
    } else if (realCause instanceof BusinessException) {
        return "biz-fallback";
    }
    return "unknown-fallback";
});
```

### 3.4 AltResult 源码分析

```java
// JDK 源码 - AltResult 内部类
/** 用于 null 结果和异常的占位符 */
static final class AltResult {
    // 异常对象（可能为 null，表示结果为 null 的正常完成）
    final Throwable ex;

    AltResult(Throwable x) { this.ex = x; }

    @Override
    public String toString() {
        return ex == null ? "null" : ex.toString();
    }
}
```

**AltResult 的两种用途**：

| 用途 | ex 字段 | 含义 |
|------|---------|------|
| null 结果的占位 | `null` | CF 正常完成，但结果值为 null |
| 异常的包装 | 非 null | CF 异常完成，ex 是异常对象 |

```java
// JDK 源码中 AltResult 的使用
// 当 CF 正常完成且结果为 null 时
final boolean completeValue(T t) {
    return internalComplete(
        // ═══ t == null 时用 AltResult(null) 占位 ═══
        (t == null) ? NIL : t
    );
}

// NIL 是一个静态常量
static final AltResult NIL = new AltResult(null);

// 判断结果是否为异常
// 如果 result instanceof AltResult 且 ex != null → 异常
// 如果 result instanceof AltResult 且 ex == null → null 正常值
// 如果 result 不是 AltResult → 正常有值
```

---

## 4. 组合模式进阶

### 4.1 allOf 结果收集的正确姿势

`CompletableFuture.allOf` 的签名：

```java
// JDK 源码 - allOf
public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
    // 注意返回类型是 CompletableFuture<Void>！
    // 不会返回各个 CF 的结果！
    return andTree(cfs, 0, cfs.length);
}
```

**常见错误**：直接使用 `allOf` 的返回值获取结果

```java
// ❌ 错误示范
CompletableFuture<Void> all = CompletableFuture.allOf(cf1, cf2, cf3);
// all.get() 返回 null，拿不到各 CF 的结果
```

**正确姿势1：配合 thenApply 收集**

```java
// ✅ 正确：利用闭包捕获各 CF 的引用
CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(() -> "hello");
CompletableFuture<String> cf2 = CompletableFuture.supplyAsync(() -> "world");
CompletableFuture<Integer> cf3 = CompletableFuture.supplyAsync(() -> 42);

CompletableFuture<Void> all = CompletableFuture.allOf(cf1, cf2, cf3);

CompletableFuture<Map<String, Object>> result = all.thenApply(v -> {
    // allOf 完成后，所有 CF 都已完成，get() 不会阻塞
    Map<String, Object> map = new HashMap<>();
    map.put("cf1", cf1.join()); // join 此时立即返回
    map.put("cf2", cf2.join());
    map.put("cf3", cf3.join());
    return map;
});
```

**正确姿势2：泛型工具方法**

```java
/**
 * 通用 allOf 结果收集工具
 * 支持任意数量、任意类型的 CF 组合
 */
@SuppressWarnings("unchecked")
public static <T> CompletableFuture<List<T>> allOfToList(
    List<CompletableFuture<T>> futures) {
    // 先转数组
    CompletableFuture<T>[] array = futures.toArray(
        new CompletableFuture[0]
    );
    return CompletableFuture.allOf(array)
        .thenApply(v -> {
            // 所有 CF 完成后，收集结果
            return futures.stream()
                .map(CompletableFuture::join) // 此时 join 不阻塞
                .collect(Collectors.toList());
        });
}

// 使用示例
List<CompletableFuture<String>> futures = Arrays.asList(
    CompletableFuture.supplyAsync(() -> "a"),
    CompletableFuture.supplyAsync(() -> "b"),
    CompletableFuture.supplyAsync(() -> "c")
);
CompletableFuture<List<String>> result = allOfToList(futures);
// result: ["a", "b", "c"]
```

### 4.2 anyOf 的类型安全问题

```java
// JDK 源码 - anyOf
public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
    // 返回类型是 CompletableFuture<Object>！
    // 丢失了泛型信息！
    return orTree(cfs, 0, cfs.length);
}
```

**类型安全的 anyOf 封装**：

```java
/**
 * 类型安全的 anyOf —— 返回第一个成功完成的 CF 的结果
 * 如果所有 CF 都异常完成，则抛出最后一个异常
 */
public static <T> CompletableFuture<T> anyOfSuccess(
    List<CompletableFuture<T>> futures) {

    // 反转语义：所有都失败才失败，任一成功就成功
    CompletableFuture<T> result = new CompletableFuture<>();

    // 异常计数器
    AtomicInteger failureCount = new AtomicInteger(0);

    for (CompletableFuture<T> f : futures) {
        f.whenComplete((value, ex) -> {
            if (ex != null) {
                // 如果所有 CF 都失败了，才以异常完成 result
                if (failureCount.incrementAndGet() == futures.size()) {
                    result.completeExceptionally(ex);
                }
            } else {
                // 任一成功，立即完成 result
                result.complete(value);
            }
        });
    }
    return result;
}
```

### 4.3 批量并行调用 + 容错模式

```java
/**
 * 批量并行调用 + 容错
 * 所有调用都执行，失败的返回默认值，最终汇总
 */
public class BatchParallelInvoker {

    /**
     * 批量调用，单个失败不影响整体
     * @param tasks     任务列表
     * @param fallback  单个任务失败时的降级函数
     * @param executor  线程池
     * @return 所有任务的结果（包括降级结果）
     */
    public static <T> CompletableFuture<List<T>> invokeAllWithFallback(
        List<Supplier<T>> tasks,
        Function<Throwable, T> fallback,
        Executor executor) {

        List<CompletableFuture<T>> futures = tasks.stream()
            .map(task -> CompletableFuture.supplyAsync(task, executor)
                .exceptionally(fallback)) // 每个任务单独容错
            .collect(Collectors.toList());

        return allOfToList(futures);
    }

    /**
     * 批量调用，带超时 + 容错
     */
    public static <T> CompletableFuture<List<T>> invokeAllWithTimeout(
        List<Supplier<T>> tasks,
        long timeout, TimeUnit unit,
        Function<Throwable, T> fallback,
        Executor executor) {

        List<CompletableFuture<T>> futures = tasks.stream()
            .map(task -> CompletableFuture.supplyAsync(task, executor)
                .orTimeout(timeout, unit)           // Java 9+ 超时
                .exceptionally(fallback))            // 超时也降级
            .collect(Collectors.toList());

        return allOfToList(futures);
    }
}

// 使用示例
List<Supplier<String>> apiCalls = Arrays.asList(
    () -> userService.getUserName(userId),    // 可能超时
    () -> orderService.getOrders(userId),     // 可能超时
    () -> creditService.getCredit(userId)     // 可能超时
);

CompletableFuture<List<String>> results =
    BatchParallelInvoker.invokeAllWithTimeout(
        apiCalls,
        3, TimeUnit.SECONDS,
        ex -> "unavailable", // 降级值
        ioPool
    );
```

---

## 5. thenCompose vs thenApply 深层区别

### 5.1 flatMap 类比

```
thenApply  ≈  map：  T → R          （一对一映射，值到值）
thenCompose ≈ flatMap：T → CF<R>    （一对多展平，值到 CF，再展平）

对比 Stream API：
  stream.map(x -> List.of(x))      → Stream<List<Integer>>  // 嵌套了
  stream.flatMap(x -> List.of(x))  → Stream<Integer>         // 展平了

对比 CompletableFuture：
  cf.thenApply(x -> getCf(x))       → CompletableFuture<CompletableFuture<R>>  // 嵌套了
  cf.thenCompose(x -> getCf(x))     → CompletableFuture<R>                     // 展平了
```

### 5.2 源码对比

```java
// ═══════════════════════════════════════════
// thenApply 源码回顾
// ═══════════════════════════════════════════
public <U> CompletableFuture<U> thenApply(
    Function<? super T,? extends U> fn) {
    return uniApplyStage(null, fn);
}

// uniApplyStage 中的关键：直接用 fn 的返回值 completeValue
// fn.apply(s) 返回 R，直接作为新 CF 的 result
// completeValue(f.apply(s))  ← 注意这里

// ═══════════════════════════════════════════
// thenCompose 源码
// ═══════════════════════════════════════════
public <U> CompletableFuture<U> thenCompose(
    Function<? super T, ? extends CompletionStage<U>> fn) {
    return uniComposeStage(null, fn);
}

// JDK 源码 - uniComposeStage
private <V> CompletableFuture<V> uniComposeStage(
    Executor e, Function<? super T, ? extends CompletionStage<V>> f) {
    if (f == null) throw new NullPointerException();
    CompletableFuture<V> d = new CompletableFuture<V>();
    // 先尝试同步执行
    if (e != null || !d.uniCompose(this, f, null)) {
        UniCompose<V> c = new UniCompose<V>(e, d, this, f);
        push(c);
        c.tryFire(SYNC);
    }
    return d;
}

// JDK 源码 - uniCompose（核心同步执行方法）
final <S> boolean uniCompose(CompletableFuture<S> a,
    Function<? super S, ? extends CompletionStage<T>> f,
    UniCompose<S,T> c) {
    Object r;
    Throwable x;
    if (a == null || (r = a.result) == null)
        return false;
    if (result == null) {
        try {
            if (c != null && !c.claim())
                return false;
            @SuppressWarnings("unchecked")
            S s = (S) r;
            if (s instanceof AltResult) {
                if ((x = ((AltResult)s).ex) != null) {
                    completeThrowable(x); // 异常传播
                    return true;
                }
                s = (S) null;
            }
            // ═══ 关键区别在这里 ═══
            // fn.apply(s) 返回的是一个 CompletionStage（即另一个 CF）
            CompletionStage<T> g = f.apply(s);
            // 对返回的 CF 注册回调
            // 当它完成时，将其结果传递给 d（外层 CF）
            if (g != null) {
                CompletableFuture<T> gf = g.toCompletableFuture();
                Object gr = gf.result;
                if (gr != null) {
                    // 返回的 CF 已完成，直接传递结果
                    completeValue(gr);
                } else {
                    // 返回的 CF 未完成，注册回调
                    gf.whenComplete((result, ex) -> {
                        if (ex != null) {
                            d.completeThrowable(ex);
                        } else {
                            d.completeValue(result);
                        }
                    });
                }
            } else {
                // fn 返回 null，以 null 结果完成
                completeValue(null);
            }
        } catch (Throwable ex) {
            completeThrowable(ex);
        }
    }
    return true;
}
```

**核心区别总结**：

```
thenApply:
  fn: T → R
  执行 fn.apply(s) 得到 R
  调用 completeValue(R) 设置结果
  返回的 CF 的 result = R

thenCompose:
  fn: T → CompletionStage<R>
  执行 fn.apply(s) 得到 CompletionStage<R>（即另一个 CF）
  将这个 CF 的结果"展平"到外层 CF
  当内层 CF 完成时，将其 result 传递给外层 CF
  返回的 CF 的 result = R（不是 CompletionStage<R>）
```

**实际使用场景对比**：

```java
// ❌ 错误：thenApply 导致嵌套
CompletableFuture<CompletableFuture<String>> nested =
    getUserId()
        .thenApply(id -> getUserName(id)); // getUserName 返回 CF<String>

// ✅ 正确：thenCompose 展平
CompletableFuture<String> flat =
    getUserId()
        .thenCompose(id -> getUserName(id)); // 直接得到 CF<String>
```

---

## 6. 底层实现原理

### 6.1 result 与 stack 字段

```java
// JDK 源码 - CompletableFuture 类定义
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {

    // ═══ 核心字段1: result ═══
    // 存储计算结果，volatile 保证可见性
    // 可能的值：
    //   null                      → 未完成
    //   AltResult(null)           → 正常完成，结果为 null
    //   AltResult(exception)      → 异常完成
    //   其他对象                   → 正常完成，结果为该对象
    volatile Object result;

    // ═══ 核心字段2: stack ═══
    // 依赖栈，Treiber Stack 的头节点
    // 存储所有依赖当前 CF 完成后执行的 Completion
    volatile Completion stack;

    // ... 其他字段
}
```

### 6.2 Completion 链表（Treiber Stack）

每个 `thenApply`、`thenAccept` 等操作都会创建一个 `Completion` 对象，压入依赖栈。

```java
// JDK 源码 - Completion 抽象类
abstract static class Completion extends ForkJoinTask<Void>
    implements Runnable, AsynchronousCompletionTask {

    // ═══ 链表指针：指向下一个 Completion ═══
    volatile Completion next;

    // 核心方法：尝试触发执行
    abstract CompletableFuture<?> tryFire(int mode);

    // isLive: 是否还在栈中
    abstract boolean isLive();

    // claim: 尝试获取执行权（用于异步执行）
    final boolean claim() {
        Executor e = executor;
        if (e != null) {
            executor = null; // 标记已被 claim
            e.execute(this); // 提交到线程池
            return true;
        }
        return false;
    }
}
```

**Treiber Stack 的 push 操作**：

```java
// JDK 源码 - push（CAS 无锁入栈）
final void push(Completion c) {
    // bilet: 检查是否已取消
    CompletableFuture<?> f = this;
    Completion t = f.stack;
    while (t != null) {
        if (t.isLive()) break; // 栈非空，跳出
        f.stack = t = t.next;  // 清理已完成的节点
    }
    do {
        c.next = t; // 新节点的 next 指向当前栈顶
        // CAS: 将 c 设置为新的栈顶
    } while (!f.casStack(t, c));
    // CAS 失败则重试（Treiber Stack 的标准操作）
}
```

**Treiber Stack 的结构示意**：

```
CF1 (result = null, stack → )
   │
   ▼
  Completion3 (thenApply fn3) → next → Completion2 (thenAccept fn2) → next → Completion1 (thenApply fn1) → next → null
  
  栈顶 = Completion3（最后注册的）
  栈底 = Completion1（最先注册的）
```

### 6.3 completeValue 触发 postComplete 遍历栈

当 CF 完成时（无论是正常完成还是异常完成），会调用 `postComplete` 遍历依赖栈，触发所有依赖的 Completion。

```java
// JDK 源码 - completeValue（正常完成）
final boolean completeValue(T t) {
    // CAS 设置 result
    // t == null 时用 NIL (AltResult) 占位
    return internalComplete((t == null) ? NIL : t);
}

// internalComplete 只是 CAS 设置 result
final boolean internalComplete(Object r) {
    return UNSAFE.compareAndSwapObject(this, RESULT, null, r);
}

// ═══ 关键方法: postComplete ═══
// 在 CAS 成功设置 result 后调用
// 遍历依赖栈，触发所有等待的 Completion
final void postComplete() {
    CompletableFuture<?> f = this;
    Completion h; // 栈顶节点
    // 循环处理栈中的每个 Completion
    while ((h = f.stack) != null ||
           (f != this && (h = f.stack) != null)) {
        // CAS 弹出栈顶
        if (f.casStack(h, h.next)) {
            if (f != this) {
                // 如果 f 不是当前 CF，将弹出的 Completion 压入当前 CF 的栈
                push(h);
            }
            // ═══ 核心：tryFire 触发 Completion 执行 ═══
            h.tryFire(POSTED); // mode = POSTED
            // tryFire 返回后，如果 Completion 产生了新的 CF
            // 新 CF 完成后又会触发 postComplete（递归！）
        }
    }
}
```

**postComplete 的递归特性**：

```
CF1.complete("result")
  → CF1.result = "result"
  → CF1.postComplete()
    → 弹出 Completion1，执行 tryFire(POSTED)
      → Completion1 的 fn 执行完毕
      → 产生 CF2，CF2.result = "transformed"
      → CF2.postComplete()  ← 递归！
        → 弹出 CF2 的依赖 Completion
        → 执行...
    → 弹出 Completion2，执行 tryFire(POSTED)
      → ...
```

**注意**：这种递归在深度链式调用时可能导致栈溢出！

### 6.4 tryFire 执行逻辑

```java
// JDK 源码 - UniApply（thenApply 产生的 Completion）
static final class UniApply<T,V> extends UniCompletion<T,V> {
    Function<? super T,? extends V> fn;

    // mode 参数：
    //   SYNC  = 0  同步模式
    //   ASYNC = 1  异步模式
    //   POSTED = 2 postComplete 触发模式
    @Override
    CompletableFuture<V> tryFire(int mode) {
        CompletableFuture<V> d; // 依赖的 CF（当前操作返回的 CF）
        CompletableFuture<T> a; // 前驱 CF（被依赖的 CF）

        // 如果当前 Completion 已经执行过，或者前驱 CF 未完成，直接返回
        if ((d = dep) == null ||
            !d.uniApply(a = src, fn, mode > 0 ? null : this))
            return null;

        // 执行成功，清理引用
        dep = null; src = null; fn = null;

        // ═══ 核心：如果 d（当前 CF）也完成了，触发它的 postComplete ═══
        return d.postFire(a, mode);
    }
}

// JDK 源码 - postFire
final CompletableFuture<?> postFire(CompletableFuture<?> a, int mode) {
    if (mode == SYNC) {
        // 同步模式：如果栈非空，返回当前 CF，由调用者继续处理
        // 避免过深的递归
        if (stack != null) return this;
    } else if (mode == ASYNC) {
        // 异步模式：直接调用 postComplete
        postComplete();
    }
    // POSTED 模式或无依赖：不处理
    return null;
}
```

**tryFire 的三种模式**：

| 模式 | 值 | 触发场景 | 行为 |
|------|---|---------|------|
| SYNC | 0 | `uniApplyStage` 中首次尝试 | 在当前线程同步执行 |
| ASYNC | 1 | 线程池异步执行 | 在线程池线程中执行 |
| POSTED | 2 | `postComplete` 遍历栈 | 在完成线程中执行 |

### 6.5 完整流程图

```
步骤1: 创建 CF1
  cf1 = CompletableFuture.supplyAsync(() -> "hello", executor)

步骤2: cf1.thenApply(fn1) 创建 CF2
  → 创建 UniApply(fn1) 压入 CF1.stack
  → CF1 未完成，UniApply 等待

步骤3: cf1.thenApply(fn2) 创建 CF3
  → 创建 UniApply(fn2) 压入 CF1.stack
  → CF1.stack: UniApply(fn2) → UniApply(fn1) → null

步骤4: executor 中的线程执行 supplyAsync 的 task
  → task 完成，结果 "hello"
  → 调用 CF1.completeValue("hello")
  → CAS 设置 CF1.result = "hello"
  → 调用 CF1.postComplete()

步骤5: postComplete 遍历栈
  → 弹出 UniApply(fn2)
  → tryFire(POSTED) → uniApply 执行 fn2("hello")
  → CF2.result = fn2("hello")
  → CF2.postComplete() (如果 CF2 有依赖)

  → 弹出 UniApply(fn1)
  → tryFire(POSTED) → uniApply 执行 fn1("hello")
  → CF3.result = fn1("hello")
  → CF3.postComplete()
```

---

## 7. 与 RxJava/Reactor/Kotlin 协程的对比

### 7.1 核心模型对比

| 维度 | CompletableFuture | RxJava | Reactor | Kotlin 协程 |
|------|-------------------|--------|---------|-------------|
| **数据模型** | 单值 (0或1) | 多值流 (0~N) | 多值流 (0~N) | 单值 + Flow |
| **背压支持** | ❌ 无 | ✅ 有 | ✅ 有 | ✅ Flow 有 |
| **取消机制** | 有限 (cancel) | 完善 (Disposable) | 完善 (Disposable) | 完善 (Job.cancel) |
| **操作符丰富度** | 少（十几个） | 丰富（数百个） | 丰富（数百个） | 中等 |
| **线程切换** | 手动指定线程池 | subscribeOn/observeOn | subscribeOn/publishOn | withContext(Dispatcher) |
| **错误处理** | exceptionally/handle | onError/.onErrorResume | onErrorResume | try-catch |
| **重试** | 无内置 | retry() | retry() | 无内置 |
| **超时** | Java 9+ orTimeout | timeout() | timeout() | withTimeout |
| **冷/热** | 热（创建即开始） | 可冷可热 | 可冷可热 | 可冷可热 |
| **学习曲线** | 低 | 高 | 高 | 中 |

### 7.2 代码风格对比

**场景：串行调用三个异步接口，带超时和错误处理**

```java
// ═══ CompletableFuture ═══
CompletableFuture<String> cf = getUserId()
    .thenComposeAsync(id -> getUserName(id), ioPool)
    .thenComposeAsync(name -> getAvatar(name), ioPool)
    .orTimeout(5, TimeUnit.SECONDS)
    .exceptionally(ex -> "default-avatar");

// ═══ RxJava ═══
Single<String> rx = Single.fromCallable(() -> getUserIdSync())
    .subscribeOn(Schedulers.io())
    .flatMap(id -> getUserNameRx(id))
    .flatMap(name -> getAvatarRx(name))
    .timeout(5, TimeUnit.SECONDS)
    .onErrorReturnItem("default-avatar");

// ═══ Reactor ═══
Mono<String> reactor = Mono.fromCallable(() -> getUserIdSync())
    .subscribeOn(Schedulers.boundedElastic())
    .flatMap(id -> getUserNameReactor(id))
    .flatMap(name -> getAvatarReactor(name))
    .timeout(Duration.ofSeconds(5))
    .onErrorReturn("default-avatar");

// ═══ Kotlin 协程 ═══
suspend fun getAvatarWithFallback(): String = coroutineScope {
    try {
        withTimeout(5000) {
            val id = getUserId()
            val name = getUserName(id)
            getAvatar(name)
        }
    } catch (e: Exception) {
        "default-avatar"
    }
}
```

### 7.3 适用场景选择

| 场景 | 推荐 | 原因 |
|------|------|------|
| 简单异步调用 | CompletableFuture | 足够用，JDK 内置 |
| 事件流处理 | RxJava/Reactor | 多值流、背压、丰富操作符 |
| WebFlux 响应式服务 | Reactor | Spring WebFlux 原生支持 |
| Android 开发 | RxJava/Kotlin 协程 | 协程代码最简洁 |
| 微服务编排 | CompletableFuture + 协程 | 简单编排用 CF，复杂流程用协程 |
| 高并发网关 | Reactor | Netty + Reactor 生态 |

### 7.4 CompletableFuture 的局限

```java
// 1. 无法表达多值流
// CF 只能完成一次，无法持续发射数据
// 需要流式场景 → 用 RxJava/Reactor

// 2. 无背压
// CF 无法感知下游消费速度
// 下游处理不过来时无法通知上游减速

// 3. 冷热不分
// CF 创建后立即开始执行（热源）
// 无法做到"有订阅者才开始"（冷源）

// 4. 操作符少
// 没有内置 retry、debounce、throttle、buffer 等
// 需要手动实现

// 5. 取消不彻底
// cancel 只是设置 CancellationException
// 如果底层任务已经在执行，无法真正中断
```

---

## 8. 生产环境最佳实践

### 8.1 必须指定线程池

```java
// ❌ 绝对禁止：使用默认 commonPool
CompletableFuture.supplyAsync(() -> callRemoteApi());

// ✅ 必须指定线程池
CompletableFuture.supplyAsync(() -> callRemoteApi(), bizThreadPool);
```

**为什么**：

1. **隔离性**：不同业务使用不同线程池，避免互相影响
2. **可控性**：自定义线程池可以监控、调参、拒绝策略
3. **避免饥饿**：I/O 密集型任务不应使用 CPU 核心数大小的线程池

```java
/**
 * 生产级线程池配置模板
 */
@Configuration
public class AsyncThreadPoolConfig {

    @Bean("ioPool")
    public Executor ioPool() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cpuCores * 2,                          // 核心线程数
            cpuCores * 4,                          // 最大线程数
            60L, TimeUnit.SECONDS,                 // 空闲线程存活时间
            new LinkedBlockingQueue<>(2000),        // 有界队列
            new CustomNamedThreadFactory("io-pool"),// 自定义线程名
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
    }

    @Bean("cpuPool")
    public Executor cpuPool() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cpuCores + 1,                           // CPU 密集型：核心数+1
            cpuCores + 1,                           // 最大同核心
            0L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new CustomNamedThreadFactory("cpu-pool"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

### 8.2 异常必须处理

```java
// ❌ 危险：异常被静默吞掉
CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("boom");
}, ioPool);
// 没有任何 exception 处理，异常会丢失！

// ✅ 正确：每个 CF 链必须有异常处理
CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("boom");
}, ioPool)
.exceptionally(ex -> {
    log.error("异步任务异常", ex);
    return "fallback";
});

// ✅ 更好：全局异常兜底
public static <T> CompletableFuture<T> safeAsync(
    Supplier<T> supplier, Executor executor) {
    CompletableFuture<T> cf = new CompletableFuture<>();
    executor.execute(() -> {
        try {
            cf.complete(supplier.get());
        } catch (Throwable ex) {
            cf.completeExceptionally(ex);
        }
    });
    return cf;
}
```

### 8.3 避免阻塞

```java
// ❌ 危险：在 CF 的回调中阻塞
CompletableFuture.supplyAsync(() -> getData(), ioPool)
    .thenApply(data -> {
        // 在回调中执行阻塞操作！
        // 如果是 thenApply（非Async），会占用完成线程
        // 如果是 commonPool，可能饿死其他任务
        return blockingDbQuery(data); // ← 阻塞！
    });

// ✅ 正确：使用 Async 方法 + 独立线程池
CompletableFuture.supplyAsync(() -> getData(), ioPool)
    .thenComposeAsync(data ->
        CompletableFuture.supplyAsync(
            () -> blockingDbQuery(data), dbPool), // 使用 db 线程池
        dbPool
    );

// ❌ 危险：get() 无限阻塞
cf.get(); // 可能永远阻塞

// ✅ 正确：带超时的 get
cf.get(5, TimeUnit.SECONDS);

// ✅ 更好：用 join 代替 get（不检查异常），但必须搭配 exceptionally
cf.exceptionally(ex -> "fallback").join();
```

### 8.4 监控

```java
/**
 * CF 监控工具
 * 记录执行时间、成功/失败率
 */
public class CompletableFutureMonitor {

    /**
     * 带监控的 supplyAsync
     */
    public static <T> CompletableFuture<T> monitoredSupply(
        String metricName,
        Supplier<T> supplier,
        Executor executor) {

        long startTime = System.nanoTime();
        CompletableFuture<T> cf = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                T result = supplier.get();
                cf.complete(result);
                // 记录成功指标
                Metrics.recordSuccess(metricName,
                    System.nanoTime() - startTime);
            } catch (Throwable ex) {
                cf.completeExceptionally(ex);
                // 记录失败指标
                Metrics.recordFailure(metricName, ex.getClass().getSimpleName(),
                    System.nanoTime() - startTime);
            }
        });

        return cf;
    }
}
```

### 8.5 CF 链式调用的线程池传递陷阱

```java
// ❌ 陷阱：以为每个阶段都用了 ioPool
CompletableFuture.supplyAsync(() -> fetchData(), ioPool)
    .thenApply(data -> parse(data))     // ← 可能不在 ioPool 执行！
    .thenApply(parsed -> enrich(parsed))// ← 可能不在 ioPool 执行！

// ✅ 如果需要确保每一步都在线程池中执行：
CompletableFuture.supplyAsync(() -> fetchData(), ioPool)
    .thenApplyAsync(data -> parse(data), ioPool)
    .thenApplyAsync(parsed -> enrich(parsed), ioPool)

// ✅ 或者：明确哪些步骤可以同步（快速操作），哪些必须异步（慢操作）
CompletableFuture.supplyAsync(() -> fetchData(), ioPool)    // 异步：I/O
    .thenApply(data -> parse(data))                          // 同步可接受：CPU 快速解析
    .thenComposeAsync(parsed -> enrichAsync(parsed), ioPool) // 异步：远程调用
```

---

## 9. 实战代码模板

### 9.1 并行聚合 + 超时容错

```java
/**
 * 并行调用多个服务，汇总结果，单个服务超时降级
 *
 * 场景：商品详情页需要同时获取商品信息、价格、库存、评论
 */
public class ProductDetailAggregator {

    private final Executor ioPool;
    private final ProductService productService;
    private final PriceService priceService;
    private final InventoryService inventoryService;
    private final ReviewService reviewService;

    public CompletableFuture<ProductDetail> getProductDetail(String productId) {
        // 并行发起4个异步调用，每个都有超时和降级
        CompletableFuture<ProductInfo> productCf =
            CompletableFuture.supplyAsync(
                    () -> productService.getProduct(productId), ioPool)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("获取商品信息失败, productId={}", productId, ex);
                    return ProductInfo.EMPTY; // 降级
                });

        CompletableFuture<PriceInfo> priceCf =
            CompletableFuture.supplyAsync(
                    () -> priceService.getPrice(productId), ioPool)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("获取价格失败, productId={}", productId, ex);
                    return PriceInfo.EMPTY;
                });

        CompletableFuture<InventoryInfo> inventoryCf =
            CompletableFuture.supplyAsync(
                    () -> inventoryService.getInventory(productId), ioPool)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("获取库存失败, productId={}", productId, ex);
                    return InventoryInfo.EMPTY;
                });

        CompletableFuture<ReviewSummary> reviewCf =
            CompletableFuture.supplyAsync(
                    () -> reviewService.getReviewSummary(productId), ioPool)
                .orTimeout(3, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("获取评论失败, productId={}", productId, ex);
                    return ReviewSummary.EMPTY;
                });

        // 聚合所有结果
        return CompletableFuture.allOf(productCf, priceCf, inventoryCf, reviewCf)
            .thenApplyAsync(v -> {
                ProductDetail detail = new ProductDetail();
                detail.setProductInfo(productCf.join());
                detail.setPriceInfo(priceCf.join());
                detail.setInventoryInfo(inventoryCf.join());
                detail.setReviewSummary(reviewCf.join());
                return detail;
            }, ioPool)
            .orTimeout(5, TimeUnit.SECONDS) // 整体超时兜底
            .exceptionally(ex -> {
                log.error("聚合商品详情失败, productId={}", productId, ex);
                return ProductDetail.EMPTY; // 最终降级
            });
    }
}
```

### 9.2 串行依赖链

```java
/**
 * 串行依赖链：每一步依赖前一步的结果
 *
 * 场景：下单流程 — 检查库存 → 锁定库存 → 创建订单 → 扣款 → 确认订单
 */
public class OrderFlow {

    private final Executor ioPool;

    public CompletableFuture<OrderResult> createOrder(OrderRequest request) {
        return CompletableFuture.supplyAsync(
                () -> inventoryService.checkStock(request), ioPool)    // 1. 检查库存
            .thenComposeAsync(stock -> {
                if (!stock.isAvailable()) {
                    // 库存不足，提前返回
                    return CompletableFuture.completedFuture(
                        OrderResult.outOfStock());
                }
                // 2. 锁定库存
                return CompletableFuture.supplyAsync(
                    () -> inventoryService.lockStock(request), ioPool);
            }, ioPool)
            .thenComposeAsync(lockResult -> {
                if (!lockResult.isSuccess()) {
                    return CompletableFuture.completedFuture(
                        OrderResult.lockFailed());
                }
                // 3. 创建订单
                return CompletableFuture.supplyAsync(
                    () -> orderService.create(request), ioPool);
            }, ioPool)
            .thenComposeAsync(order -> {
                // 4. 扣款
                return CompletableFuture.supplyAsync(
                    () -> paymentService.charge(order), ioPool);
            }, ioPool)
            .thenComposeAsync(payment -> {
                if (!payment.isSuccess()) {
                    // 扣款失败，回滚
                    return CompletableFuture.supplyAsync(
                        () -> orderService.cancel(payment.getOrderId()), ioPool)
                        .thenApply(cancelResult ->
                            OrderResult.paymentFailed());
                }
                // 5. 确认订单
                return CompletableFuture.supplyAsync(
                    () -> orderService.confirm(payment.getOrderId()), ioPool);
            }, ioPool)
            .exceptionally(ex -> {
                log.error("创建订单异常", ex);
                return OrderResult.error(ex.getMessage());
            });
    }
}
```

### 9.3 竞速模式

```java
/**
 * 竞速模式：多个数据源竞争，谁先返回用谁
 *
 * 场景：多缓存查询（本地缓存 → 远程缓存 → 数据库，谁先返回用谁）
 *       或多机房调用（机房A、机房B，谁先返回用谁）
 */
public class RacingPattern {

    /**
     * 竞速：多个数据源，第一个成功的获胜
     */
    public <T> CompletableFuture<T> race(
        List<Supplier<CompletableFuture<T>>> suppliers,
        Executor executor) {

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        for (Supplier<CompletableFuture<T>> supplier : suppliers) {
            CompletableFuture<T> cf = supplier.get();
            cf.whenComplete((value, ex) -> {
                if (ex == null) {
                    // 成功，立即完成 result
                    result.complete(value);
                } else {
                    // 失败，计数
                    if (failureCount.incrementAndGet() == suppliers.size()) {
                        // 全部失败，result 以最后一个异常完成
                        result.completeExceptionally(ex);
                    }
                }
            });
        }

        return result;
    }

    /**
     * 竞速：第一个返回的获胜（不管成功还是失败）
     * 等同于 anyOf
     */
    public <T> CompletableFuture<T> raceAny(
        List<Supplier<CompletableFuture<T>>> suppliers) {

        @SuppressWarnings("unchecked")
        CompletableFuture<T>[] futures = suppliers.stream()
            .map(Supplier::get)
            .toArray(CompletableFuture[]::new);

        return (CompletableFuture<T>) CompletableFuture.anyOf(futures);
    }

    // 使用示例：多机房竞速
    public CompletableFuture<UserInfo> getUserInfoMultiDc(String userId) {
        return race(Arrays.asList(
            () -> CompletableFuture.supplyAsync(
                () -> dataCenterA.getUserInfo(userId), ioPool),
            () -> CompletableFuture.supplyAsync(
                () -> dataCenterB.getUserInfo(userId), ioPool),
            () -> CompletableFuture.supplyAsync(
                () -> dataCenterC.getUserInfo(userId), ioPool)
        ), ioPool)
        .orTimeout(3, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            log.error("所有机房查询失败, userId={}", userId, ex);
            return UserInfo.EMPTY;
        });
    }
}
```

### 9.4 带重试的异步调用

```java
/**
 * 带重试的异步调用
 *
 * 支持自定义重试次数、重试间隔、重试条件
 */
public class AsyncRetry {

    /**
     * 异步重试模板
     *
     * @param supplier     要执行的操作
     * @param maxRetries   最大重试次数
     * @param delay        重试间隔
     * @param unit         时间单位
     * @param retryOn      判断是否需要重试的条件
     * @param executor     线程池
     * @param <T>          返回类型
     * @return CompletableFuture<T>
     */
    public static <T> CompletableFuture<T> withRetry(
        Supplier<T> supplier,
        int maxRetries,
        long delay,
        TimeUnit unit,
        Predicate<Throwable> retryOn,
        ScheduledExecutorService scheduler,
        Executor executor) {

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger attempt = new AtomicInteger(0);

        // 递归执行
        Runnable task = new Runnable() {
            @Override
            public void run() {
                CompletableFuture.supplyAsync(supplier, executor)
                    .whenComplete((value, ex) -> {
                        if (ex == null) {
                            // 成功，完成 result
                            result.complete(value);
                        } else {
                            // 失败
                            Throwable realEx = unwrapCompletionException(ex);
                            int currentAttempt = attempt.incrementAndGet();
                            if (currentAttempt < maxRetries && retryOn.test(realEx)) {
                                // 需要重试
                                log.warn("异步操作失败，第{}次重试，异常: {}",
                                    currentAttempt, realEx.getMessage());
                                scheduler.schedule(this, delay, unit);
                            } else {
                                // 不重试，以异常完成
                                result.completeExceptionally(ex);
                            }
                        }
                    });
            }
        };

        task.run(); // 首次执行
        return result;
    }

    // 简化版：固定3次重试，1秒间隔，所有异常都重试
    public static <T> CompletableFuture<T> withRetry(
        Supplier<T> supplier, Executor executor) {

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "retry-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        return withRetry(
            supplier,
            3,               // 最多重试3次
            1, TimeUnit.SECONDS,
            ex -> true,      // 所有异常都重试
            scheduler,
            executor
        ).whenComplete((v, ex) -> scheduler.shutdown());
    }

    /**
     * 指数退避重试
     */
    public static <T> CompletableFuture<T> withExponentialBackoff(
        Supplier<T> supplier,
        int maxRetries,
        long initialDelay,
        TimeUnit unit,
        Executor executor) {

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger attempt = new AtomicInteger(0);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "backoff-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        Runnable task = new Runnable() {
            @Override
            public void run() {
                CompletableFuture.supplyAsync(supplier, executor)
                    .whenComplete((value, ex) -> {
                        if (ex == null) {
                            result.complete(value);
                            scheduler.shutdown();
                        } else {
                            int currentAttempt = attempt.incrementAndGet();
                            if (currentAttempt < maxRetries) {
                                // 指数退避: initialDelay * 2^attempt
                                long backoffDelay =
                                    initialDelay * (1L << currentAttempt);
                                log.warn("异步操作失败，第{}次重试，{}秒后重试",
                                    currentAttempt,
                                    unit.toSeconds(backoffDelay));
                                scheduler.schedule(this, backoffDelay, unit);
                            } else {
                                result.completeExceptionally(ex);
                                scheduler.shutdown();
                            }
                        }
                    });
            }
        };

        task.run();
        return result;
    }

    /**
     * 解包 CompletionException
     */
    private static Throwable unwrapCompletionException(Throwable ex) {
        Throwable cause = ex;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}

// 使用示例
CompletableFuture<String> result = AsyncRetry.withRetry(
    () -> httpClient.get("https://api.example.com/data"),
    3,                          // 最多重试3次
    1, TimeUnit.SECONDS,        // 每次间隔1秒
    ex -> !(ex instanceof IllegalArgumentException), // 非参数异常才重试
    scheduler,
    ioPool
);
```

---

## 10. 常见面试问题

### Q1: thenApply 和 thenApplyAsync 的区别？

**答**：

- `thenApply`：同步执行。如果前一个 CF 已完成，在调用线程执行；如果未完成，在完成前一个 CF 的线程执行
- `thenApplyAsync`：异步执行。不管前一个 CF 是否完成，都在指定线程池（默认 commonPool）中执行

源码层面：`thenApply` 调用 `uniApplyStage(null, fn)`，executor 为 null；`thenApplyAsync` 调用 `uniApplyStage(ASYNC_POOL, fn)`，executor 非 null。在 `uniApplyStage` 中，如果 `e != null`，直接创建 Completion 入栈异步执行；如果 `e == null`，先尝试 `uniApply` 同步执行，失败再入栈。

### Q2: CompletableFuture 的异常如何传播？为什么捕获到的是 CompletionException？

**答**：

异常沿链式调用向下传播，中间的 `thenApply`、`thenAccept` 等操作遇到上游异常会跳过。

原始异常在 `completeThrowable` 方法中被包装为 `CompletionException`（如果原始异常不是 CompletionException 或 CancellationException）。所以我们在 `exceptionally` 中捕获到的通常是 `CompletionException`，需要通过 `getCause()` 获取原始异常。

### Q3: allOf 返回 CompletableFuture<Void>，如何获取所有结果？

**答**：

`allOf` 只保证所有 CF 完成，不收集结果。正确做法是在 `allOf().thenApply()` 中利用闭包引用各 CF，调用 `join()` 获取结果（此时 join 不会阻塞，因为 allOf 已经保证完成）。也可以封装泛型工具方法，将 `List<CompletableFuture<T>>` 转为 `CompletableFuture<List<T>>`。

### Q4: thenCompose 和 thenApply 的区别？

**答**：

`thenApply` 的函数返回 `R`，结果是 `CompletableFuture<R>`——一对一映射。
`thenCompose` 的函数返回 `CompletionStage<R>`，结果是展平的 `CompletableFuture<R>`——类似 flatMap。

源码层面：`thenApply` 中 `fn.apply(s)` 的返回值直接通过 `completeValue` 设置为新 CF 的结果；`thenCompose` 中 `f.apply(s)` 返回一个 CompletionStage，然后对它注册 `whenComplete` 回调，当内层 CF 完成时将结果传递给外层 CF。

### Q5: CompletableFuture 的底层是如何实现依赖链的？

**答**：

CompletableFuture 有两个核心 volatile 字段：`result` 和 `stack`。

- `result`：存储计算结果（null 表示未完成，AltResult 表示异常或 null 值，其他为正常结果）
- `stack`：Treiber Stack 头节点，存储所有依赖当前 CF 的 Completion 对象

每个 `thenApply` 等操作创建一个 Completion 对象，通过 CAS 压入栈中。当 CF 完成时（`completeValue`），触发 `postComplete`，遍历栈中的所有 Completion，调用 `tryFire(POSTED)` 执行。Completion 执行后如果产生了新的 CF 完成，又会递归调用 `postComplete`。

### Q6: 为什么不建议使用默认的 commonPool？

**答**：

1. **线程数固定**：commonPool 的并行度 = CPU 核心数 - 1，对 I/O 密集型任务不够用
2. **全局共享**：同一 JVM 中所有 CF 的 Async 方法和 Parallel Stream 共享 commonPool，互相影响
3. **无法隔离**：一个慢任务阻塞线程后，其他不相关业务也无法执行
4. **无法监控和调优**：commonPool 是全局的，无法针对业务调整参数

### Q7: 如何实现 CompletableFuture 的超时控制？

**答**：

- **Java 9+**：使用 `orTimeout(duration, unit)` 和 `completeOnTimeout(value, duration, unit)`。底层使用 `ScheduledExecutorService` 延迟调度，到时后以 `TimeoutException` 异常完成（orTimeout）或以指定值正常完成（completeOnTimeout）
- **Java 8**：手动方案。使用 `ScheduledExecutorService` 延迟调用 `completeExceptionally`，或使用 `anyOf` 与延迟 CF 竞争

### Q8: exceptionally、handle、whenComplete 的区别？

**答**：

- `exceptionally`：只在异常时执行，可以降级返回默认值
- `handle`：正常和异常都执行，可以修改结果（降级或转换）
- `whenComplete`：正常和异常都执行，只读不修改，异常继续传播

源码层面：`exceptionally` 内部检查 result 是否为 AltResult 且 ex != null；`handle` 不管正常异常都执行 fn；`whenComplete` 执行 action 后根据是否有异常决定 `completeValue` 还是 `completeThrowable`。

### Q9: CompletableFuture 的 cancel 能真正取消底层任务吗？

**答**：

**不能**。`cancel(false)` 只是将 CF 以 `CancellationException` 异常完成，如果底层任务已经在执行，不会被中断。`cancel(true)` 会尝试中断（如果底层是 FutureTask 且支持中断），但大多数情况下底层任务是 Runnable，无法真正中断。

```java
// cancel 源码
public boolean cancel(boolean mayInterruptIfRunning) {
    boolean cancelled = (result == null) &&
        internalComplete(new AltResult(new CancellationException()));
    postComplete();
    return cancelled || isCancelled();
}
// 只是 CAS 设置 result，没有中断逻辑
```

### Q10: 如何避免 CompletableFuture 链式调用中的栈溢出？

**答**：

`postComplete` 是递归调用的，当链式调用非常深时可能栈溢出。解决方案：

1. **使用 Async 方法**：`thenApplyAsync` 等方法会将任务提交到线程池，打破递归
2. **拆分长链**：将过长的链式调用拆分为多段
3. **使用 thenCompose**：将嵌套结构展平，减少链深度

```java
// ❌ 可能栈溢出的深链
cf.thenApply(fn1).thenApply(fn2).thenApply(fn3)...thenApply(fn1000);

// ✅ 使用 Async 方法打断递归
cf.thenApplyAsync(fn1, pool)
  .thenApplyAsync(fn2, pool)
  .thenApplyAsync(fn3, pool);
```

### Q11: 多个 thenApply 注册在同一个 CF 上，它们的执行顺序是什么？

**答**：

按照 **LIFO（后进先出）** 的顺序执行。因为 Completion 对象通过 Treiber Stack 存储，`postComplete` 遍历栈时从栈顶开始。所以最后注册的 `thenApply` 最先执行。

```java
CompletableFuture<String> cf = new CompletableFuture<>();

cf.thenApply(s -> s + "-1"); // 先注册，后执行
cf.thenApply(s -> s + "-2"); // 后注册，先执行
cf.thenApply(s -> s + "-3"); // 最后注册，最先执行

cf.complete("hello");
// 结果: "hello-3" → "hello-3-2" → "hello-3-2-1"
// 注意：这三个 thenApply 分别产生3个独立的 CF
// 执行顺序是 3→2→1，但每个 CF 的结果是独立的
```

### Q12: 为什么 thenRun/thenAccept 等方法在前一个 CF 异常时不会执行？

**答**：

因为在 `uniApply`、`uniAccept` 等方法的源码中，当检测到前一个 CF 的 result 是 `AltResult` 且 `ex != null` 时，会直接调用 `completeThrowable(x)` 传播异常，跳过 fn/acc 的执行。这是设计如此——异常应该传播到 `exceptionally` 或 `handle` 处理，而不是被中间操作吞掉。

---

## 附录：CompletableFuture API 速查表

### 转换操作

| 方法 | 描述 | Async 版本 |
|------|------|-----------|
| `thenApply(fn)` | 同步转换 T→R | `thenApplyAsync(fn, pool)` |
| `thenCompose(fn)` | 异步展平 T→CF<R> | `thenComposeAsync(fn, pool)` |

### 消费操作

| 方法 | 描述 | Async 版本 |
|------|------|-----------|
| `thenAccept(action)` | 消费结果 T→void | `thenAcceptAsync(action, pool)` |
| `thenRun(action)` | 不消费，只执行 | `thenRunAsync(action, pool)` |

### 组合操作

| 方法 | 描述 |
|------|------|
| `thenCombine(other, fn)` | 两个 CF 都完成后合并 |
| `thenCombineAsync(other, fn, pool)` | 异步版本 |
| `thenAcceptBoth(other, action)` | 两个 CF 都完成后消费 |
| `runAfterBoth(other, action)` | 两个 CF 都完成后执行 |
| `applyToEither(other, fn)` | 任一 CF 完成后转换 |
| `acceptEither(other, action)` | 任一 CF 完成后消费 |
| `runAfterEither(other, action)` | 任一 CF 完成后执行 |
| `allOf(cfs...)` | 所有 CF 都完成 |
| `anyOf(cfs...)` | 任一 CF 完成 |

### 异常处理

| 方法 | 描述 |
|------|------|
| `exceptionally(fn)` | 异常降级 |
| `handle(fn)` | 正常/异常都处理，可修改结果 |
| `whenComplete(action)` | 正常/异常都处理，不修改结果 |

### 超时控制（Java 9+）

| 方法 | 描述 |
|------|------|
| `orTimeout(timeout, unit)` | 超时后以 TimeoutException 异常完成 |
| `completeOnTimeout(value, timeout, unit)` | 超时后以指定值正常完成 |

### 完成操作

| 方法 | 描述 |
|------|------|
| `complete(value)` | 手动以指定值完成 |
| `completeExceptionally(ex)` | 手动以异常完成 |
| `cancel(mayInterrupt)` | 以 CancellationException 完成 |

---

> **总结**：CompletableFuture 是 Java 异步编程的基石，理解其线程池规则、异常传播机制和底层 Treiber Stack 实现是掌握进阶用法的关键。生产环境中务必：**指定线程池、处理异常、避免阻塞、添加监控**。对于复杂的流式场景，考虑使用 RxJava/Reactor/Kotlin 协程。
