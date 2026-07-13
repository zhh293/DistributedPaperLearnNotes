# CyclicBarrier 深度解析

## 一、CyclicBarrier 是什么

### 1.1 从实际场景引入

假设你遇到这样一个需求：有一个巨大的矩阵运算任务，需要拆分成 4 个子任务交给 4 个线程并行计算。每个线程算完自己那部分后，需要等待其他线程全部完成，然后主线程（或某个线程）把所有结果汇总。汇总完毕后，可能还要进入下一轮并行计算，再等待、再汇总……如此反复多轮。

如果用最朴素的方式写，你可能会为每一轮都创建一个 `CountDownLatch`，然后小心翼翼地计数、await、再重新创建。代码冗长且容易出错。而 `CyclicBarrier` 天生就是为这种"多个线程互相等待到齐，然后一起继续"的场景设计的。

用 `CyclicBarrier` 写出来的代码大致是这样的：

```java
import java.util.concurrent.*;

public class MatrixCompute {
    public static void main(String[] args) {
        int parties = 4;
        // 所有线程到齐后执行的动作
        CyclicBarrier barrier = new CyclicBarrier(parties, () -> {
            System.out.println("所有子任务完成，执行汇总...");
        });

        for (int i = 0; i < parties; i++) {
            final int taskId = i;
            new Thread(() -> {
                for (int phase = 0; phase < 3; phase++) {
                    System.out.println("线程 " + taskId + " 第 " + phase + " 轮计算完成");
                    try {
                        barrier.await(); // 等待其他线程到达屏障
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }
}
```

这段代码里，4 个线程在每一轮都会各自执行计算，然后调用 `barrier.await()` 在屏障处等待。当第 4 个线程（最后一个到达的线程）调用 `await()` 时，屏障打开，barrierAction 被执行，然后所有 4 个线程同时被唤醒继续执行下一轮。这个过程可以循环发生——这就是 "Cyclic"（循环的）的含义。

### 1.2 与 CountDownLatch 的核心区别

`CyclicBarrier` 和 `CountDownLatch` 都是 Java 并发包中用于线程同步的工具，初学者很容易混淆。它们的本质区别在于：

| 对比维度 | CyclicBarrier | CountDownLatch |
|---------|--------------|----------------|
| 可重用性 | 可循环使用（屏障打开后自动重置） | 一次性（计数到 0 后不可再用） |
| 计数方向 | 递减：从 `parties` 减到 0 | 递减：从初始 `count` 减到 0 |
| 等待模式 | 参与方**互相等待**（所有线程都在屏障处 await） | 外部线程等待计数器归零（通常是主线程 await，子线程 countDown） |
| 底层实现 | `ReentrantLock` + `Condition` | AQS 共享模式 |
| 屏障动作 | 支持 `Runnable barrierAction` | 无 |
| 异常传播 | 一个线程出问题，所有等待线程都会收到 `BrokenBarrierException` | 一个线程异常只影响自己的 countDown，其他线程不一定感知 |

简单总结一句话：**CountDownLatch 是"一个人等多个人"，CyclicBarrier 是"多个人互等"。**

### 1.3 "Cyclic" 的含义

"Cyclic"（循环的）这个词点明了 `CyclicBarrier` 最核心的特性——屏障可以被重复使用。当所有参与线程都到达屏障后，屏障打开，线程们继续执行。与此同时，屏障会自动重置，准备迎接下一轮的等待。这就像赛跑比赛中的起跑线：选手们就绪后起跑，跑完一圈后回到起跑线再次就绪、再次起跑。

---

## 二、核心字段与构造方法源码分析

### 2.1 类声明与核心字段

先看 JDK 源码中 `CyclicBarrier` 的类声明和核心字段定义：

```java
public class CyclicBarrier {
    /** 内部类：用于管理屏障的"代"（generation） */
    private static class Generation {
        boolean broken = false; // 标记当前屏障是否已被破坏
    }

    /** 保护屏障状态的锁 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 线程等待的条件变量，与 lock 配合使用 */
    private final Condition trip = lock.newCondition();
    /** 参与方总数（不可变，在构造时确定） */
    private final int parties;
    /** 屏障动作：所有线程到齐后、唤醒所有线程之前执行 */
    private final Runnable barrierCommand;
    /** 当前代（generation） */
    private Generation generation = new Generation();

    /**
     * 当前还需要等待的线程数。
     * 每个线程到达屏障时 count 减 1，
     * 减到 0 时表示所有线程已到齐。
     * 屏障重置时，count 会被重置为 parties。
     */
    private int count;
    // ...
}
```

下面逐一分析每个字段的设计意图。

### 2.2 ReentrantLock lock

`lock` 是一把可重入锁（`ReentrantLock`），用于保护 `CyclicBarrier` 的所有内部状态——包括 `count`、`generation` 等。任何对屏障状态的操作（如 `await()`、`reset()`、`getNumberWaiting()` 等）都必须先获取这把锁。

你可能会问：为什么不用 `synchronized`？原因是 `CyclicBarrier` 需要与 `Condition` 配合使用（`trip` 就是基于 `lock` 创建的），而 `synchronized` 只能配合 `Object.wait()/notify()`，不如 `Condition` 灵活。`Condition` 支持更精细的线程等待/唤醒控制，代码也更清晰。

### 2.3 Condition trip

`trip` 是一个 `Condition` 对象，由 `lock.newCondition()` 创建。它是线程等待和被唤醒的关键机制。

当一个线程到达屏障但不是最后一个时，它会调用 `trip.await()` 进入等待状态，释放锁并阻塞。当最后一个线程到达后，会调用 `trip.signalAll()` 唤醒所有在 `trip` 上等待的线程。

为什么叫 `trip`（旅行/绊倒）？这里的语义是"触发"屏障——当最后一个线程到达时，它"trips"（触发）了屏障，所有等待线程被释放。这是 JDK 作者的命名习惯。

### 2.4 int parties

`parties` 是参与线程的总数，在构造时确定，**永远不变**。它是屏障的"容量"——屏障期望有多少个线程到达后才会打开。

注意 `parties` 是 `final` 的，一旦在构造方法中赋值就不可修改。而 `count` 是可变的，它在每次屏障重置时被重新赋值为 `parties`。

### 2.5 int count

`count` 是当前还需要等待的线程数。初始值为 `parties`，每有一个线程调用 `await()` 就减 1。当 `count` 减到 0 时，说明所有线程都已到达，屏障打开。

屏障重置时（`nextGeneration()`），`count` 被重新赋值为 `parties`，开始下一轮计数。这是实现"循环"的关键之一。

### 2.6 Runnable barrierCommand

`barrierCommand` 是一个可选的 `Runnable`，在构造方法中传入。当最后一个线程到达屏障时，会先执行这个 `barrierCommand`，然后再唤醒其他等待线程。

`barrierCommand` 的典型用途是在每一轮计算结束后执行汇总逻辑。注意它是在**最后一个到达的线程**中执行的，而不是在单独的线程中。这意味着如果 `barrierCommand` 抛出异常，屏障会被破坏。

### 2.7 Generation generation

`generation` 是 `CyclicBarrier` 内部类 `Generation` 的一个实例，用于标识屏障的当前"代"。每次屏障重置（`nextGeneration()`）时，会创建一个新的 `Generation` 对象。这个设计非常巧妙，后面会详细解释。

#### Generation 内部类的设计意图：为什么需要这个对象？

考虑这样一个场景：线程 A 在第 1 代调用了 `await()` 并进入等待。此时屏障被重置进入第 2 代。然后线程 A 从等待中被唤醒。它怎么知道自己是被正常唤醒（屏障打开），还是被异常唤醒（屏障被破坏）？

解决方案是：每个线程在 `await()` 开始时记录当前的 `generation` 引用，然后在被唤醒后检查这个引用是否还等于 `CyclicBarrier` 当前的 `generation`。如果不等于，说明屏障已经进入了新的一代——自己是在旧一代中等待的，是被 `nextGeneration()` 唤醒的，应该正常返回。如果等于，说明屏障在自己的这一代中被破坏了（`breakBarrier()` 没有创建新的 Generation），需要抛出异常。

`Generation` 只有一个 `boolean broken` 字段，标记当前代是否被破坏。`Generation` 本身被设计为一个简单的标记对象，它不需要包含太多信息，核心作用就是提供一个"身份标识"——通过引用比较来区分不同的代。

如果不使用 `Generation` 机制，就需要额外维护一个版本号或用其他复杂方式来判断线程属于哪一代，代码会复杂很多。用对象引用比较是最简洁、最可靠的方式。

### 2.8 构造方法

```java
/**
 * 创建一个 CyclicBarrier，当给定数量的线程（parties）都在其上等待时，
 * 屏障将被触发，但不执行预定义的动作。
 *
 * @param parties 屏障触发前必须调用 await() 的线程数
 * @throws IllegalArgumentException 如果 parties <= 0
 */
public CyclicBarrier(int parties) {
    this(parties, null);
}

/**
 * 创建一个 CyclicBarrier，当给定数量的线程都在其上等待时，
 * 屏障将被触发，并在释放所有线程之前执行给定的屏障动作。
 *
 * @param parties 屏障触发前必须调用 await() 的线程数
 * @param barrierAction 屏障触发时执行的动作，可以为 null
 * @throws IllegalArgumentException 如果 parties <= 0
 */
public CyclicBarrier(int parties, Runnable barrierAction) {
    if (parties <= 0) throw new IllegalArgumentException();
    this.parties = parties;
    this.count = parties;       // 初始时 count 等于 parties
    this.barrierCommand = barrierAction;
}
```

两个构造方法都很简单。核心逻辑是：`parties` 和 `barrierCommand` 被赋值且不可变（`final`），`count` 初始化为 `parties`，`generation` 默认创建一个新的 `Generation` 对象（`broken = false`）。

---

## 三、await() 方法完整源码逐行分析

`await()` 是 `CyclicBarrier` 最核心的方法。它有两个版本——无参和带超时参数：

```java
public int await() throws InterruptedException, BrokenBarrierException {
    try {
        return dowait(false, 0L);
    } catch (TimeoutException toe) {
        throw new Error(toe); // cannot happen（不可能发生，因为没有设置超时）
    }
}

public int await(long timeout, TimeUnit unit)
    throws InterruptedException, BrokenBarrierException, TimeoutException {
    return dowait(true, unit.toNanos(timeout));
}
```

两个版本都委托给 `dowait(boolean timed, long nanos)` 执行。返回值是当前线程到达屏障时的索引——如果 `parties` 个线程按顺序到达，最后到达的线程返回 0，第一个到达的返回 `parties - 1`。这个索引可以用来做一些特殊处理（比如让最后到达的线程做额外的工作）。

### 3.1 dowait() 完整源码

下面是 `dowait()` 的完整源码（基于 JDK 8/11），我会逐行注释解释：

```java
private int dowait(boolean timed, long nanos)
    throws InterruptedException, BrokenBarrierException, TimeoutException {
    
    final ReentrantLock lock = this.lock;
    // 1. 获取锁——所有对屏障状态的操作都必须在锁保护下进行
    lock.lock();
    try {
        // 2. 记录当前代的引用
        //    每个线程在进入等待前先保存当前 generation 的引用，
        //    被唤醒后用它来判断自己属于哪一代
        final Generation g = generation;

        // 3. 安全检查：当前屏障是否已经被破坏？
        //    如果 g.broken 为 true，直接抛出 BrokenBarrierException。
        //    这种情况发生在：之前有线程中断/超时导致屏障被 breakBarrier()，
        //    而当前线程此时才到达。
        if (g.broken)
            throw new BrokenBarrierException();

        // 4. 检查当前线程是否被中断
        //    如果当前线程在调用 await() 之前就被中断了（interrupt flag 已设置），
        //    则直接破坏屏障并抛出 InterruptedException。
        //    注意：这里用的是 Thread.interrupted()，它会清除中断标志。
        if (Thread.interrupted()) {
            breakBarrier();  // 破坏屏障，唤醒所有等待线程
            throw new InterruptedException();
        }

        // 5. 递减计数器，并判断当前线程是否是最后一个到达的
        int index = --count;
        if (index == 0) {  // 最后一个线程到达！
            boolean ranAction = false;
            try {
                // 6. 执行屏障动作（如果构造时指定了）
                //    注意：barrierCommand 在当前线程（最后一个到达的线程）中执行
                final Runnable command = barrierCommand;
                if (command != null)
                    command.run();
                ranAction = true;
                // 7. 进入下一代——唤醒所有等待线程、重置 count、创建新 generation
                nextGeneration();
                // 返回 0，表示当前线程是最后一个到达的
                return 0;
            } finally {
                // 8. 如果 barrierCommand 执行抛出异常，
                //    ranAction 仍为 false，需要破坏屏障
                if (!ranAction)
                    breakBarrier();
            }
        }

        // ============ 以下是非最后一个线程的等待逻辑 ============
        
        // 9. 当前线程不是最后一个到达的，需要循环等待
        //    使用自旋循环是因为等待过程中可能遇到多种异常情况需要重新判断
        for (;;) {
            try {
                // 10. 根据是否设置超时，选择不同的等待方式
                if (!timed)
                    trip.await();          // 无限等待，直到被 signal 或中断
                else if (nanos > 0L)
                    nanos = trip.awaitNanos(nanos);  // 限时等待
            } catch (InterruptedException ie) {
                // 11. 等待过程中被中断
                //     只有当当前代没有被破坏且没有被重置时，才需要破坏屏障
                if (g == generation && ! g.broken) {
                    breakBarrier();  // 破坏屏障
                    throw ie;        // 重新抛出中断异常
                } else {
                    // 12. 如果屏障已经被破坏或已经进入新一代，
                    //     说明中断来得"太晚了"，屏障已经被其他原因处理了。
                    //     此时只需要设置中断标志（自我中断），让上层代码感知，
                    //     然后继续循环，在下面检查到 broken 后抛出对应异常。
                    Thread.currentThread().interrupt();
                }
            }

            // 13. 被唤醒后的检查
            //     情况一：屏障被破坏
            if (g.broken)
                throw new BrokenBarrierException();

            // 14. 情况二：屏障已经进入了新一代
            //     说明屏障正常打开，当前线程是被 nextGeneration() 唤醒的
            //     g != generation 意味着 generation 引用已经更新（创建了新 Generation）
            if (g != generation)
                return index;  // 正常返回，index 是当前线程的到达索引

            // 15. 情况三：超时等待超时
            if (timed && nanos <= 0L) {
                breakBarrier();  // 破坏屏障
                throw new TimeoutException();  // 抛出超时异常
            }
            // 16. 如果以上情况都不满足，继续循环等待
            //     这种情况很罕见，可能是虚假唤醒（spurious wakeup）
        }
    } finally {
        // 17. 无论正常返回还是抛出异常，最后都要释放锁
        lock.unlock();
    }
}
```

### 3.2 流程详解：每一步为什么这么做

**第 1 步：获取锁**

`CyclicBarrier` 的所有状态（`count`、`generation`）都是共享可变状态，必须用锁保护。使用 `lock.lock()`（非公平模式获取锁）确保同一时刻只有一个线程能修改屏障状态。

**第 2 步：记录当前代的引用**

这一步是整个 `Generation` 机制的核心。线程 A 在第 1 代进入 `dowait()`，保存 `g = generation`（指向第 1 代的 Generation 对象）。之后即使屏障被重置，`generation` 字段指向了新的对象，但 `g` 仍然指向第 1 代的对象。线程 A 被唤醒后，通过比较 `g == generation` 就能判断屏障是否已经进入了新一代。

**第 3 步：检查屏障是否已破坏**

如果线程 A 在到达屏障之前，另一个线程 B 已经因为中断或超时导致 `breakBarrier()` 被调用，那么当前代的 `broken` 字段为 `true`。线程 A 不应该继续等待（屏障已经坏了），直接抛出 `BrokenBarrierException`。这是"异常传播"的体现——一个线程出问题，其他还没到达的线程立刻感知。

**第 4 步：检查中断**

这里使用 `Thread.interrupted()`（会清除中断标志），而不是 `Thread.currentThread().isInterrupted()`（不清除）。原因是：如果线程被中断了，`CyclicBarrier` 要主动破坏屏障并抛出 `InterruptedException`，此时中断标志应该被消费掉。

为什么中断会导致 `breakBarrier()`？因为一个等待线程被中断意味着它无法继续正常参与屏障同步，如果其他线程还在等它，就会永远等下去。所以必须破坏屏障，让所有线程都知道出了问题。

**第 5 步：递减计数器**

`--count` 将计数器减 1，如果结果为 0，说明当前线程是最后一个到达的线程。返回的 `index` 值表示"还差多少个线程到达"——最后一个线程返回 0，倒数第二个返回 1，以此类推。

**第 6 步：执行屏障动作**

如果构造时传入了 `barrierCommand`，在最后一个线程中执行它。注意这里的执行是同步的——如果 `barrierCommand.run()` 耗时很长或抛出异常，其他线程会继续等待（或因异常导致屏障被破坏）。

**第 7 步：nextGeneration()**

这是屏障打开的关键步骤。`nextGeneration()` 做三件事：唤醒所有等待线程、重置 `count`、创建新的 `Generation`。详细分析见下一节。

**第 8 步：异常处理**

如果 `barrierCommand` 抛出异常，`ranAction` 为 `false`，`finally` 块中调用 `breakBarrier()` 破坏屏障。这样其他等待线程被唤醒后会抛出 `BrokenBarrierException`，知道屏障出了问题。

**第 9~16 步：非最后一个线程的等待循环**

这部分是最复杂的。线程调用 `trip.await()`（或 `trip.awaitNanos()`）进入阻塞等待。被唤醒后需要检查三种情况：

- **屏障被破坏**（`g.broken` 为 true）：抛出 `BrokenBarrierException`。
- **屏障已进入新一代**（`g != generation`）：正常返回。
- **超时**（`timed && nanos <= 0L`）：破坏屏障并抛出 `TimeoutException`。

如果都不是，说明是虚假唤醒，继续循环等待。

**第 10 步中的 InterruptedException 处理（第 11~12 步）**

这是最微妙的部分。当线程在 `trip.await()` 中被中断时：

- 如果 `g == generation && !g.broken`：说明当前代还是活跃的（没有被重置也没有被破坏），当前线程的中断会破坏屏障。调用 `breakBarrier()` 并抛出 `InterruptedException`。
- 否则（`g != generation` 或 `g.broken`）：说明屏障已经被重置或破坏了，当前线程的中断来得太晚。此时设置中断标志（`Thread.currentThread().interrupt()`），让上层代码感知，然后继续循环。循环中会检查到 `g.broken` 或 `g != generation`，抛出对应异常或正常返回。

**第 17 步：释放锁**

无论怎样，最后都要在 `finally` 中释放锁，确保不会死锁。

### 3.3 dowait() 流程图

```
dowait(timed, nanos) 开始
        │
        ▼
  获取 lock.lock()
        │
        ▼
  记录 g = generation
        │
        ▼
  g.broken? ──── Yes ──→ 抛出 BrokenBarrierException
        │ No
        ▼
  Thread.interrupted()? ──── Yes ──→ breakBarrier() + 抛出 InterruptedException
        │ No
        ▼
  index = --count
        │
        ▼
  index == 0? ──── Yes ──→ 执行 barrierCommand
        │                    │
        │ No                 ▼
        │              nextGeneration()
        │                    │
        │              return 0
        ▼
  循环等待 loop:
        │
        ▼
  trip.await() / awaitNanos()
        │
        ▼
  被唤醒后:
        ├── g.broken? ──── Yes ──→ 抛出 BrokenBarrierException
        ├── g != generation? ── Yes ──→ return index（正常返回）
        └── timed && nanos<=0? ── Yes ──→ breakBarrier() + 抛出 TimeoutException
        │ No
        └── 继续循环
```

---

## 四、nextGeneration() 方法分析

```java
/**
 * 将屏障更新到下一代。当所有线程都到达屏障时调用此方法。
 */
private void nextGeneration() {
    // 1. 唤醒上一代的所有等待线程
    //    signalAll() 会让所有在 trip 条件上等待的线程被唤醒，
    //    它们从 trip.await() 返回后，会检查到 g != generation，正常返回
    trip.signalAll();

    // 2. 重置计数器为 parties，准备下一轮
    //    这就是"循环"的关键——count 回到初始值
    count = parties;

    // 3. 创建新的 Generation 对象
    //    新的 generation.broken = false，表示新的屏障是完好的
    //    旧的 generation 对象不再被引用（会被 GC 回收）
    //    所有在新一代中 await 的线程会记录这个新的 generation 引用
    generation = new Generation();
}
```

### 4.1 三个步骤的执行顺序

这三步的顺序非常重要：

1. **先 `signalAll()`**：唤醒所有在当前代等待的线程。这些线程从 `trip.await()` 返回后，还需要重新获取 `lock` 才能继续执行。但此时 `lock` 仍然被最后一个线程持有（`nextGeneration()` 是在最后一个线程的 `dowait()` 中调用的），所以被唤醒的线程会阻塞在获取锁的步骤上。

2. **再重置 `count`**：重置为 `parties`，为下一轮做准备。被唤醒的线程此时还在等锁，不会读到这个新值。

3. **最后创建新 `Generation`**：这步最关键。被唤醒的线程最终获取到锁后，会检查 `g != generation`——由于 `generation` 已经指向了新的对象，`g`（旧引用）不等于新的 `generation`，所以它们正常返回。

### 4.2 为什么 signalAll 在前、创建新 Generation 在后？

假设反过来——先创建新 `Generation`，再 `signalAll()`：

被唤醒的线程获取锁后检查 `g != generation`（为 true），正常返回。看起来也没问题。

但关键在于 `signalAll()` 的语义。`Condition.signalAll()` 只是将等待线程从条件队列转移到同步队列，它们还需要竞争锁才能真正运行。在它们获取锁之前，最后一个线程已经完成了 `nextGeneration()` 并释放了锁。所以顺序并不影响正确性，但先 `signalAll` 再重置状态更符合直觉——"先释放等待者，再重置状态"。

### 4.3 nextGeneration() 与"Cyclic"的关系

`nextGeneration()` 是 `CyclicBarrier` 实现可重用的核心。每次屏障打开时：

- `count` 被重置为 `parties`——计数器回到起点。
- `Generation` 被更新——旧的代结束了，新的代开始了。

这意味着屏障可以立即投入下一轮使用，不需要外部做任何额外操作。线程们可以在同一个 `CyclicBarrier` 实例上反复 `await()`，每一轮都像使用一个新的屏障一样。

---

## 五、breakBarrier() 方法分析

```java
/**
 * 破坏当前屏障。仅在持有锁时调用。
 */
private void breakBarrier() {
    // 1. 将当前代的 broken 标志设为 true
    //    所有在这个代中等待或即将到达的线程都会看到这个标志
    generation.broken = true;

    // 2. 重置计数器为 parties
    //    这一步看起来有点奇怪——屏障都被破坏了，为什么还要重置 count？
    //    原因：如果用户捕获异常后想重新使用这个 CyclicBarrier，
    //    count 必须是正确的值才能开始新一轮。
    //    但注意，generation.broken 仍然是 true，所以需要手动 reset() 才能正常使用。
    count = parties;

    // 3. 唤醒所有在当前代等待的线程
    //    这些线程被唤醒后，检查到 g.broken == true，会抛出 BrokenBarrierException
    trip.signalAll();
}
```

### 5.1 什么时候会调用 breakBarrier()

在 `dowait()` 中，以下几种情况会调用 `breakBarrier()`：

1. **线程在调用 `await()` 之前已被中断**（第 4 步）：`Thread.interrupted()` 返回 true 时。
2. **线程在 `trip.await()` 等待过程中被中断**（第 11 步）：且当前代仍然活跃。
3. **等待超时**（第 15 步）：`timed && nanos <= 0L`。
4. **`barrierCommand` 执行抛出异常**（第 8 步）：`finally` 块中 `!ranAction` 为 true。

此外，手动调用 `reset()` 也会间接触发 `breakBarrier()`。

### 5.2 broken 状态的传播

`breakBarrier()` 设置 `generation.broken = true` 后，所有在当前代等待的线程被 `signalAll()` 唤醒。它们获取锁后，检查到 `g.broken` 为 true，抛出 `BrokenBarrierException`。

同时，如果在 `breakBarrier()` 之后有新线程调用 `await()`，它在第 3 步检查 `g.broken` 时就会直接抛出异常。

这就是 `CyclicBarrier` 的"异常传播"机制——一个线程出问题，不会让其他线程无休止地等待，而是让所有线程都能感知到并做出响应。

### 5.3 为什么 breakBarrier 后 count 要重置？

虽然 `broken = true` 意味着当前代已经废了，但把 `count` 重置为 `parties` 是为了防止计数器处于不正确的状态。如果用户捕获了异常后想通过 `reset()` 恢复屏障，`reset()` 中的 `breakBarrier()` 会再次重置 `count`，确保值正确。这是一种防御性编程的思路。

---

## 六、reset() 方法分析

```java
/**
 * 将屏障重置为初始状态。
 * 如果有任何线程当前正在屏障上等待，它们将抛出 BrokenBarrierException。
 */
public void reset() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        // 1. 先破坏当前屏障
        //    如果有线程正在等待，它们会被唤醒并收到 BrokenBarrierException
        breakBarrier(); // break the current generation

        // 2. 再开始下一代
        //    重置 count、创建新的 Generation、唤醒（此时没有线程在等待）
        nextGeneration(); // start a new generation
    } finally {
        lock.unlock();
    }
}
```

### 6.1 reset() 的执行顺序

`reset()` 先调用 `breakBarrier()`，再调用 `nextGeneration()`。这个顺序是有讲究的：

1. **`breakBarrier()`**：设置当前代 `broken = true`，`signalAll()` 唤醒所有等待线程。这些线程被唤醒后会发现 `g.broken` 为 true，抛出 `BrokenBarrierException`。

2. **`nextGeneration()`**：创建新的 `Generation`（`broken = false`），重置 `count = parties`。此时屏障恢复到"完好"状态，可以重新使用。

### 6.2 为什么要先 break 再 nextGeneration？

如果先 `nextGeneration()`，当前等待的线程会被唤醒并发现 `g != generation`（新一代创建了），它们会正常返回——这不是我们想要的！`reset()` 的语义是"强行重置"，正在等待的线程应该被通知屏障已破坏。

所以必须先 `breakBarrier()`（设置 `broken = true`，让等待线程收到 `BrokenBarrierException`），再 `nextGeneration()`（为后续使用创建干净的代）。

### 6.3 reset() 的使用场景

`reset()` 很少在正常流程中使用。常见场景是：屏障在使用过程中出了异常（被 break 了），用户捕获异常后想恢复屏障重新使用。此时调用 `reset()` 可以清除 `broken` 状态，让屏障恢复可用。

但更常见的做法是直接创建一个新的 `CyclicBarrier` 实例，而不是 `reset()` 旧的——这样代码更清晰，也不容易出错。

---

## 七、BrokenBarrierException 异常机制

### 7.1 BrokenBarrierException 是什么

`BrokenBarrierException` 是 `java.util.concurrent` 包中的一个受检异常（checked exception），表示 `CyclicBarrier` 已被破坏。当屏障处于 `broken` 状态时，任何在该屏障上调用 `await()` 的线程都会收到这个异常。

### 7.2 什么情况下会抛出

以下情况会导致 `BrokenBarrierException` 被抛出：

1. **某个等待线程被中断**：该线程会调用 `breakBarrier()`，其他等待线程被唤醒后收到 `BrokenBarrierException`。
2. **某个等待线程超时**：该线程调用 `breakBarrier()`，其他等待线程收到 `BrokenBarrierException`。
3. **`barrierCommand` 抛出异常**：最后一个线程执行 `barrierCommand` 时如果抛出异常，`finally` 块调用 `breakBarrier()`，其他等待线程收到 `BrokenBarrierException`。
4. **手动调用 `reset()`**：等待中的线程被 `breakBarrier()` 唤醒，收到 `BrokenBarrierException`。
5. **屏障已经被破坏后新线程调用 `await()`**：直接在第 3 步检查到 `g.broken` 为 true，抛出 `BrokenBarrierException`。

### 7.3 broken 状态的传播：一个线程出问题，其他线程都会感知到

这是 `CyclicBarrier` 设计中非常重要的一点。考虑以下场景：

```
parties = 3，线程 A、B、C
A 先到达，await()，count=2，进入等待
B 到达，await()，count=1，进入等待
C 在到达前被中断
```

此时 C 调用 `await()` 时检测到 `Thread.interrupted()` 为 true，执行 `breakBarrier()`：
- `generation.broken = true`
- `count = parties = 3`
- `signalAll()` 唤醒 A 和 B

A 和 B 从 `trip.await()` 返回，获取锁后检查到 `g.broken == true`，抛出 `BrokenBarrierException`。

这样 A 和 B 就知道屏障已经坏了，不会永远等下去。它们可以捕获异常后做相应处理（如重试、创建新屏障等）。

### 7.4 代码演示：broken 状态传播

```java
import java.util.concurrent.*;

public class BrokenBarrierDemo {
    public static void main(String[] args) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(3);

        Thread t1 = new Thread(() -> {
            try {
                System.out.println("T1 到达屏障");
                barrier.await();
                System.out.println("T1 通过屏障");
            } catch (BrokenBarrierException e) {
                System.out.println("T1 收到 BrokenBarrierException: " + e.getMessage());
            } catch (InterruptedException e) {
                System.out.println("T1 被中断");
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                System.out.println("T2 到达屏障");
                barrier.await();
                System.out.println("T2 通过屏障");
            } catch (BrokenBarrierException e) {
                System.out.println("T2 收到 BrokenBarrierException: " + e.getMessage());
            } catch (InterruptedException e) {
                System.out.println("T2 被中断");
            }
        });

        Thread t3 = new Thread(() -> {
            try {
                // T3 故意延迟，然后中断自己
                Thread.sleep(1000);
                Thread.currentThread().interrupt();
                System.out.println("T3 到达屏障（已中断）");
                barrier.await();  // 这里会触发 breakBarrier()
            } catch (BrokenBarrierException e) {
                System.out.println("T3 收到 BrokenBarrierException");
            } catch (InterruptedException e) {
                System.out.println("T3 抛出 InterruptedException，屏障被破坏");
            }
        });

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}
```

运行结果：
```
T1 到达屏障
T2 到达屏障
T3 到达屏障（已中断）
T3 抛出 InterruptedException，屏障被破坏
T1 收到 BrokenBarrierException: null
T2 收到 BrokenBarrierException: null
```

可以看到，T3 的中断导致屏障被破坏，T1 和 T2 都收到了 `BrokenBarrierException`。

---

## 八、与 CountDownLatch 的深度对比

### 8.1 底层实现对比

**CyclicBarrier 的底层实现：ReentrantLock + Condition**

```java
// CyclicBarrier 核心字段
private final ReentrantLock lock = new ReentrantLock();
private final Condition trip = lock.newCondition();
private int count;

// 线程等待：trip.await()
// 唤醒所有：trip.signalAll()
```

`CyclicBarrier` 使用 `ReentrantLock` 保护状态，使用 `Condition` 实现线程的等待/唤醒。每个到达的线程都会获取锁、修改 `count`、在 `Condition` 上等待或被唤醒。这是一种基于"显式锁 + 条件变量"的实现方式，与 `synchronized + wait/notify` 类似但更灵活。

**CountDownLatch 的底层实现：AQS 共享模式**

```java
// CountDownLatch 内部使用自定义的 AQS 同步器
private static final class Sync extends AbstractQueuedSynchronizer {
    // ...
    protected int tryAcquireShared(int acquires) {
        return (getState() == 0) ? 1 : -1;
    }
    protected boolean tryReleaseShared(int releases) {
        for (;;) {
            int c = getState();
            if (c == 0) return false;
            int nextc = c - 1;
            if (compareAndSetState(c, nextc))
                return nextc == 0;
        }
    }
}
```

`CountDownLatch` 基于 AQS（AbstractQueuedSynchronizer）的共享模式实现。`countDown()` 对应 `releaseShared(1)`，使用 CAS 操作将 state 减 1。`await()` 对应 `acquireSharedInterruptibly(1)`，当 state 为 0 时才能获取成功（即不再阻塞）。

AQS 是 Java 并发包的基石，`ReentrantLock`、`Semaphore`、`CountDownLatch` 等都基于它。而 `CyclicBarrier` 没有直接使用 AQS，而是通过组合 `ReentrantLock` 和 `Condition` 间接使用了 AQS（因为 `ReentrantLock` 本身基于 AQS）。

**实现差异带来的影响**：

- `CountDownLatch` 的 `countDown()` 使用 CAS 操作，不需要获取锁，性能更高。
- `CyclicBarrier` 的 `await()` 需要获取 `ReentrantLock`，在高并发下竞争更激烈，但提供了更丰富的功能（如 `barrierAction`、`Generation` 机制等）。

### 8.2 语义对比

**CyclicBarrier：线程互相等待**

所有参与方都在屏障处 `await()`，等待其他参与方到达。屏障打开后，所有参与方同时继续。没有"主线程"和"子线程"的区别——所有线程都是平等的参与方。

典型场景：多人赛跑，所有选手就绪后同时起跑。

**CountDownLatch：等待事件完成**

一个或多个线程在 `await()` 处等待，其他线程完成工作后调用 `countDown()` 减少计数器。计数器归零时，等待线程被释放。等待方和计数方是不同的角色。

典型场景：主线程等待 N 个子任务全部完成后再继续。

### 8.3 可重用性对比

```java
// CyclicBarrier 可以重用
CyclicBarrier barrier = new CyclicBarrier(2);
// 第一轮
barrier.await();  // 线程1
barrier.await();  // 线程2 → 屏障打开
// 第二轮（自动重置）
barrier.await();  // 线程1
barrier.await();  // 线程2 → 屏障再次打开

// CountDownLatch 不能重用
CountDownLatch latch = new CountDownLatch(2);
latch.countDown();  // count=1
latch.countDown();  // count=0 → 等待线程释放
// 此时 latch 已废，无法再用。需要新建一个 CountDownLatch。
```

### 8.4 屏障动作对比

```java
// CyclicBarrier 支持屏障动作
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("所有线程到齐，执行汇总逻辑");
});
// 每次屏障打开时都会执行这个 barrierAction

// CountDownLatch 没有屏障动作
CountDownLatch latch = new CountDownLatch(3);
// countDown 到 0 时只是释放等待线程，没有额外的动作
```

### 8.5 代码示例对比：同一需求两种实现

需求：3 个线程各自执行任务，全部完成后主线程打印"全部完成"。

```java
// === CyclicBarrier 实现 ===
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("全部完成！");
});
for (int i = 0; i < 3; i++) {
    final int id = i;
    new Thread(() -> {
        System.out.println("线程 " + id + " 完成");
        try { barrier.await(); } catch (Exception e) {}
    }).start();
}

// === CountDownLatch 实现 ===
CountDownLatch latch = new CountDownLatch(3);
for (int i = 0; i < 3; i++) {
    final int id = i;
    new Thread(() -> {
        System.out.println("线程 " + id + " 完成");
        latch.countDown();
    }).start();
}
latch.await();  // 主线程等待
System.out.println("全部完成！");
```

对比可以看到：`CyclicBarrier` 把汇总逻辑放在了 `barrierAction` 中，而 `CountDownLatch` 需要主线程显式 `await()`。两者都能完成需求，但语义不同——`CyclicBarrier` 是"3 个线程互相等待"，`CountDownLatch` 是"主线程等 3 个事件完成"。

---

## 九、实战场景与代码示例

### 9.1 多阶段并行计算（矩阵分块计算）

场景：将一个大矩阵分成 4 块，4 个线程并行计算每一块的值，每轮计算后通过屏障同步，然后进入下一轮。共 3 轮。

```java
import java.util.concurrent.*;
import java.util.Arrays;

public class MatrixMultiPhaseCompute {
    public static void main(String[] args) {
        int parties = 4;
        int phases = 3;
        double[][] matrix = new double[parties][100]; // 每个线程负责一行

        // 屏障动作：每轮结束后打印进度
        CyclicBarrier barrier = new CyclicBarrier(parties, () -> {
            System.out.println("----- 一轮计算完成 -----");
        });

        Thread[] threads = new Thread[parties];
        for (int i = 0; i < parties; i++) {
            final int row = i;
            threads[i] = new Thread(() -> {
                for (int phase = 0; phase < phases; phase++) {
                    // 模拟并行计算这一行的数据
                    for (int j = 0; j < matrix[row].length; j++) {
                        matrix[row][j] = row * 100 + j + phase * 1000;
                    }
                    System.out.println("线程 " + row + " 第 " + phase + " 轮计算完成");
                    try {
                        int arrivalIndex = barrier.await();
                        // arrivalIndex 可以用来做一些特殊处理
                        // 比如最后一个到达的线程（index=0）可以做额外工作
                        if (arrivalIndex == 0) {
                            // 这里是在新一轮开始后，最后一个到达的线程
                            // 可以做一些跨线程的数据校验等
                        }
                    } catch (InterruptedException | BrokenBarrierException e) {
                        e.printStackTrace();
                        return;
                    }
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) {}
        }
        System.out.println("所有计算完成");
    }
}
```

### 9.2 批量数据分段处理后合并

场景：有一批数据需要分 3 段并行处理，每段处理完后在屏障处等待，全部到齐后执行合并，然后可能进行下一轮处理。

```java
import java.util.concurrent.*;
import java.util.*;

public class BatchDataProcess {
    public static void main(String[] args) {
        int parties = 3;
        List<String> allData = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            allData.add("data-" + i);
        }

        // 每段数据
        int segmentSize = allData.size() / parties;
        
        // 用一个共享容器存放每段处理结果
        Map<Integer, List<String>> results = new ConcurrentHashMap<>();
        
        // 屏障动作：合并所有段的结果
        CyclicBarrier barrier = new CyclicBarrier(parties, () -> {
            System.out.println("=== 合并结果 ===");
            results.values().forEach(list -> {
                list.forEach(System.out::println);
            });
            System.out.println("=== 合并完成 ===\n");
            results.clear();  // 清空，准备下一轮
        });

        for (int i = 0; i < parties; i++) {
            final int segId = i;
            final List<String> segment = allData.subList(
                i * segmentSize, (i + 1) * segmentSize);
            
            new Thread(() -> {
                for (int round = 0; round < 2; round++) {  // 处理2轮
                    List<String> processed = new ArrayList<>();
                    for (String data : segment) {
                        processed.add(data + " [seg=" + segId + ", round=" + round + "]");
                    }
                    results.put(segId, processed);
                    System.out.println("段 " + segId + " 第 " + round + " 轮处理完成");
                    try {
                        barrier.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }
}
```

### 9.3 模拟赛跑（所有选手就绪后同时起跑）

```java
import java.util.concurrent.*;
import java.util.Random;

public class HorseRace {
    public static void main(String[] args) {
        int horseCount = 5;
        // 第一道屏障：所有选手就绪后起跑
        // 第二道屏障：所有选手到达终点后宣布成绩
        CyclicBarrier startBarrier = new CyclicBarrier(horseCount, () -> {
            System.out.println("所有选手就绪，比赛开始！");
        });
        
        CyclicBarrier finishBarrier = new CyclicBarrier(horseCount, () -> {
            System.out.println("所有选手到达终点，比赛结束！");
        });

        String[] names = {"闪电", "飞毛腿", "追风", "赤兔", "疾风"};
        for (int i = 0; i < horseCount; i++) {
            final String name = names[i];
            new Thread(() -> {
                try {
                    System.out.println(name + " 已就绪");
                    Thread.sleep(new Random().nextInt(1000));  // 准备时间
                    
                    startBarrier.await();  // 等待所有选手就绪
                    
                    System.out.println(name + " 开始奔跑...");
                    long startTime = System.currentTimeMillis();
                    Thread.sleep(new Random().nextInt(3000));  // 奔跑时间
                    long finishTime = System.currentTimeMillis() - startTime;
                    System.out.println(name + " 到达终点！用时 " + finishTime + "ms");
                    
                    finishBarrier.await();  // 等待所有选手到终点
                    System.out.println(name + " 比赛结束");
                } catch (InterruptedException | BrokenBarrierException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }
}
```

### 9.4 配合线程池使用的注意事项

在实际开发中，我们通常用线程池管理线程。但 `CyclicBarrier` 配合线程池使用时有一个非常重要的陷阱：**如果线程池的线程数少于 `parties`，程序会永远阻塞！**

```java
import java.util.concurrent.*;

public class ThreadPoolTrap {
    public static void main(String[] args) {
        // 陷阱示例：线程池只有 2 个线程，但 barrier 需要 3 个
        ExecutorService pool = Executors.newFixedThreadPool(2);  // 只有2个线程！
        CyclicBarrier barrier = new CyclicBarrier(3);            // 需要3个
        
        for (int i = 0; i < 3; i++) {
            final int id = i;
            pool.submit(() -> {
                System.out.println("任务 " + id + " 到达屏障");
                try {
                    barrier.await();  // 永远等不到第3个线程！
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        // 第3个任务在队列中等待，但线程池只有2个线程，都在await()阻塞了
        // 结果：死锁！
        
        // pool.shutdown();
    }
}
```

**原因分析**：前 2 个任务获取了线程池的 2 个线程，都调用了 `barrier.await()` 进入等待。第 3 个任务在任务队列中排队，但线程池已经没有空闲线程了（2 个线程都在屏障处阻塞），第 3 个任务永远无法执行，`barrier.await()` 永远等不到第 3 个线程。

**解决方案**：

1. **确保线程池线程数 >= parties**：最基本的要求。
2. **使用带超时的 `await(timeout)`**：即使出了问题也不会永远阻塞。
3. **合理设置任务数量**：不要提交超过线程池容量的任务到屏障。

```java
// 正确示例
ExecutorService pool = Executors.newFixedThreadPool(4);  // 线程数 >= parties
CyclicBarrier barrier = new CyclicBarrier(3);

for (int i = 0; i < 3; i++) {
    final int id = i;
    pool.submit(() -> {
        try {
            System.out.println("任务 " + id + " 到达屏障");
            // 使用带超时的 await，防止永久阻塞
            barrier.await(5, TimeUnit.SECONDS);
            System.out.println("任务 " + id + " 通过屏障");
        } catch (TimeoutException e) {
            System.out.println("任务 " + id + " 等待超时");
        } catch (Exception e) {
            e.printStackTrace();
        }
    });
}
pool.shutdown();
```

---

## 十、常见面试问题

### Q1：CyclicBarrier 和 CountDownLatch 的区别？

这是最高频的面试题之一。回答时可以从以下几个维度展开：

**1. 可重用性**：`CyclicBarrier` 可以重复使用，屏障打开后自动重置；`CountDownLatch` 是一次性的，计数到 0 后就废了。

**2. 语义**：`CyclicBarrier` 是"线程互相等待"——所有参与方都在屏障处 await，等到齐了一起走；`CountDownLatch` 是"等待事件完成"——一个线程 await 等待 N 个事件完成，其他线程 countDown 表示自己的事件完成了。

**3. 底层实现**：`CyclicBarrier` 基于 `ReentrantLock + Condition`；`CountDownLatch` 基于 AQS 共享模式。

**4. 屏障动作**：`CyclicBarrier` 支持 `barrierCommand`，在屏障打开时执行；`CountDownLatch` 没有这个功能。

**5. 异常处理**：`CyclicBarrier` 有 `BrokenBarrierException` 机制，一个线程出问题其他线程都能感知；`CountDownLatch` 没有这种传播机制。

**6. 计数方向**：两者都是递减计数，但 `CyclicBarrier` 的计数是内部维护的（每次 await 减 1），`CountDownLatch` 的计数是外部调用的（每次 countDown 减 1）。

### Q2：CyclicBarrier 是怎么实现可重用的？

可重用的核心在于 `nextGeneration()` 方法，它在屏障打开时（最后一个线程到达时）被调用，做三件事：

1. `trip.signalAll()`：唤醒所有等待线程。
2. `count = parties`：将计数器重置为初始值。
3. `generation = new Generation()`：创建新的代。

这三步完成后，屏障就恢复到了初始状态，可以立即投入下一轮使用。线程们在同一个 `CyclicBarrier` 实例上反复调用 `await()`，每一轮都会经历"到达 → 等待 → 屏障打开 → 重置 → 下一轮"的循环。

此外，`Generation` 机制保证了线程不会混淆不同的轮次——线程通过比较自己保存的 `generation` 引用和当前的 `generation` 引用来判断自己属于哪一代。

### Q3：如果其中一个线程超时了，其他线程会怎样？

假设 `parties = 3`，线程 A 和 B 已经在 `await()` 中等待，线程 C 还没到达。

如果 A 设置了超时且超时了：

1. A 从 `trip.awaitNanos()` 返回，`nanos <= 0`。
2. A 调用 `breakBarrier()`：
   - `generation.broken = true`
   - `count = parties`
   - `trip.signalAll()` 唤醒 B
3. A 抛出 `TimeoutException`。
4. B 被唤醒，获取锁后检查到 `g.broken == true`，抛出 `BrokenBarrierException`。
5. C 如果之后调用 `await()`，检查到 `g.broken == true`，也抛出 `BrokenBarrierException`。

所以结论是：**一个线程超时会导致整个屏障被破坏，所有其他等待线程都会收到 `BrokenBarrierException`**。这是设计上的选择——屏障需要所有参与方都到达才能打开，少一个都不行，所以任何一个出问题都要通知所有人。

如果用户想在异常后重新使用屏障，需要调用 `reset()` 或创建新的 `CyclicBarrier`。

### Q4：barrierAction 在哪个线程执行？

`barrierAction` 在**最后一个到达屏障的线程**中执行，不是在单独的线程中，也不是在主线程中。

具体来说，在 `dowait()` 方法中，当 `--count == 0` 时（最后一个线程到达），会在该线程中同步调用 `command.run()`。这意味着：

1. **同步执行**：`barrierAction` 的执行会阻塞最后一个线程，其他等待线程要等 `barrierAction` 执行完（以及 `nextGeneration()` 完成）后才会被唤醒。
2. **单线程执行**：`barrierAction` 不需要考虑并发问题，因为同一时刻只有一个线程在执行它。
3. **异常影响**：如果 `barrierAction` 抛出未捕获的异常，`finally` 块中会调用 `breakBarrier()`，导致屏障被破坏，其他等待线程收到 `BrokenBarrierException`。

```java
// 源码中的相关部分
if (index == 0) {  // 最后一个线程到达
    boolean ranAction = false;
    try {
        final Runnable command = barrierCommand;
        if (command != null)
            command.run();        // 在当前（最后一个）线程中执行
        ranAction = true;
        nextGeneration();
        return 0;
    } finally {
        if (!ranAction)
            breakBarrier();        // 如果 barrierAction 抛异常，破坏屏障
    }
}
```

### Q5：CyclicBarrier 的 await() 返回值是什么意思？

`await()` 返回一个 `int` 值，表示当前线程到达屏障时的索引。如果 `parties` 个线程按顺序到达，最后到达的返回 0，倒数第二个返回 1，第一个到达的返回 `parties - 1`。

这个返回值可以用来让最后一个到达的线程做额外的工作：

```java
int index = barrier.await();
if (index == 0) {
    // 我是最后一个到达的线程
    // 可以做一些只需要做一次的初始化工作
    // 但注意：此时其他线程已经被唤醒，可能已经开始执行了
}
```

不过需要注意，返回 0 的线程是最后一个到达的，它在 `nextGeneration()` 之后返回。此时其他线程可能已经从 `await()` 返回并开始执行了。所以返回值 0 更多是提供一种"我触发了屏障"的标识，而不是严格的"先于其他线程执行"的保证。

### Q6：CyclicBarrier 中的 Generation 有什么作用？

`Generation` 的核心作用是区分不同的"轮次"（代），防止线程在屏障重置后混淆。

考虑一个场景：线程 A 在第 1 代 `await()` 并进入等待。此时屏障被 `reset()`，进入第 2 代。然后有线程在第 2 代中 `await()` 并触发了 `breakBarrier()`。线程 A 被唤醒后，如何判断自己是应该正常返回还是抛出异常？

通过比较 `g`（线程 A 保存的第 1 代引用）和 `generation`（当前代引用）：
- 如果 `g != generation`：说明线程 A 属于旧一代，它是在 `nextGeneration()` 中被正常唤醒的，应该正常返回。
- 如果 `g == generation && g.broken`：说明线程 A 属于当前代，且当前代被破坏了，应该抛出 `BrokenBarrierException`。

如果没有 `Generation` 机制，线程就无法区分自己是被正常唤醒还是被异常唤醒，可重用性就无法实现。

### Q7：为什么 CyclicBarrier 用 ReentrantLock 而不用 synchronized？

主要原因有三点：

1. **Condition 的灵活性**：`CyclicBarrier` 需要精确控制线程的等待和唤醒，`Condition` 提供了 `await()`、`signalAll()`、`awaitNanos()` 等方法，比 `Object.wait()/notifyAll()` 更灵活。特别是 `awaitNanos()` 支持纳秒级超时，`synchronized` 体系下没有等价方法。

2. **锁的公平性可选**：`ReentrantLock` 可以选择公平或非公平模式，虽然 `CyclicBarrier` 默认用非公平模式，但保留了扩展能力。

3. **设计一致性**：JUC 包中的工具类普遍使用 `ReentrantLock` 而非 `synchronized`，这是一种设计风格的选择。`ReentrantLock` 基于 AQS，与 JUC 整体架构一致。

---

## 附录：CyclicBarrier 完整源码（JDK 11）

以下附上 JDK 11 中 `CyclicBarrier` 的核心源码（省略了部分注释），供参考：

```java
package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CyclicBarrier {
    private static class Generation {
        Generation() {}                 // prevent access constructor creation
        boolean broken;                 // initially false
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition trip = lock.newCondition();
    private final int parties;
    private final Runnable barrierCommand;
    private Generation generation = new Generation();

    private int count;

    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    private void nextGeneration() {
        trip.signalAll();
        count = parties;
        generation = new Generation();
    }

    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }

    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe);
        }
    }

    public int await(long timeout, TimeUnit unit)
        throws InterruptedException, BrokenBarrierException, TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    private int dowait(boolean timed, long nanos)
        throws InterruptedException, BrokenBarrierException, TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Generation g = generation;

            if (g.broken)
                throw new BrokenBarrierException();

            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            int index = --count;
            if (index == 0) {  // tripped
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)
                        breakBarrier();
                }
            }

            for (;;) {
                try {
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        Thread.currentThread().interrupt();
                    }
                }

                if (g.broken)
                    throw new BrokenBarrierException();

                if (g != generation)
                    return index;

                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int getParties() {
        return parties;
    }

    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 总结

`CyclicBarrier` 是一个设计精巧的并发同步工具，核心特点如下：

1. **可循环使用**：通过 `nextGeneration()` 重置 `count` 和创建新 `Generation`，实现屏障的自动重置。
2. **线程互等语义**：所有参与方在屏障处等待，到齐后一起继续，适合多线程协作场景。
3. **Generation 机制**：通过对象引用比较区分不同轮次，防止线程在重置后混淆。
4. **异常传播**：一个线程出问题，`breakBarrier()` 确保所有等待线程都能感知，避免无限等待。
5. **屏障动作**：支持在屏障打开时执行 `barrierCommand`，方便做汇总等操作。
6. **底层实现**：基于 `ReentrantLock + Condition`，与 `CountDownLatch` 的 AQS 共享模式不同。

理解 `CyclicBarrier` 的关键是理解 `dowait()` 方法的完整流程，以及 `Generation` 机制如何保证可重用的正确性。掌握了这些，在面对面试问题和实际开发中都能游刃有余。
