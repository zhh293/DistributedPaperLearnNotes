# LongAdder 与 LongAccumulator 深度解析

> 本文基于 JDK 8 / JDK 11 源码，面向有一定 Java 基础但不熟悉并发底层原理的读者，逐行拆解 Striped64、LongAdder、LongAccumulator 的设计思想与实现细节。

---

## 一、为什么需要 LongAdder

### 1.1 AtomicLong 的性能瓶颈

在讲 LongAdder 之前，我们先看看它的"前辈"——`AtomicLong`。

`AtomicLong` 内部维护了一个 `volatile long value`，所有的自增操作都通过 **CAS（Compare-And-Swap）** 完成：

```java
// AtomicLong 的 incrementAndGet() 简化逻辑
public final long incrementAndGet() {
    // unsafe.getAndAddLong 内部是一个 do-while 循环
    // 不断地尝试 CAS，直到成功
    return unsafe.getAndAddLong(this, valueOffset, 1L) + 1L;
}
```

在 **低并发** 环境下，CAS 通常一次就能成功，性能非常好。但在 **高并发** 环境下，问题就来了：

假设有 16 个线程同时对同一个 `AtomicLong` 执行 `incrementAndGet()`：

```
线程1: 读到 value=100, 尝试 CAS(100→101) —— 成功！
线程2: 读到 value=100, 尝试 CAS(100→101) —— 失败（value 已经是101了）
线程3: 读到 value=100, 尝试 CAS(100→101) —— 失败
线程4: 读到 value=100, 尝试 CAS(100→101) —— 失败
... 其他线程全部失败

线程2: 重新读 value=101, 尝试 CAS(101→102) —— 成功！
线程3: 重新读 value=101, 尝试 CAS(101→102) —— 失败
线程4: 重新读 value=101, 尝试 CAS(101→102) —— 失败
... 继续重试
```

看到问题了吗？**16 个线程竞争同一个变量，每一轮只有 1 个线程能成功，其余 15 个线程都在做无用功**——读取旧值、计算新值、尝试 CAS、发现失败、再来一遍。这就是所谓的 **CAS 空转（spinning）**，CPU 在不停地做计算和比较，但产出几乎为零。

更糟糕的是，由于 `value` 是 `volatile` 的，每次 CAS 成功后，其他所有核心的缓存都要被刷新（缓存行失效），这会产生大量的 **总线流量**，进一步拖慢整个系统。

### 1.2 核心思想：化整为零，分散热点

Doug Lea（`java.util.concurrent` 包的作者）想到了一个绝妙的办法：**既然一个变量太热了，那就拆成多个变量，让不同的线程去操作不同的变量，最后再把它们加起来。**

这个思想可以类比 `ConcurrentHashMap` 的分段锁设计：

```
AtomicLong 的做法（所有线程抢一把锁/一个变量）：
┌─────────────────────────┐
│       value = 100       │  ← 16个线程全部挤在这里 CAS
└─────────────────────────┘

LongAdder 的做法（分散到多个 Cell）：
┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
│base=5│ │cell0 │ │cell1 │ │cell2 │ │cell3 │
│      │ │ =30  │ │ =25  │ │ =20  │ │ =20  │
└──────┘ └──────┘ └──────┘ └──────┘ └──────┘
  ↑        ↑        ↑        ↑        ↑
 线程A    线程B    线程C    线程D    线程E
                                    (各自操作自己的那份)

最终值 = 5 + 30 + 25 + 20 + 20 = 100
```

每个线程根据自己的 **哈希值**（线程探针值）定位到某个 Cell，只对那个 Cell 做 CAS。这样，16 个线程可能分散到 4 个 Cell 上，每个 Cell 只有 4 个线程竞争，冲突概率大大降低。

### 1.3 设计目标：写性能优先，牺牲读的实时性

LongAdder 的设计哲学是：

- **写入（add）极致快**：通过分散热点，把 N 个线程对 1 个变量的竞争，变成 N 个线程对 M 个变量的竞争（M 最大等于 CPU 核心数），CAS 冲突概率从 (N-1)/N 降低到 (N/M-1)/(N/M)。
- **读取（sum）不保证精确**：因为要遍历 base + 所有 Cell 求和，遍历过程中其他线程可能还在修改，所以读到的是一个"近似值"。

这种权衡在很多场景下是可以接受的，比如：监控指标计数、QPS 统计、请求量统计——你不需要知道"此刻精确的值"，你只需要一个大致准确的累计值。

---

## 二、整体架构设计

### 2.1 继承关系

```
java.lang.Number
    └── java.util.concurrent.atomic.Striped64  (抽象类，核心实现)
            ├── LongAdder        (长整型累加器，只做加法)
            ├── LongAccumulator  (长整型累积器，可自定义函数)
            ├── DoubleAdder      (双精度累加器)
            └── DoubleAccumulator(双精度累积器)
```

**Striped64** 是整个体系的核心抽象类，名字中的 "Striped" 意为"条纹的、分段的"，"64"指的是 64 位数据类型。LongAdder 和 LongAccumulator 都是在 Striped64 的基础上，仅仅覆写了几个简单的方法而已——真正的"脏活累活"全在 Striped64 里。

### 2.2 核心数据结构

Striped64 内部维护了三个关键字段：

```java
abstract class Striped64 extends Number {

    // Cell 数组，懒初始化，长度总是 2 的幂
    transient volatile Cell[] cells;

    // 基础值：在没有竞争时直接 CAS 这个值（快速路径）
    transient volatile long base;

    // 自旋锁标记：0 表示未加锁，1 表示已加锁
    // 用于保护 cells 数组的初始化和扩容
    transient volatile int cellsBusy;
}
```

**最终值的计算公式**：

```
result = base + cells[0].value + cells[1].value + ... + cells[n-1].value
```

也就是说，LongAdder 把一个"逻辑上的值"拆分成了 `1 + N` 份：1 份在 `base` 里，N 份在 `Cell[]` 数组里。读取时需要把它们全部加起来。

### 2.3 整体运作流程概览

```
add(x) 被调用
    │
    ├── Cell[] 还没初始化？
    │       │
    │       ├── YES → 尝试 CAS base
    │       │           │
    │       │           ├── 成功 → 返回（快速路径，无竞争）
    │       │           │
    │       │           └── 失败 → 说明有竞争，调用 longAccumulate()
    │       │                      （在里面初始化 Cell[] 并累加）
    │       │
    │       └── NO → Cell[] 已存在
    │               │
    │               ├── 根据线程 probe 定位到 cells[index]
    │               │
    │               ├── 该位置为 null？→ 调用 longAccumulate() 创建新 Cell
    │               │
    │               └── 该位置不为 null？→ 尝试 CAS cell.value
    │                       │
    │                       ├── 成功 → 返回
    │                       │
    │                       └── 失败 → 调用 longAccumulate()
    │                                  （可能扩容、可能重新哈希）
```

---

## 三、Cell 内部类源码分析

### 3.1 Cell 源码

```java
// Striped64 的静态内部类
@sun.misc.Contended   // 关键注解！解决伪共享问题
static final class Cell {
    // volatile 保证可见性
    volatile long value;

    // 构造方法：传入初始值
    Cell(long x) { value = x; }

    // CAS 更新 value 的方法
    // 参数 cmp 是期望的旧值，val 是要设置的新值
    // 如果当前 value == cmp，则设置为 val 并返回 true
    // 否则返回 false（说明被其他线程抢先修改了）
    final boolean cas(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
    }

    // 以下是获取 Unsafe 实例和计算字段偏移量的样板代码
    private static final sun.misc.Unsafe UNSAFE;
    private static final long valueOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> ak = Cell.class;
            // 计算 value 字段在 Cell 对象中的内存偏移量
            // CAS 操作需要知道这个偏移量才能直接操作内存
            valueOffset = UNSAFE.objectFieldOffset(
                ak.getDeclaredField("value"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
```

Cell 本身非常简单：就是包装了一个 `volatile long value`，外加一个 CAS 方法。但它头上那个 `@Contended` 注解才是精髓。

### 3.2 伪共享问题与 @Contended 注解

要理解 `@Contended`，必须先理解 **伪共享（False Sharing）**。

#### 3.2.1 CPU 缓存行（Cache Line）基础

现代 CPU 不会逐字节地从内存读取数据，而是以 **缓存行（Cache Line）** 为单位批量读取。一个缓存行通常是 **64 字节**。

```
内存：
┌────────────────────────────────────────────────────────────────┐
│  byte0  byte1  byte2  ...  byte62  byte63 │ byte64 byte65 ... │
└───────────── 缓存行1 (64B) ──────────────┘└── 缓存行2 ──────┘
```

当 CPU 需要读取某个变量时，会把该变量所在的 **整个缓存行**（64 字节）一次性加载到 CPU 缓存（L1/L2/L3）中。如果接下来要读取的变量恰好在同一个缓存行里，就直接从缓存读取，速度极快。

#### 3.2.2 伪共享是怎么产生的

假设 Cell 数组中相邻的两个 Cell 对象碰巧在内存中紧挨着，它们的 `value` 字段落在了同一个 64 字节的缓存行上：

```
内存布局（未做填充）：
┌──────────────────────────── 缓存行 (64B) ────────────────────────────┐
│  Cell[0].value (8B)  │  Cell[1].value (8B)  │ 其他对象的数据 ... │
└──────────────────────────────────────────────────────────────────────┘

CPU核心0 操作 Cell[0].value
CPU核心1 操作 Cell[1].value
```

问题来了：

```
1. 核心0 加载缓存行（包含 Cell[0].value 和 Cell[1].value）到自己的 L1 缓存
2. 核心1 也加载同一个缓存行到自己的 L1 缓存
3. 核心0 修改了 Cell[0].value
   → 根据 MESI 缓存一致性协议，核心1 缓存中的这个缓存行被标记为 Invalid（失效）
4. 核心1 想读取 Cell[1].value
   → 发现缓存行已失效！只能重新从内存（或其他核心的缓存）加载
5. 核心1 修改了 Cell[1].value
   → 核心0 缓存中的缓存行又失效了！
6. 如此反复，两个核心的缓存不断互相作废...
```

这就是 **伪共享**：Cell[0] 和 Cell[1] 是两个完全独立的变量，逻辑上没有任何关系，但因为物理上挤在同一个缓存行里，一个被修改，另一个也跟着"遭殃"——缓存行被反复标记为失效，不得不反复从内存重新加载。这种现象被形象地称为 **"缓存行乒乓（Cache Line Ping-Pong）"**。

#### 3.2.3 @Contended 的解决方案

`@sun.misc.Contended` 注解告诉 JVM：**请在这个对象的前后各填充一段空白字节，确保这个对象独占一个（甚至多个）缓存行，不和其他对象共享缓存行。**

具体来说，JVM 会在 Cell 对象的内存布局前后各填充 **128 字节**（为什么是 128 而不是 64？因为某些 CPU 使用 128 字节的缓存行，或者有 prefetch 预取机制会加载相邻缓存行，所以填充 128 字节更保险）。

```
填充后的内存布局：
┌─── 128B 填充 ───┐┌─ Cell[0].value (8B) ─┐┌─── 128B 填充 ───┐
│  0000...0000     ││      value = 30      ││  0000...0000     │
└─────────────────┘└──────────────────────┘└─────────────────┘

┌─── 128B 填充 ───┐┌─ Cell[1].value (8B) ─┐┌─── 128B 填充 ───┐
│  0000...0000     ││      value = 25      ││  0000...0000     │
└─────────────────┘└──────────────────────┘└─────────────────┘
```

这样，每个 Cell 独占自己的缓存行，核心0 修改 Cell[0] 不会影响核心1 的 Cell[1] 缓存行，伪共享问题彻底解决。

**注意**：`@Contended` 默认只对 JDK 内部的类生效。如果你想在自己的代码中使用它，需要添加 JVM 参数 `-XX:-RestrictContended`。

---

## 四、Striped64.longAccumulate() 源码逐行深度分析

这是 **整个 LongAdder 体系中最核心、最复杂的方法**。当 `add()` 中的快速路径（CAS base 或 CAS cell）失败时，就会调用这个方法。它负责 Cell 数组的初始化、新 Cell 的创建、Cell 数组的扩容、以及累加重试。

### 4.1 方法签名

```java
/**
 * @param x        要累加的值
 * @param fn       累加函数（LongAdder 传 null 表示用加法，LongAccumulator 传自定义函数）
 * @param wasUncontended  调用前最后一次 CAS 是否成功（false 表示竞争激烈，需要重新哈希）
 */
final void longAccumulate(long x, LongBinaryOperator fn,
                          boolean wasUncontended) {
```

### 4.2 完整源码逐行注释

下面是完整源码（基于 JDK 8），我会在每一行/每一段后面写上详细注释：

```java
final void longAccumulate(long x, LongBinaryOperator fn,
                          boolean wasUncontended) {
    int h;  // 当前线程的探针值（probe），用来决定映射到哪个 Cell

    // ======== 第一步：获取当前线程的探针值 ========
    // 探针值（probe）类似于线程的"随机哈希"，但不同于 hashCode()
    // 它存储在 Thread 对象内部，可以被修改（用于重新哈希）
    if ((h = getProbe()) == 0) {
        // 如果 probe == 0，说明这个线程还没初始化过探针值
        // 调用 ThreadLocalRandom 来初始化
        ThreadLocalRandom.current(); // 强制初始化
        h = getProbe();              // 重新获取初始化后的探针值
        wasUncontended = true;       // 首次进入，认为还没有竞争
        // 为什么 probe == 0 时要特殊处理？
        // 因为 probe == 0 的线程都会映射到 cells[0]，造成无谓的竞争
        // 所以要先给它一个随机的 probe 值
    }

    // collide 标记：上一轮循环中 CAS 是否失败了
    // 如果连续两次 CAS 都失败（collide == true），才会触发扩容
    boolean collide = false;

    // ======== 主循环：不断重试直到成功 ========
    for (;;) {
        Cell[] as;   // cells 数组的本地引用
        Cell a;      // 当前线程对应的那个 Cell
        int n;       // cells 数组当前长度
        long v;      // 用于 CAS 操作的期望旧值

        // ==========================================
        // 分支一：cells 数组已经初始化了
        // ==========================================
        if ((as = cells) != null && (n = as.length) > 0) {

            // —— 情况 1.1：当前线程映射到的槽位是空的（还没有 Cell）——
            if ((a = as[(n - 1) & h]) == null) {
                // (n - 1) & h 等价于 h % n（因为 n 是 2 的幂）
                // 这就是用探针值对数组长度取模，定位到某个槽位

                if (cellsBusy == 0) {       // 检查有没有其他线程在操作数组
                    Cell r = new Cell(x);   // 提前创建 Cell 对象（乐观策略）
                    if (cellsBusy == 0 && casCellsBusy()) {
                        // 再次检查 cellsBusy 并尝试加锁（CAS 0→1）
                        // 双重检查：第一次检查避免无意义的 CAS，第二次 CAS 才是真正的加锁
                        boolean created = false;
                        try {
                            Cell[] rs; int m, j;
                            // 加锁后再次确认：数组还在、槽位仍然为空
                            // 为什么要再确认？因为在我们加锁之前，
                            // 可能有其他线程已经在同一个位置创建了 Cell
                            if ((rs = cells) != null &&
                                (m = rs.length) > 0 &&
                                rs[j = (m - 1) & h] == null) {
                                rs[j] = r;       // 将新创建的 Cell 放入数组
                                created = true;  // 标记创建成功
                            }
                        } finally {
                            cellsBusy = 0; // 释放锁（直接赋值为0，因为只有我们持有锁）
                        }
                        if (created)
                            break;  // 创建成功，累加值已经包含在新 Cell 的初始值中，退出循环
                        continue;   // 创建失败（其他线程抢先创建了），重新循环
                    }
                }
                collide = false; // 走到这里说明加锁失败了，先把 collide 重置
                // 注意：这里没有 break 也没有 continue
                // 会掉到最后面的 advanceProbe() 重新哈希
            }

            // —— 情况 1.2：wasUncontended == false ——
            // 说明调用者（add方法）最后一次对 Cell 的 CAS 失败了
            // 这意味着竞争激烈，我们需要重新哈希到另一个 Cell
            else if (!wasUncontended)
                wasUncontended = true;
                // 仅仅设置 wasUncontended = true，然后掉到底部的 advanceProbe()
                // 重新哈希之后再试，不在本轮 CAS（避免在竞争激烈的位置反复 CAS）

            // —— 情况 1.3：尝试对当前 Cell 做 CAS ——
            else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                         fn.applyAsLong(v, x))))
                break;
                // fn == null 时就是 v + x（LongAdder 的加法）
                // fn != null 时调用自定义函数（LongAccumulator）
                // CAS 成功就直接退出循环，任务完成！

            // —— 情况 1.4：数组长度已经 >= CPU 核心数，或者数组刚被别的线程扩容了 ——
            else if (n >= NCPU || cells != as)
                collide = false;
                // n >= NCPU：数组长度已经达到了 CPU 核心数
                //   再扩容也没意义了（因为同一时刻最多只有 NCPU 个线程在运行）
                // cells != as：数组引用变了，说明刚被其他线程扩容过
                //   需要用新数组重试，不应该再触发扩容
                // 这两种情况都设置 collide = false，阻止下面的扩容逻辑

            // —— 情况 1.5：CAS 失败了，而且上面没有阻止扩容 ——
            else if (!collide)
                collide = true;
                // 第一次 CAS 失败：把 collide 设为 true，先不急着扩容
                // 给一次重新哈希的机会（也许换个 Cell 就不冲突了）
                // 如果下一轮还是失败（collide 已经是 true），就会进入情况 1.6 扩容

            // —— 情况 1.6：连续 CAS 失败，触发扩容 ——
            else if (cellsBusy == 0 && casCellsBusy()) {
                // 先加锁（CAS cellsBusy 0→1）
                try {
                    if (cells == as) {  // 再次确认数组没被其他线程扩容过
                        Cell[] rs = new Cell[n << 1]; // 容量翻倍（n << 1 等于 n * 2）
                        for (int i = 0; i < n; ++i)
                            rs[i] = as[i];  // 把旧数组的 Cell 引用复制到新数组
                            // 注意：这里只是复制引用，不是 deep copy
                            // 旧 Cell 对象会被新数组继续使用
                        cells = rs; // 将 cells 指向新数组
                    }
                } finally {
                    cellsBusy = 0; // 释放锁
                }
                collide = false; // 扩容完成，重置碰撞标记
                continue;        // 用新的更大数组重新尝试，跳过底部的 advanceProbe()
                // 为什么跳过 advanceProbe？
                // 因为扩容后 (n-1)&h 的结果会不同（n变大了），
                // 线程会自然地分散到新的槽位，不需要重新哈希 h 本身
            }

            // ======== 底部：重新哈希探针值 ========
            // 走到这里说明当前的哈希位置不太好（冲突了），换一个位置试试
            h = advanceProbe(h);
            // advanceProbe 是一个异或移位的伪随机函数（xorshift）
            // 它会修改线程的 probe 值，使得下一轮映射到不同的 Cell
        }

        // ==========================================
        // 分支二：cells 数组还没初始化，尝试初始化
        // ==========================================
        else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
            // 三个条件缺一不可：
            // 1. cellsBusy == 0：没有其他线程在操作数组
            // 2. cells == as：确认 cells 确实还是 null（防止 ABA）
            // 3. casCellsBusy()：CAS 加锁成功
            boolean init = false;
            try {
                if (cells == as) {  // 加锁后再次确认（经典的 Double-Check）
                    Cell[] rs = new Cell[2]; // 初始容量为 2
                    rs[h & 1] = new Cell(x);
                    // h & 1 就是 h % 2，根据探针值决定把新 Cell 放在 [0] 还是 [1]
                    // 新 Cell 的初始值就是要累加的 x
                    cells = rs;     // 发布新数组
                    init = true;    // 标记初始化成功
                }
            } finally {
                cellsBusy = 0; // 释放锁
            }
            if (init)
                break;  // 初始化成功，值已包含在新 Cell 中，退出循环
        }

        // ==========================================
        // 分支三：兜底——cells 正在被其他线程初始化/扩容，我等不及了
        //        直接尝试 CAS base
        // ==========================================
        else if (casBase(v = base, ((fn == null) ? v + x :
                                    fn.applyAsLong(v, x))))
            break;
            // 如果 CAS base 成功了，也算完成任务，退出
            // 这是最后的兜底手段：在 cells 不可用期间，退回到类似 AtomicLong 的方式

    } // end for(;;)
}
```

### 4.3 longAccumulate 流程总结

为了帮助理解，我把上面的逻辑梳理成一个清晰的决策树：

```
进入 longAccumulate()
│
├── 线程 probe == 0？ → 初始化 probe
│
└── 进入无限循环 for(;;)
     │
     ├── 【分支一】cells 已存在且长度 > 0
     │    │
     │    ├── 1.1 目标槽位 == null
     │    │    └── 加锁 → 创建新 Cell 放入 → 成功则 break
     │    │
     │    ├── 1.2 wasUncontended == false（上一次 CAS 失败）
     │    │    └── 设 wasUncontended = true → 重新哈希（底部 advanceProbe）
     │    │
     │    ├── 1.3 CAS cell.value → 成功则 break
     │    │
     │    ├── 1.4 n >= NCPU 或 cells 被扩容了
     │    │    └── collide = false（不允许扩容）→ 重新哈希
     │    │
     │    ├── 1.5 !collide（第一次碰撞）
     │    │    └── collide = true → 重新哈希（给一次机会）
     │    │
     │    └── 1.6 collide == true（连续碰撞）
     │         └── 加锁 → 数组容量翻倍 → continue
     │
     ├── 【分支二】cells 未初始化
     │    └── 加锁 → 创建 Cell[2] → 放入第一个 Cell → break
     │
     └── 【分支三】兜底
          └── CAS base → 成功则 break
```

### 4.4 cellsBusy 字段详解

```java
transient volatile int cellsBusy;
```

cellsBusy 是一个极简的 **自旋锁**，只有两个值：0（未加锁）和 1（已加锁）。

加锁方式：

```java
// CAS 将 cellsBusy 从 0 改为 1
final boolean casCellsBusy() {
    return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
}
```

释放方式：

```java
cellsBusy = 0; // 直接赋值
// 因为只有持锁线程才会写 0，所以不需要 CAS
// volatile 保证了可见性
```

它只在三个地方使用：

1. 初始化 cells 数组时加锁
2. 创建新 Cell 放入空槽位时加锁
3. 扩容 cells 数组时加锁

注意：对 Cell 内 value 的 CAS 操作 **不需要加锁**，cellsBusy 只保护 cells 数组结构本身的变更。

### 4.5 为什么 cells 数组长度上限是 CPU 核心数？

```java
// NCPU 在 Striped64 中的定义
static final int NCPU = Runtime.getRuntime().availableProcessors();

// 在 longAccumulate 中的判断
else if (n >= NCPU || cells != as)
    collide = false;  // 不再扩容
```

原因很简单：**操作系统同一时刻最多只能并行运行 NCPU 个线程**。即使你有 100 个线程，在 8 核 CPU 上，同一瞬间最多只有 8 个线程在真正运行。所以 Cell 数组长度达到 8 就够了——每个运行中的线程最多对应一个 Cell。

如果数组更大，只会浪费内存（每个 Cell 因为 `@Contended` 填充后要占用约 256+ 字节），而不会提升性能。

### 4.6 advanceProbe —— 重新哈希

```java
static final int advanceProbe(int probe) {
    probe ^= probe << 13;   // xorshift 伪随机算法
    probe ^= probe >>> 17;
    probe ^= probe << 5;
    UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
    return probe;
}
```

这是一个 **xorshift** 伪随机数生成器，通过三次异或移位操作，把当前的 probe 值变换成一个新值。这样线程下一轮循环就会映射到不同的 Cell 槽位，避免继续在同一个位置碰撞。

---

## 五、LongAdder 核心方法源码分析

### 5.1 add(long x)

```java
public void add(long x) {
    Cell[] as;    // cells 数组的本地引用
    long b, v;    // b: base 的值; v: Cell 中的值
    int m;        // cells 数组长度减 1（用于取模）
    Cell a;       // 当前线程映射到的 Cell

    // 第一个条件：(as = cells) != null
    //   如果 cells 数组已经存在（说明之前发生过竞争），跳过 CAS base，直接操作 Cell
    //   如果 cells 为 null（还没发生过竞争），尝试 CAS base
    //
    // 第二个条件（只在 cells 为 null 时才会执行）：!casBase(b = base, b + x)
    //   尝试 CAS base：base = base + x
    //   如果成功（返回 true），取反为 false，整个 if 为 false → 方法直接返回（快速路径）
    //   如果失败（返回 false），取反为 true，进入 if 体内
    if ((as = cells) != null || !casBase(b = base, b + x)) {
        // 走到这里，说明：
        // 1. cells 已存在（之前有竞争），或者
        // 2. CAS base 失败了（现在有竞争）

        boolean uncontended = true; // 标记是否"无竞争"

        // 下面一连串的短路条件判断：
        if (as == null                            // cells 为 null（还没初始化）
            || (m = as.length - 1) < 0            // cells 长度为 0（理论上不会出现，防御性编程）
            || (a = as[getProbe() & m]) == null    // 当前线程映射到的槽位为空（Cell 还没创建）
            || !(uncontended = a.cas(v = a.value, v + x))) // 对 Cell 做 CAS
            //   ^^^^^^^^^^^^^^^^ 这里有个小技巧：
            //   如果前面三个条件都为 false（即 cells 存在、有长度、槽位不为空）
            //   才会执行到第四个条件：CAS Cell
            //   CAS 成功 → uncontended = true → 取反为 false → 整个 if 为 false → 返回
            //   CAS 失败 → uncontended = false → 取反为 true → 进入 longAccumulate
        {
            // 所有快速路径都失败了，调用核心方法
            longAccumulate(x, null, uncontended);
            // null 表示使用加法（LongAdder 专用）
            // uncontended 告诉 longAccumulate 最后一次 CAS 是否成功
        }
    }
}
```

**add() 方法的精妙之处** 在于它设计了多层"快速路径"：

1. **第一层快速路径**：如果没有竞争（cells 为 null），直接 CAS base，一条指令搞定。
2. **第二层快速路径**：如果有竞争但目标 Cell 存在，直接 CAS Cell。
3. **兜底**：两层快速路径都失败了，才进入复杂的 `longAccumulate()` 方法。

在低并发场景下，大多数调用都走第一层快速路径，性能和 AtomicLong 几乎一样。在高并发场景下，线程分散到不同的 Cell 上，第二层快速路径的 CAS 成功率很高，也很少需要进入 longAccumulate。

### 5.2 increment() 和 decrement()

```java
public void increment() {
    add(1L);  // 就是 +1，没有任何额外逻辑
}

public void decrement() {
    add(-1L); // 就是 -1
}
```

### 5.3 sum()

```java
public long sum() {
    Cell[] as = cells;
    Cell a;
    long sum = base;  // 从 base 开始累加
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null)  // 跳过空槽位
                sum += a.value;       // 加上每个 Cell 的值
        }
    }
    return sum;
}
```

#### 为什么 sum() 不精确？

因为 **遍历不是原子操作**。在遍历过程中：

```
时刻 T1: sum 已经累加了 base + cells[0] + cells[1]
时刻 T2: 另一个线程修改了 cells[0].value（+10）
时刻 T3: sum 继续累加 cells[2] + cells[3]
```

最终 sum 的结果没有包含时刻 T2 的那次修改（或者包含了一半修改），所以读到的是一个 **"快照"** 而非精确值。

**但这在大多数统计场景下完全可以接受**——你统计一个 API 的调用次数，差个几次完全不影响决策。

### 5.4 reset()

```java
public void reset() {
    Cell[] as = cells; Cell a;
    base = 0L;  // 重置 base
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null)
                a.value = 0L;  // 重置每个 Cell
        }
    }
}
```

### 5.5 sumThenReset()

```java
public long sumThenReset() {
    Cell[] as = cells; Cell a;
    long sum = base;
    base = 0L;  // 读取并重置 base
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null) {
                sum += a.value;
                a.value = 0L;  // 读取并重置每个 Cell
            }
        }
    }
    return sum;
}
```

注意：`sumThenReset()` 也不是原子操作，在重置期间如果有其他线程在 add，可能会丢失一些更新。

---

## 六、LongAccumulator 源码分析

### 6.1 与 LongAdder 的关系

`LongAccumulator` 是 `LongAdder` 的"泛化版本"：

| 特性 | LongAdder | LongAccumulator |
|------|-----------|-----------------|
| 累加操作 | 固定为加法（+） | 可自定义任意二元函数 |
| 初始值 | 固定为 0 | 可自定义 |
| base 含义 | 累加的一部分 | 初始值 identity |
| 典型用途 | 计数 | 求最大值、最小值、自定义聚合 |

实际上，`LongAdder` 在逻辑上等价于 `new LongAccumulator(Long::sum, 0L)`。

### 6.2 核心字段

```java
public class LongAccumulator extends Striped64 implements Serializable {

    // 自定义的累加函数
    private final LongBinaryOperator function;

    // 初始值（identity element）
    // 必须满足：function.applyAsLong(identity, x) == x
    // 比如：加法的 identity 是 0，因为 0 + x == x
    //       Math::max 的 identity 是 Long.MIN_VALUE，因为 max(MIN_VALUE, x) == x
    private final long identity;

    public LongAccumulator(LongBinaryOperator accumulatorFunction,
                           long identity) {
        this.function = accumulatorFunction;
        base = this.identity = identity;
        // base 被初始化为 identity
    }
}
```

### 6.3 accumulate(long x) —— 核心累加方法

```java
public void accumulate(long x) {
    Cell[] as; long b, v, r; int m; Cell a;

    // 和 LongAdder.add() 结构几乎一样，只是把 "b + x" 换成了 "function.applyAsLong(b, x)"
    if ((as = cells) != null ||
        (r = function.applyAsLong(b = base, x)) != b  // 计算新值
         && !casBase(b, r)) {                          // 尝试 CAS base
        // 注意这里多了一个条件：r != b
        // 如果函数计算结果和原值相同（比如 max(100, 50) == 100），就不需要 CAS 了
        // 这是一个优化：避免无意义的 CAS 操作

        boolean uncontended = true;
        if (as == null || (m = as.length - 1) < 0 ||
            (a = as[getProbe() & m]) == null ||
            !(uncontended =
              (r = function.applyAsLong(v = a.value, x)) != v // 同样的优化
               || a.cas(v, r)))
            longAccumulate(x, function, uncontended);
            // 注意这里传的是 function（而非 null）
            // longAccumulate 内部会用这个 function 来替代加法
    }
}
```

### 6.4 get() —— 获取累积结果

```java
public long get() {
    Cell[] as = cells; Cell a;
    long result = base;  // 从 base 开始
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null)
                result = function.applyAsLong(result, a.value);
                // 不是简单相加，而是用自定义函数来 reduce
                // 比如 function 是 Math::max 时：
                //   result = max(result, cells[0])
                //   result = max(result, cells[1])
                //   ...
        }
    }
    return result;
}
```

### 6.5 使用示例

#### 示例1：求最大值

```java
// 创建一个求最大值的累积器
// identity 是 Long.MIN_VALUE，这样任何 x 都会比它大
LongAccumulator maxAccumulator = new LongAccumulator(Math::max, Long.MIN_VALUE);

// 多个线程并发地提交值
ExecutorService pool = Executors.newFixedThreadPool(8);
for (int i = 0; i < 100; i++) {
    final int val = i;
    pool.submit(() -> maxAccumulator.accumulate(val));
}
pool.shutdown();
pool.awaitTermination(1, TimeUnit.SECONDS);

System.out.println(maxAccumulator.get()); // 输出 99
```

#### 示例2：求最小值

```java
LongAccumulator minAccumulator = new LongAccumulator(Math::min, Long.MAX_VALUE);
// 多线程提交后...
System.out.println(minAccumulator.get()); // 输出最小值
```

#### 示例3：自定义聚合——求乘积

```java
// 注意：乘法的 identity 是 1（因为 1 * x == x）
LongAccumulator product = new LongAccumulator((a, b) -> a * b, 1L);
product.accumulate(3);
product.accumulate(4);
product.accumulate(5);
System.out.println(product.get()); // 输出 60 (= 1 * 3 * 4 * 5)
```

**注意事项**：自定义函数必须满足 **交换律** 和 **结合律**，因为多个 Cell 的 reduce 顺序是不确定的。加法、乘法、Math::max、Math::min 都满足这个条件，但减法、除法不行。

---

## 七、与 AtomicLong 的性能对比

### 7.1 低竞争场景

在只有 1-2 个线程的情况下：

- **AtomicLong**：直接 CAS `value`，几乎不会失败，性能很好。
- **LongAdder**：先尝试 CAS `base`（第一层快速路径），成功则直接返回。由于没有竞争，CAS 基本都能成功，性能和 AtomicLong 差不多，甚至可能略差一点（因为有额外的条件判断）。

结论：**低竞争下两者性能接近，AtomicLong 可能略优。**

### 7.2 高竞争场景

在 16+ 线程的情况下：

- **AtomicLong**：所有线程争抢同一个变量，CAS 失败率极高，大量时间浪费在重试上。
- **LongAdder**：线程分散到不同的 Cell 上（假设 8 核 CPU，最多 8 个 Cell），每个 Cell 平均只有 2 个线程竞争，CAS 成功率大幅提升。

典型的 benchmark 结果（JMH 测试，16 线程自增 1 亿次）：

```
AtomicLong.incrementAndGet():     ~8.5 秒
LongAdder.increment():            ~0.9 秒
```

在这个场景下，**LongAdder 快了大约 9 倍**。线程数越多，差距越大。

### 7.3 选型建议

**选 LongAdder 的场景**：

- 高并发写入、低频读取的计数场景，比如监控指标（QPS、请求量、错误计数）
- 只需要最终的统计总量，不需要实时精确值
- 类似 Prometheus Counter 的场景

**选 AtomicLong 的场景**：

- 需要精确读取当前值，比如序列号生成器（每次 get() 必须准确）
- 需要 `compareAndSet()` 等条件更新语义
- 并发度不高（1-4 个线程），用 AtomicLong 更简单直接

**选 LongAccumulator 的场景**：

- 需要自定义聚合逻辑（求最值、求乘积等）
- 高并发场景下的聚合计算

### 7.4 一个简单的性能对比代码

```java
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.CountDownLatch;

public class PerformanceCompare {
    private static final int THREAD_COUNT = 16;
    private static final int INCREMENT_PER_THREAD = 10_000_000;

    public static void main(String[] args) throws InterruptedException {
        // 测试 AtomicLong
        AtomicLong atomicLong = new AtomicLong(0);
        long start1 = System.currentTimeMillis();
        CountDownLatch latch1 = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < INCREMENT_PER_THREAD; j++) {
                    atomicLong.incrementAndGet();
                }
                latch1.countDown();
            }).start();
        }
        latch1.await();
        long time1 = System.currentTimeMillis() - start1;
        System.out.println("AtomicLong: " + time1 + "ms, value=" + atomicLong.get());

        // 测试 LongAdder
        LongAdder longAdder = new LongAdder();
        long start2 = System.currentTimeMillis();
        CountDownLatch latch2 = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < INCREMENT_PER_THREAD; j++) {
                    longAdder.increment();
                }
                latch2.countDown();
            }).start();
        }
        latch2.await();
        long time2 = System.currentTimeMillis() - start2;
        System.out.println("LongAdder:  " + time2 + "ms, value=" + longAdder.sum());
    }
}
```

---

## 八、伪共享问题深入讲解

### 8.1 CPU 缓存架构回顾

现代多核 CPU 的缓存层次结构如下：

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  核心 0   │  │  核心 1   │  │  核心 2   │  │  核心 3   │
│ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │
│ │L1 32K│ │  │ │L1 32K│ │  │ │L1 32K│ │  │ │L1 32K│ │
│ └──────┘ │  │ └──────┘ │  │ └──────┘ │  │ └──────┘ │
│ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │
│ │L2256K│ │  │ │L2256K│ │  │ │L2256K│ │  │ │L2256K│ │
│ └──────┘ │  │ └──────┘ │  │ └──────┘ │  │ └──────┘ │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │
     └─────────────┴──────┬──────┴─────────────┘
                    ┌─────┴─────┐
                    │ L3 共享    │
                    │ 8-30MB    │
                    └─────┬─────┘
                          │
                    ┌─────┴─────┐
                    │  主内存     │
                    │  (DRAM)   │
                    └───────────┘
```

各级缓存的访问延迟对比：

- **L1 缓存**：约 1 纳秒（每个核心独有）
- **L2 缓存**：约 3-5 纳秒（每个核心独有）
- **L3 缓存**：约 10-20 纳秒（所有核心共享）
- **主内存**：约 60-100 纳秒

可以看到，L1 比主内存快了约 100 倍。所以，**能不能命中缓存，对性能影响巨大**。

### 8.2 缓存行（Cache Line）

CPU 与缓存之间传输数据的最小单位是 **缓存行**，通常为 **64 字节**。当 CPU 需要读取内存中的一个 long 变量（8 字节），它不是只读这 8 字节，而是把包含这 8 字节的整个 64 字节缓存行都加载进来。

这意味着：如果两个变量在内存中紧挨着（相距不超过 64 字节），它们很可能在同一个缓存行里。

### 8.3 MESI 协议下的伪共享过程

MESI 是最常见的缓存一致性协议，它为每个缓存行维护四种状态：

- **M (Modified)**：已修改，当前核心独占，和主内存不一致
- **E (Exclusive)**：独占，只在当前核心缓存中，和主内存一致
- **S (Shared)**：共享，可能在多个核心缓存中，和主内存一致
- **I (Invalid)**：失效，缓存行无效

伪共享的产生过程如下图所示：

```
初始状态：Cell[0].value 和 Cell[1].value 在同一个缓存行

步骤1: 核心0 读取 Cell[0].value
        → 加载整个缓存行到核心0的 L1 缓存
        → 缓存行状态：核心0=E(独占)

        核心0 L1: [Cell[0].value=10 | Cell[1].value=20] 状态=E
        核心1 L1: (空)

步骤2: 核心1 读取 Cell[1].value
        → 加载同一个缓存行到核心1的 L1 缓存
        → 两个核心都有这个缓存行，状态变为 S(共享)

        核心0 L1: [Cell[0].value=10 | Cell[1].value=20] 状态=S
        核心1 L1: [Cell[0].value=10 | Cell[1].value=20] 状态=S

步骤3: 核心0 修改 Cell[0].value = 11
        → 核心0 的缓存行状态变为 M(已修改)
        → 核心0 发出 Invalidate 消息
        → 核心1 的缓存行状态变为 I(失效) !!!

        核心0 L1: [Cell[0].value=11 | Cell[1].value=20] 状态=M
        核心1 L1: [Cell[0].value=10 | Cell[1].value=20] 状态=I  ← 失效了！

步骤4: 核心1 想要修改 Cell[1].value = 21
        → 发现缓存行已经失效（I 状态）
        → 必须重新从核心0（或主内存）加载最新的缓存行
        → 核心0 的缓存行写回主内存，状态变为 I
        → 核心1 加载新缓存行，修改后状态变为 M

        核心0 L1: [Cell[0].value=11 | Cell[1].value=20] 状态=I  ← 又失效了！
        核心1 L1: [Cell[0].value=11 | Cell[1].value=21] 状态=M

步骤5: 核心0 想要修改 Cell[0].value = 12
        → 缓存行失效，又要重新加载...

如此反复，形成"缓存行乒乓"！
```

**每次缓存行失效，都要花费约 60-100 纳秒从主内存或其他核心重新加载，这比 L1 缓存命中慢了 100 倍。** 在高频率的 CAS 操作下，这种开销是致命的。

### 8.4 @Contended 注解的工作原理

`@sun.misc.Contended`（JDK 9+ 移到了 `jdk.internal.vm.annotation.Contended`）告诉 JVM 的内存分配器：**在这个类的对象前后各填充足够的空白字节，使其独占缓存行。**

未填充时的 Cell 对象内存布局：

```
┌──────────────────┐
│ Object Header    │  12 字节 (64位JVM, 压缩指针开启)
├──────────────────┤
│ value (long)     │   8 字节
├──────────────────┤
│ padding          │   4 字节 (对齐到 8 的倍数)
└──────────────────┘
  总计: 24 字节

两个 Cell 可能落在同一个 64 字节的缓存行里
```

使用 @Contended 后的布局：

```
┌──────────────────┐
│ 前置填充          │ 128 字节
├──────────────────┤
│ Object Header    │  12 字节
├──────────────────┤
│ value (long)     │   8 字节
├──────────────────┤
│ 后置填充          │ 128 字节
└──────────────────┘
  总计: 约 276 字节

每个 Cell 独占多个缓存行，绝不会和其他 Cell 共享
```

### 8.5 其他解决伪共享的方式

除了 `@Contended`，还有一种经典的手动填充方式，Disruptor 框架就是这么做的：

```java
// Disruptor 中的手动缓存行填充
class LhsPadding {
    // 7 个 long = 56 字节的填充
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class Value extends LhsPadding {
    protected volatile long value; // 8 字节
}

class RhsPadding extends Value {
    // 7 个 long = 56 字节的填充
    protected long p9, p10, p11, p12, p13, p14, p15;
}

// 前面 56 字节 + value 8 字节 + 后面 56 字节 = 120 字节
// 远超一个 64 字节缓存行，保证 value 独占缓存行
```

这种方式不依赖 JVM 特定注解，但代码冗长且容易被 JIT 编译器优化掉（JIT 可能认为那些填充字段没有被使用，从而消除它们）。`@Contended` 是 JVM 层面的支持，更可靠。

### 8.6 JVM 参数

如果你想在自己的代码中使用 `@Contended`，需要添加 JVM 参数：

```
-XX:-RestrictContended
```

默认情况下，`RestrictContended` 是开启的（`-XX:+RestrictContended`），这意味着 `@Contended` 只对 JDK 内部类生效。关闭此限制后，用户代码也可以使用。

另一个相关参数：

```
-XX:ContendedPaddingWidth=128
```

可以控制填充的字节数，默认是 128。

---

## 九、在框架中的应用

### 9.1 ConcurrentHashMap 的 size 计算

JDK 8 的 `ConcurrentHashMap` 在统计元素个数时，采用了和 LongAdder **完全相同的思想**。它内部有一个 `counterCells` 数组和一个 `baseCount` 字段：

```java
// ConcurrentHashMap 中的相关字段（简化）
private transient volatile long baseCount;
private transient volatile CounterCell[] counterCells;

// CounterCell 和 Striped64.Cell 几乎一模一样
@sun.misc.Contended
static final class CounterCell {
    volatile long value;
    CounterCell(long x) { value = x; }
}
```

当执行 `put()` 操作时，计数逻辑和 `LongAdder.add()` 如出一辙——先 CAS `baseCount`，失败则分散到 `CounterCell` 上。

当调用 `size()` 时，也是 `baseCount + sum(counterCells)` 遍历求和——同样不保证精确。

```java
// ConcurrentHashMap.size() 简化逻辑
public int size() {
    long n = sumCount();  // baseCount + 所有 counterCell 的 value
    return ((n < 0L) ? 0 :
            (n > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE :
            (int)n);
}
```

这就是为什么 `ConcurrentHashMap.size()` 的文档说"返回的是一个估计值"——底层原理和 `LongAdder.sum()` 完全一样。

### 9.2 Striped64 家族的其他成员

**DoubleAdder**：double 版本的 LongAdder。由于 CAS 不直接支持 double 类型，它内部用 `Double.doubleToRawLongBits()` 和 `Double.longBitsToDouble()` 把 double 转为 long 来做 CAS。

```java
// DoubleAdder.add() 核心逻辑
public void add(double x) {
    // 把 double 转成 long 来存储和 CAS
    long v = Double.doubleToRawLongBits(value);
    long newBits = Double.doubleToRawLongBits(
        Double.longBitsToDouble(v) + x);
    cas(v, newBits);
}
```

**DoubleAccumulator**：double 版本的 LongAccumulator，支持自定义函数。

### 9.3 监控框架中的应用

**Prometheus Java Client**（simpleclient）的 Counter 实现就使用了 `DoubleAdder` 来累计指标值，因为 Counter 天然就是"只增不减的累计计数器"，完美匹配 Adder 的设计理念：高并发写入、偶尔读取。

**Micrometer**（Spring Boot Actuator 的底层指标库）同样大量使用 LongAdder/DoubleAdder 来实现计数器和分布摘要。

---

## 十、常见面试问题

### Q1: LongAdder 的原理是什么？为什么比 AtomicLong 快？

**答**：LongAdder 的核心思想是 **"化整为零，分散热点"**。AtomicLong 让所有线程对同一个 volatile long 变量做 CAS，高并发下大量 CAS 失败导致空转。LongAdder 维护了一个 `base` 变量和一个 `Cell[]` 数组，不同线程根据自身的哈希值（probe）定位到不同的 Cell，各自对自己的 Cell 做 CAS。这样把 N 个线程对 1 个变量的竞争，分散成了 N 个线程对 M 个变量的竞争（M 最大等于 CPU 核心数），CAS 冲突概率大幅降低。最终值通过 `base + sum(cells)` 汇总。代价是 `sum()` 不保证实时精确。

### Q2: 什么是伪共享？@Contended 是怎么解决的？

**答**：伪共享是指逻辑上无关的变量因物理上落在同一个 CPU 缓存行（通常 64 字节）中，一个变量被修改导致整个缓存行在 MESI 协议下失效，进而使得另一个变量的读取也不得不重新从内存加载的现象。这会严重拖慢多核并行性能。

`@Contended` 注解指示 JVM 在对象的内存布局前后各填充 128 字节的空白区域，确保该对象（比如 Cell）独占一个或多个缓存行，不与其他对象共享，从根本上消除了伪共享。

### Q3: LongAdder 的 sum() 为什么不精确？

**答**：`sum()` 方法需要遍历 `base` 和所有 `cells[i].value` 并累加。这个遍历过程不是原子的——在遍历期间，其他线程可能正在对某些 Cell 执行 `add()` 操作。比如 sum 已经读过 cells[0] 了，之后另一个线程又修改了 cells[0]，这次修改就不会被计入 sum 的结果。所以 `sum()` 返回的是一个"近似值"或"快照"，适用于统计计数等不要求实时精确的场景。

### Q4: Cell 数组为什么最大等于 CPU 核心数？

**答**：因为操作系统在同一时刻最多只能并行运行 NCPU（CPU 核心数）个线程。即使创建了 100 个线程，在 8 核 CPU 上同一瞬间最多只有 8 个线程真正在执行。所以 Cell 数组大于 NCPU 不会减少竞争（因为同时竞争的线程不会超过 NCPU），反而浪费内存（每个 Cell 因为 `@Contended` 填充后要占用约 256+ 字节）。

### Q5: LongAdder 和 LongAccumulator 的区别？

**答**：LongAdder 是 LongAccumulator 的特殊情况。LongAdder 只支持加法运算，初始值固定为 0；LongAccumulator 支持通过 `LongBinaryOperator` 自定义任意二元运算（如 Math::max、Math::min），并且可以自定义初始值。在逻辑上，`new LongAdder()` 等价于 `new LongAccumulator(Long::sum, 0L)`。选型上，如果只是计数用 LongAdder 更简洁；如果需要求最值或其他聚合操作，用 LongAccumulator。需要注意 LongAccumulator 的自定义函数必须满足交换律和结合律。

### Q6: longAccumulate 方法中，为什么要先检查 wasUncontended，而不是直接 CAS？

**答**：当 `wasUncontended == false` 时，意味着调用者在进入 longAccumulate 之前，对当前 Cell 的 CAS 刚刚失败了。如果此时不重新哈希就直接再次 CAS 同一个 Cell，很可能还是失败（因为那个位置竞争激烈）。所以 longAccumulate 先设 `wasUncontended = true`，然后跳到底部的 `advanceProbe()` 重新哈希，下一轮循环去尝试一个不同的 Cell。这是一种避免在热点位置反复碰撞的优化策略。

### Q7: Cells 数组的初始大小为什么是 2？

**答**：初始大小为 2 是一个权衡选择。设为 1 没有意义（和只有 base 没区别），设太大浪费内存。初始为 2 是最小的有效分散——至少可以把线程分到两个 Cell 上。如果之后竞争还是很激烈，会按 2 倍扩容（4→8→16...），直到达到 NCPU 上限。这种按需扩容的策略既节省了内存，又能在竞争加剧时自动调整。

### Q8: 如果在 sum() 的过程中有线程在 add()，最终值是多还是少？

**答**：都有可能，取决于具体的时序。如果 sum 已经累加了 cells[i] 之后，某个线程又对 cells[i] 做了 add，那这部分会少算；如果 sum 还没累加到 cells[j]，某个线程对 cells[j] 做了 add 然后 sum 才读到它，那这部分就算进去了。总之 sum 的结果是一个不可靠的快照，不保证和任何一个时间点的"真实值"完全一致。

---

## 附录：关键源码速查

### Striped64 核心字段

```java
abstract class Striped64 extends Number {
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    transient volatile Cell[] cells;       // Cell 数组
    transient volatile long base;          // 基础值
    transient volatile int cellsBusy;      // 自旋锁

    final boolean casBase(long cmp, long val) { ... }   // CAS base
    final boolean casCellsBusy() { ... }                // CAS cellsBusy 0→1
    static final int getProbe() { ... }                 // 获取线程探针值
    static final int advanceProbe(int probe) { ... }    // 重新哈希
}
```

### LongAdder 公开方法

```java
public class LongAdder extends Striped64 {
    void add(long x)          // 累加
    void increment()          // +1
    void decrement()          // -1
    long sum()                // 求和（不精确）
    void reset()              // 重置为0
    long sumThenReset()       // 求和后重置
    long longValue()          // 等价于 sum()
    int intValue()            // (int) sum()
    float floatValue()        // (float) sum()
    double doubleValue()      // (double) sum()
}
```

### LongAccumulator 公开方法

```java
public class LongAccumulator extends Striped64 {
    LongAccumulator(LongBinaryOperator fn, long identity)  // 构造
    void accumulate(long x)   // 累积
    long get()                // 获取结果
    void reset()              // 重置为 identity
    long getThenReset()       // 获取后重置
}
```

---

> **总结**：LongAdder 通过"分散热点"的思想，把单点 CAS 竞争转化为多点并行累加，配合 `@Contended` 消除伪共享，在高并发写入场景下实现了远超 AtomicLong 的性能。理解它的核心在于理解 Striped64 的 `longAccumulate()` 方法——那是整个设计的精华所在。
