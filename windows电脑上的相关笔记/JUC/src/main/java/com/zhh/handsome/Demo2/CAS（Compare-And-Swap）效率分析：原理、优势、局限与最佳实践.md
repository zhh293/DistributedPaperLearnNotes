# CAS（Compare-And-Swap）效率分析：原理、优势、局限与最佳实践

CAS 是**无锁并发编程的核心原子操作**，广泛应用于 Java 并发包（JUC）、Spring 框架（如缓存、锁机制）及分布式系统中。其核心优势是**避免线程阻塞 / 唤醒的上下文切换开销**，但效率受并发竞争、硬件支持等因素影响显著。本文从原理出发，深入分析 CAS 效率的关键维度、对比其他并发机制，并给出实践优化建议。

## 一、CAS 核心原理回顾

### 1. 定义

CAS（Compare-And-Swap，比较并交换）是一种**硬件级原子操作**，用于解决多线程并发场景下的共享变量更新问题，核心逻辑：

> 给定内存地址 
>
> `V`
>
> 、预期值 
>
> `A`
>
> 、目标值 
>
> `B`
>
> ：



1. 读取内存 `V` 中的当前值 `current`；

2. 比较 `current` 与 `A`：若相等，将 `V` 更新为 `B`（成功）；若不相等，不做操作（失败）；

3. 返回操作结果（成功 / 失败，或旧值）。



### 2. Java 中的实现

Java 通过 `sun.misc.Unsafe` 类（或 Java 9+ `VarHandle`）封装底层 CAS 指令，例如：



* `compareAndSwapInt(Object obj, long offset, int expect, int update)`：原子更新 int 类型变量；

* `compareAndSwapLong(Object obj, long offset, long expect, long update)`：原子更新 long 类型变量。

**典型应用**：`AtomicInteger` 的自增操作（无锁实现）：



```
public final int incrementAndGet() {

&#x20;   // 循环重试 CAS，直到成功

&#x20;   return unsafe.getAndAddInt(this, valueOffset, 1) + 1;

}

// Unsafe 底层实现（简化）

public final int getAndAddInt(Object obj, long offset, int delta) {

&#x20;   int expect;

&#x20;   do {

&#x20;       expect = this.getIntVolatile(obj, offset); // volatile 读，保证可见性

&#x20;   } while (!this.compareAndSwapInt(obj, offset, expect, expect + delta)); // CAS 核心

&#x20;   return expect;

}
```

## 二、CAS 效率优势：为什么无锁比有锁快？

CAS 的效率核心源于**无锁设计**，避免了传统锁（如 `synchronized`、`ReentrantLock`）的两大开销：

### 1. 避免上下文切换开销（核心优势）



* **有锁机制**（如 `synchronized` 重量级锁）：线程竞争失败时会被阻塞（`BLOCKED` 状态），需要操作系统介入：

1. 保存当前线程的寄存器、栈帧等状态；

2. 调度器切换到其他就绪线程；

3. 线程被唤醒时，恢复之前的状态。

* 上下文切换的开销约为 **微秒级到毫秒级**，高并发场景下频繁切换会严重拖慢性能。

- **CAS 无锁机制**：线程竞争失败时不会阻塞，而是通过**自旋重试**（用户态循环）尝试再次更新，全程无需操作系统内核态干预，开销仅为**纳秒级**（循环 + 硬件指令）。

### 2. 硬件级原子性，开销极低

CAS 操作最终依赖 CPU 底层指令实现（如 x86 的 `cmpxchg`、ARM 的 `ldrex/strex`），是**单条硬件指令**，原子性由硬件保证：



* 无需软件层面的锁协议（如监视器锁、信号量）；

* 无锁竞争时，CAS 操作可在 **1-2 个 CPU 周期** 内完成，效率远超软件锁。

### 3. 低并发 / 无竞争场景：接近串行性能

当共享变量竞争程度低（如大部分线程读、少量线程写）时，CAS 几乎可以 “一次成功”，自旋次数为 0，此时性能接近串行执行，远超有锁机制（即使是轻量级锁也需要锁标记的更新）。

## 三、CAS 效率局限：哪些场景会变慢？

CAS 并非 “万能高效”，在特定场景下效率会显著下降，甚至不如有锁机制：

### 1. 高并发竞争：自旋重试导致 CPU 空转

CAS 的 “自旋重试” 是把双刃剑：



* 低竞争：自旋 0-1 次即可成功，开销可忽略；

* 高竞争：大量线程同时修改同一个共享变量，CAS 频繁失败，线程会持续循环重试（如 `AtomicInteger` 无限制自旋），导致：

1. CPU 利用率飙升（可能 100%）；

2. 线程空转消耗 CPU 资源，反而挤压其他任务的执行时间；

3. 效率低于有锁机制（有锁会让竞争失败的线程阻塞，释放 CPU 给其他线程）。

**示例**：1000 个线程同时调用 `AtomicInteger.incrementAndGet()`，高并发下自旋次数激增，性能可能比 `synchronized` 还差。

### 2. 变量大小限制：64 位变量的额外开销

CAS 仅支持**单个变量**的原子更新，且变量大小受硬件限制：



* 32 位 CPU 上，64 位变量（`long`/`double`）的 CAS 操作无法通过单条指令完成，需要分成两次 32 位操作；

* Java 中 `AtomicLong`/`AtomicDouble` 在 32 位 JVM 上，底层会通过 “隐含锁”（`Unsafe` 的 `lock` 指令）保证原子性，此时 CAS 退化为有锁操作，效率大幅下降。

### 3. ABA 问题的解决方案：额外开销

CAS 存在 **ABA 问题**（变量被修改为 B 后又改回 A，CAS 误判为未修改），解决方案会引入额外开销：



* `AtomicStampedReference`：通过 “值 + 版本号” 双校验解决 ABA，每次 CAS 需同时比较值和版本号，操作开销比普通 CAS 高 30%-50%；

* `AtomicMarkableReference`：通过 “标记位” 替代版本号，开销略低于 `AtomicStampedReference`，但仍高于普通 CAS。

### 4. 只能操作单个变量：复合操作的效率瓶颈

CAS 仅支持单个变量的原子更新，若需要原子更新多个变量（如 “更新变量 A 同时更新变量 B”），需：



* 用锁包裹多个 CAS 操作（退化为有锁，失去无锁优势）；

* 将多个变量封装为一个对象（如自定义 `Pair` 类），通过 `AtomicReference` 原子更新对象引用，但会增加对象创建 / 拷贝开销。

## 四、CAS 与其他并发机制的效率对比



| 并发机制                     | 核心开销             | 低并发效率 | 高并发效率     | 适用场景              |
| ------------------------ | ---------------- | ----- | --------- | ----------------- |
| CAS（`AtomicX`）           | 自旋重试（用户态）        | 极高    | 低（CPU 空转） | 单个变量更新、低竞争场景      |
| `synchronized`（轻量级锁）     | 锁标记更新（用户态）       | 高     | 中         | 方法 / 代码块同步、中等竞争   |
| `synchronized`（重量级锁）     | 上下文切换（内核态）       | 低     | 中         | 高竞争场景（线程阻塞释放 CPU） |
| `ReentrantLock`          | 锁竞争队列（用户态 + 内核态） | 中     | 中高        | 复杂锁场景（可中断、公平锁）    |
| 分段锁（`ConcurrentHashMap`） | 减少竞争粒度           | 高     | 高         | 海量数据的并发读写         |

### 关键结论：



* 低竞争场景：CAS > 轻量级 `synchronized` > `ReentrantLock`；

* 中高竞争场景：分段锁 > 重量级 `synchronized`/`ReentrantLock` > CAS；

* 单个变量更新：CAS 最优；多个变量更新：锁或分段锁更高效。

## 五、优化 CAS 效率的实践建议

### 1. 控制并发竞争程度（核心优化）



* **减少共享变量**：用线程本地变量（`ThreadLocal`）替代共享变量，避免竞争（如 Spring 中的 `RequestContextHolder`）；

* **拆分共享变量**：将一个高竞争变量拆分为多个低竞争变量（如计数器拆分为 `count1`、`count2`，最后汇总，类似 `LongAdder` 的设计）；

* **使用分段锁**：对共享数据分片，每个分片用独立的 CAS 变量（如 `ConcurrentHashMap` 的 Segment 设计，JDK 8 后改为 Node 数组 +  synchronized，但核心思想一致）。

### 2. 限制自旋次数，避免 CPU 空转

JUC 中部分类通过**限制自旋次数**优化高竞争场景（如 `LinkedTransferQueue`），自定义 CAS 操作时可借鉴：



```
// 限制自旋次数为 3 次，失败则降级为其他策略（如阻塞）

public boolean casWithSpinLimit(Object obj, long offset, int expect, int update) {

&#x20;   int spinCount = 0;

&#x20;   do {

&#x20;       if (unsafe.compareAndSwapInt(obj, offset, expect, update)) {

&#x20;           return true;

&#x20;       }

&#x20;       spinCount++;

&#x20;   } while (spinCount < 3 && Thread.currentThread().isAlive());

&#x20;   // 自旋失败后降级：如使用 Lock 或阻塞队列

&#x20;   return false;

}
```

### 3. 选择合适的 CAS 工具类



* 单个 int/long 变量：优先用 `AtomicInteger`/`AtomicLong`（效率最高）；

* 需解决 ABA 问题：用 `AtomicStampedReference`（版本号）或 `AtomicMarkableReference`（标记位），避免过度设计；

* 高竞争计数器：用 `LongAdder`/`DoubleAdder`（内部拆分为多个单元格，减少竞争，效率远超 `AtomicLong`）。

### 4. 避免 64 位变量在 32 位 JVM 上的 CAS



* 32 位 JVM 中，`AtomicLong`/`AtomicDouble` 的 CAS 会退化为有锁操作，建议：

1. 优先使用 64 位 JVM；

2. 若必须用 32 位 JVM，将 64 位变量拆分为两个 32 位变量（如 `high` + `low`），分别用 `AtomicInteger` 维护。

### 5. 复合操作优先用锁或原子引用



* 若需原子更新多个变量，优先用 `synchronized` 或 `ReentrantLock`（代码简洁，效率比手动 CAS 重试更高）；

* 若需无锁实现，用 `AtomicReference` 封装多个变量为一个对象（如 `AtomicReference<Pair>`），但需注意对象创建开销。

## 六、总结：CAS 效率的核心规律



1. **效率本质**：无锁设计避免上下文切换，硬件指令保证原子性，低竞争场景下性能最优；

2. **效率瓶颈**：高竞争导致的自旋空转、64 位变量的额外开销、ABA 解决方案的 overhead；

3. **适用场景**：单个变量更新、低并发竞争、追求极致性能的场景（如计数器、缓存更新）；

4. **优化关键**：减少竞争粒度（拆分变量、分段锁）、限制自旋次数、选择合适的工具类。

CAS 是并发编程的 “性能利器”，但需根据实际场景合理使用 —— 低竞争时 “如鱼得水”，高竞争时需结合锁或分段机制规避短板，才能最大化系统效率。

> （注：文档部分内容可能由 AI 生成）