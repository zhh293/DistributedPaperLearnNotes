# Java synchronized 锁升级全链路深度解析

> 基于 HotSpot JVM（OpenJDK）源码，64位 JVM，深入到每一个 bit 的变化，讲透锁升级的每一步。

---

## 目录

- [一、Java 对象头 Mark Word 结构](#一java-对象头-mark-word-结构)
- [二、无锁状态](#二无锁状态)
- [三、偏向锁详解](#三偏向锁详解)
- [四、轻量级锁详解](#四轻量级锁详解)
- [五、自旋优化](#五自旋优化)
- [六、重量级锁详解](#六重量级锁详解)
- [七、锁升级全链路总结图](#七锁升级全链路总结图)
- [八、锁粗化与锁消除](#八锁粗化与锁消除)
- [九、常见面试问题](#九常见面试问题)

---

## 一、Java 对象头 Mark Word 结构

### 1.1 对象头的三大组成部分

在 HotSpot JVM 中，每个 Java 对象在堆内存中的布局由三部分组成：

```
+--------------------------------------------------+
|                  对象头 (Object Header)            |
|  +--------------------------------------------+  |
|  | Mark Word (存储运行时数据：GC年龄、锁信息等)   |  |
|  +--------------------------------------------+  |
|  | Klass Pointer (类型指针，指向类元数据)         |  |
|  +--------------------------------------------+  |
|  | Array Length (仅数组对象有，记录数组长度)       |  |
|  +--------------------------------------------+  |
+--------------------------------------------------+
|                  实例数据 (Instance Data)          |
+--------------------------------------------------+
|                  对齐填充 (Padding)                |
+--------------------------------------------------+
```

- **Mark Word**：存储对象自身的运行时数据，如哈希码（hashCode）、GC 分代年龄、锁状态标志、线程持有的锁、偏向线程 ID、偏向时间戳等。
- **Klass Pointer**：类型指针，指向对象的类元数据（类元信息存在方法区/元空间中），JVM 据此确定该对象是哪个类的实例。
- **Array Length**：只有数组对象才有，记录数组长度。

### 1.2 Mark Word 的设计理念

Mark Word 被设计成一个**非固定的数据结构**，以便在极小的空间内存储尽量多的信息。它根据对象的状态（锁状态），**复用同一块内存空间**来存储不同的信息。这就是 Mark Word 的核心设计思想——**空间复用**。

在 32 位 JVM 中，Mark Word 为 32 bit；在 64 位 JVM 中，Mark Word 为 64 bit。本文以 **64 位 JVM** 为主进行讲解。

### 1.3 64 位 JVM 下 Mark Word 的 bit 分布图

以下是 64 位 JVM（`markOop.hpp` 源码定义）中 Mark Word 在不同锁状态下的完整 bit 分布：

#### 无锁状态（Unlocked / Neutral）

```
|---------------------------------------------------------------------------------------------------|
|  unused:25bit  |  identity_hashcode:31bit  |  unused:1bit  |  age:4bit  |  biased_lock:1bit  |  lock:2bit  |
|---------------------------------------------------------------------------------------------------|
|                    56bit                        |     4bit     |      1bit       |    2bit     |
```

- `lock:2bit` = `01`，`biased_lock:1bit` = `0` → 无锁
- `identity_hashcode:31bit`：对象的身份哈希码（延迟计算，见后文）
- `age:4bit`：GC 分代年龄（最大 15，因为 4 bit 最大值为 15）

#### 偏向锁状态（Biased Locking）

```
|-----------------------------------------------------------------------------------------------------------|
|  threadId:54bit  |  epoch:2bit  |  unused:1bit  |  age:4bit  |  biased_lock:1bit  |  lock:2bit  |
|-----------------------------------------------------------------------------------------------------------|
|           56bit               |     1bit     |    4bit    |       1bit        |    2bit     |
```

- `lock:2bit` = `01`，`biased_lock:1bit` = `1` → 偏向锁
- `threadId:54bit`：持有偏向锁的线程 ID（OS 线程 ID）
- `epoch:2bit`：偏向锁时间戳/批量重偏向的纪元标记

#### 轻量级锁状态（Lightweight Locked）

```
|-----------------------------------------------------------------------------------------------|
|                              ptr_to_lock_record:62bit                              |  lock:2bit  |
|-----------------------------------------------------------------------------------------------|
|                                     62bit                                         |    2bit     |
```

- `lock:2bit` = `00` → 轻量级锁
- `ptr_to_lock_record:62bit`：指向栈中 Lock Record 的指针（Displaced Mark Word 存放在此）

#### 重量级锁状态（Heavyweight Locked）

```
|-----------------------------------------------------------------------------------------------|
|                              ptr_to_heavyweight_monitor:62bit                      |  lock:2bit  |
|-----------------------------------------------------------------------------------------------|
|                                     62bit                                         |    2bit     |
```

- `lock:2bit` = `10` → 重量级锁
- `ptr_to_heavyweight_monitor:62bit`：指向 ObjectMonitor 对象的指针

#### GC 标记状态（Marked for GC）

```
|-----------------------------------------------------------------------------------------------|
|                                    空（由GC使用）                                   |  lock:2bit  |
|-----------------------------------------------------------------------------------------------|
|                                     62bit                                         |    2bit     |
```

- `lock:2bit` = `11` → GC 标记

### 1.4 锁状态标志位总结表

| 锁状态     | lock (2bit) | biased_lock (1bit) | 说明                       |
|-----------|:-----------:|:-------------------:|----------------------------|
| 无锁       | 01          | 0                   | 对象刚创建，未加锁         |
| 偏向锁     | 01          | 1                   | 同一线程重入，无竞争       |
| 轻量级锁   | 00          | -                   | 有轻度竞争，CAS自旋         |
| 重量级锁   | 10          | -                   | 激烈竞争，线程阻塞等待      |
| GC标记     | 11          | -                   | GC 阶段使用                |

> **关键理解**：`lock` 和 `biased_lock` 组合起来才能确定对象的锁状态。`biased_lock` 仅在 `lock=01` 时有意义。

### 1.5 HotSpot 源码定义

以下代码来自 OpenJDK `src/hotspot/share/oops/markOop.hpp`：

```cpp
// Mark Word 的枚举常量定义
enum { age_bits                 = 4,      // GC分代年龄 4bit
       lock_bits                = 2,      // 锁标志位 2bit
       biased_lock_bits         = 1,      // 偏向锁标志位 1bit
       max_age_bits             = age_bits, // 最大年龄位数
       hash_bits                = 31,     // 哈希码 31bit（64位JVM下）
       thread_bits              = 54,     // 线程ID 54bit
       epoch_bits               = 2,      // 偏向锁纪元 2bit
};

// 锁状态值
enum { locked_value             = 0,      // 00 轻量级锁
       unlocked_value           = 1,      // 01 无锁/偏向锁
       monitor_value            = 2,      // 10 重量级锁
       marked_value             = 3,      // 11 GC标记
       biased_locking_value     = 5,      // 101 偏向锁 (biased_lock=1, lock=01)
};
```

### 1.6 Mark Word 空间复用的本质

理解 Mark Word 的关键在于：**不同状态下同一块 64 bit 内存被用于存储完全不同的信息**。

- 无锁时，这 64 bit 用来存 `identity_hashcode` 和 `age`；
- 偏向锁时，`identity_hashcode` 的位置被 `threadId` + `epoch` 替代；
- 轻量级锁时，整个 62 bit（除去最后 2 bit 的 lock 标志）变成一个指向 Lock Record 的指针；
- 重量级锁时，这 62 bit 变成指向 ObjectMonitor 的指针。

**这就是为什么调用 `hashCode()` 后无法进入偏向锁——因为 hashCode 和 threadId 在 Mark Word 中占据相同的 bit 位，两者互斥！**

---

## 二、无锁状态

### 2.1 对象的初始状态

当一个 Java 对象刚被 `new` 出来时（假设没有调用 `hashCode()`），它的 Mark Word 处于**无锁状态**：

```
Mark Word 初始值（64位JVM）:
|  unused:25bit(全0)  |  identity_hashcode:31bit(全0)  |  unused:1bit(0)  |  age:4bit(0000)  |  biased_lock:0  |  lock:01  |
```

此时：
- `biased_lock = 0`，`lock = 01`：无锁状态
- `identity_hashcode = 0`：尚未计算
- `age = 0`：尚未经历 GC

### 2.2 hashCode 的懒计算（Lazy Calculation）

Java 对象的 `identity hashcode` 是**延迟计算**的，这意味着：

1. **对象刚创建时，hashCode 并不存在**。Mark Word 中 `identity_hashcode` 的 31 bit 全部为 0。
2. **只有在第一次调用 `hashCode()` 方法时**，JVM 才会计算哈希值，并将结果写入 Mark Word。
3. 此后该哈希值**永久不变**，即使 `hashCode()` 被再次调用，也直接返回已缓存的值。

#### 底层实现

在 HotSpot 中，`hashCode()` 的计算由 `Object.hashCode()` 的本地方法实现触发：

```cpp
// src/hotspot/share/oops/oop.cpp
intptr_t oopDesc::identity_hash_value_for(objArrayOop obj) {
    // 如果已经计算过，直接返回
    // 如果没有计算过，调用 FastHashCode 进行计算
}
```

`FastHashCode` 的核心逻辑（`src/hotspot/share/runtime/synchronizer.cpp`）：

```cpp
static intptr_t FastHashCode(Thread* self, oop obj) {
    // 1. 检查 Mark Word 中是否已有 hashCode
    // 2. 如果对象处于偏向锁状态，需要先撤销偏向锁
    // 3. 如果对象处于轻量级锁状态，从 Lock Record 中的 Displaced Mark Word 读取
    // 4. 如果对象处于重量级锁状态，从 ObjectMonitor 中读取
    // 5. 如果无锁且没有 hashCode，则计算并写入 Mark Word
}
```

#### hashCode 的计算方式

JVM 提供了多种 hashCode 生成策略，通过 `-XX:hashCode=` 参数配置（默认值为 5）：

| 值  | 策略              | 说明                                       |
|-----|------------------|--------------------------------------------|
| 0   | Park-Miller      | 随机数生成器                               |
| 1   | 地址             | 对象内存地址                               |
| 2   | 始终为1          | 测试用                                     |
| 3   | 递增             | 从0开始递增                                |
| 4   | 内存地址          | 对象在堆中的地址                            |
| 5   | Marsaglia XOR    | 默认策略，使用随机数和地址的异或操作         |

### 2.3 调用 hashCode 后无法偏向的核心原因

这是面试中的高频考点，也是一个非常精妙的设计细节：

**原因**：在 64 位 Mark Word 中，`identity_hashcode`（31 bit）和 `threadId`（54 bit）占据**同一块内存区域**，二者互斥。

```
无锁状态下的 Mark Word:
|  unused:25bit  |  identity_hashcode:31bit  |  unused:1bit  |  age:4bit  |  biased_lock:0  |  lock:01  |
                                    ↑
                                    这 31 bit 用来存 hashCode

偏向锁状态下的 Mark Word:
|  threadId:54bit  |  epoch:2bit  |  unused:1bit  |  age:4bit  |  biased_lock:1  |  lock:01  |
       ↑                                                           ↑
       threadId 占据了原来 hashCode 的位置                         biased_lock 变为 1
```

如果对象已经计算并缓存了 `identity_hashcode`，那么这个位置就被占用了，无法再写入 `threadId`，因此**该对象永远无法进入偏向锁状态**，只能从无锁直接升级到轻量级锁。

#### 代码验证

```java
public class HashCodeBiasedLockTest {
    public static void main(String[] args) {
        // -XX:BiasedLockingStartupDelay=0 关闭偏向锁延迟
        Object lock = new Object();

        // 先调用 hashCode
        System.out.println("hashCode = " + lock.hashCode());

        // 此时尝试获取偏向锁 —— 会失败，直接进入轻量级锁
        synchronized (lock) {
            // 查看锁状态：这里 lock 的 Mark Word 中 biased_lock=0
            // 已经无法进入偏向锁状态
            System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        }
    }
}
```

### 2.4 System.identityHashCode() vs Object.hashCode()

- `System.identityHashCode(obj)`：无论 obj 是否重写了 `hashCode()`，都返回其**身份哈希码**（存储在 Mark Word 中的那个）。
- `obj.hashCode()`：如果类重写了 `hashCode()`，则调用重写的版本；否则调用默认的 `identity hashcode`。

因此，即使一个类重写了 `hashCode()` 方法，**只要没有调用过 `System.identityHashCode()` 或默认的 `Object.hashCode()`，对象的 Mark Word 中就不会有 identity hashcode，仍然可以进入偏向锁**。

### 2.5 无锁状态下的其他操作

在无锁状态下，Mark Word 中的 `age` 字段会在每次 GC 后递增（如果对象存活下来），当 `age` 达到阈值（默认 15，由 `-XX:MaxTenuringThreshold` 控制）时，对象会从 Survivor 区晋升到老年代。

---

## 三、偏向锁详解

### 3.1 偏向锁的设计目标

偏向锁的提出基于一个重要的**经验观察**：

> **在大多数情况下，锁不仅不存在多线程竞争，而且总是由同一线程多次获得。**

也就是说，**如果一个 synchronized 块从头到尾都只有一个线程在访问**，那么根本没有必要做任何同步操作（连 CAS 都不需要）。偏向锁就是为了让这种场景下的加锁/解锁**零开销**。

"偏向"的含义是：**锁会"偏向"于第一个获得它的线程**，如果在后续运行中该锁没有被其他线程尝试获取，则持有偏向锁的线程永远不需要同步。

### 3.2 偏向锁的延迟开启

**重要**：偏向锁在 JVM 启动后**并不是立即可用的**，而是有一个**延迟**（默认约 4 秒）。

#### 为什么延迟？

JVM 在启动过程中，自身的初始化代码（如 `ClassLoader`、`System` 等核心类的初始化）会大量使用 `synchronized`，而这些同步场景中**存在多线程竞争**。如果一开始就开启偏向锁，这些竞争会导致大量偏向锁撤销，反而降低性能。因此 JVM 在启动后等几秒，等初始化阶段的热锁冷却后再开启偏向锁。

#### 延迟参数

- `-XX:BiasedLockingStartupDelay=0`：设为 0 可以关闭延迟，立即启用偏向锁（测试时常用）

#### 源码证据

```cpp
// src/hotspot/share/runtime/biasedLocking.cpp
void BiasedLocking::init() {
    // 如果偏向锁延迟 > 0，则创建一个 VM_Operation 在延迟时间后启用偏向锁
    if (BiasedLockingStartupDelay > 0) {
        // 创建定时任务，在 BiasedLockingStartupDelay 毫秒后
        // 调用 enable_biased_locking() 方法
    }
}

// 启用偏向锁时，会遍历所有已加载的 Klass，将其 prototype_header 设置为可偏向
static void enable_biased_locking() {
    _biased_locking_enabled = true;
    // 更新所有类的 prototype_header
}
```

#### 验证延迟开启

```java
public class BiasedLockDelayTest {
    public static void main(String[] args) throws InterruptedException {
        Object lock = new Object();
        // 立即加锁 —— 此时偏向锁尚未启用，lock 会是轻量级锁
        synchronized (lock) {
            System.out.println("立即加锁：" + ClassLayout.parseInstance(lock).toPrintable());
        }

        // 等待 5 秒，偏向锁已启用
        Thread.sleep(5000);

        Object lock2 = new Object();
        synchronized (lock2) {
            System.out.println("延迟后加锁：" + ClassLayout.parseInstance(lock2).toPrintable());
        }
    }
}
```

输出对比：
- 立即加锁：`lock: 00`（轻量级锁）
- 延迟后加锁：`biased_lock: 1, lock: 01`（偏向锁）

### 3.3 偏向锁的加锁过程——CAS 写入 threadId

#### 加锁流程（第一次获取）

当线程第一次进入 `synchronized` 代码块时，偏向锁的获取过程如下：

```
步骤1: 检查 Mark Word 的锁标志位
       ├── lock=01, biased_lock=0 → 无锁，尝试偏向
       ├── lock=01, biased_lock=1 → 已偏向，检查是否偏向当前线程
       ├── lock=00 → 轻量级锁，走轻量级锁逻辑
       └── lock=10 → 重量级锁，走重量级锁逻辑

步骤2: 检查对象是否允许偏向（klass 的 prototype_header）
       └── 如果类的 epoch 与全局 epoch 不匹配（批量撤销），则不可偏向

步骤3: 通过 CAS 操作将当前线程 ID 写入 Mark Word
       ├── CAS 成功 → 获取偏向锁成功，Mark Word 变为偏向锁状态
       └── CAS 失败 → 说明存在竞争，需要撤销偏向锁

步骤4: 获取偏向锁成功后，后续该线程再次进入同一个锁的 synchronized 块
       只需简单比较 Mark Word 中的 threadId 是否等于当前线程 ID
       ├── 相等 → 直接进入（零开销）
       └── 不等 → 需要撤销或重偏向
```

#### HotSpot 源码——偏向锁获取

```cpp
// src/hotspot/share/interpreter/interpreterRuntime.cpp
IRT_ENTRY(void, InterpreterRuntime::monitorenter(JavaThread* thread, BasicObjectLock* elem))
    Handle h_obj(thread, elem->obj());
    if (UseBiasedLocking) {
        // 尝试快速路径：偏向锁
        ObjectSynchronizer::fast_enter(h_obj, elem->lock(), true, CHECK);
    } else {
        // 慢速路径：轻量级锁 / 重量级锁
        ObjectSynchronizer::slow_enter(h_obj, elem->lock(), CHECK);
    }
IRT_END
```

```cpp
// src/hotspot/share/runtime/synchronizer.cpp
void ObjectSynchronizer::fast_enter(Handle obj, BasicLock* lock,
                                     bool attempt_rebias, TRAPS) {
    if (UseBiasedLocking) {
        if (!SafepointSynchronize::is_at_safepoint()) {
            // 非安全点，尝试偏向锁获取或重偏向
            BiasedLocking::Condition cond = BiasedLocking::revoke_and_rebias(
                obj, attempt_rebias, THREAD);
            if (cond == BiasedLocking::BIAS_REVOKED_AND_REBIASED) {
                // 成功重偏向
                return;
            }
        } else {
            // 安全点，只能撤销
        }
        // 偏向锁获取失败，走轻量级锁
        slow_enter(obj, lock, THREAD);
    } else {
        slow_enter(obj, lock, THREAD);
    }
}
```

#### 偏向锁获取的核心判断

```cpp
// src/hotspot/share/runtime/biasedLocking.cpp
BiasedLocking::Condition BiasedLocking::revoke_and_rebias(
    Handle obj, bool attempt_rebias, TRAPS) {

    markOop mark = obj->mark();

    // 1. 如果 Mark Word 已经偏向当前线程 → 直接返回（最快路径）
    if (mark->has_bias_pattern() &&
        mark->biased_locker() == THREAD) {
        return BiasedLocking::BIAS_REVOKED_AND_REBIASED;
    }

    // 2. 如果对象是可偏向的且当前没有偏向 → CAS 写入线程ID
    if (mark->is_biased_anonymously()) {
        // 匿名偏向（第一次获取偏向锁）
        markOop biased_prototype = ...;
        if (attempt_rebias) {
            // CAS 将 threadId 写入 Mark Word
            markOop new_mark = markOopDesc::encode_thread_id(
                THREAD, mark->age(), mark->epoch());
            if (obj->cas_set_mark(new_mark, mark) == mark) {
                return BiasedLocking::BIAS_REVOKED_AND_REBIASED;
            }
        }
    }

    // 3. CAS 失败或其他情况 → 需要撤销偏向锁
    ...
}
```

#### 偏向锁重入——无需任何同步操作

当同一线程再次进入已偏向自己的 `synchronized` 块时：

1. 读取 Mark Word，发现 `biased_lock=1, lock=01`
2. 检查 `threadId == 当前线程ID` → **匹配**
3. 直接进入 synchronized 块 → **没有任何 CAS，没有任何内存屏障，零开销**

这就是偏向锁的终极目标：**让只有一个线程访问的锁，性能等同于没有锁**。

### 3.4 偏向锁的撤销流程

偏向锁的**撤销（Revocation）**是指将偏向锁状态恢复为无锁状态（或升级为轻量级锁）。撤销不是简单的操作，它需要等待 **SafePoint**（安全点）。

#### 什么时候需要撤销？

1. **其他线程尝试获取偏向锁**：此时偏向锁的"偏向"被打破
2. **调用对象的 `hashCode()`**：需要空间存储 hashCode
3. **批量撤销**：类的所有对象都不再适合偏向锁

#### 撤销的详细流程

```
步骤1: 线程B尝试获取已被线程A偏向的锁
       → CAS 失败（因为 Mark Word 中的 threadId 不是线程B的）

步骤2: 线程B请求撤销偏向锁
       → 等待全局安全点（SafePoint）

步骤3: 到达 SafePoint 后，检查原持有偏向锁的线程A的状态
       ├── 线程A已退出 synchronized 块（不活跃）
       │   → 将 Mark Word 恢复为无锁状态（或轻量级锁）
       │   → 线程B重新竞争锁
       └── 线程A仍在 synchronized 块中（活跃）
           → 偏向锁升级为轻量级锁
           → 将 Lock Record 指针写入 Mark Word

步骤4: 恢复线程执行
```

#### SafePoint 机制

**为什么撤销偏向锁需要 SafePoint？**

因为撤销偏向锁需要检查**持有偏向锁的线程的状态**（是否还在 synchronized 块中），这需要查看线程栈帧中的 Lock Record。而这个操作必须保证线程栈的**一致性**——线程必须在已知状态下（而不是正在修改栈帧），所以需要等到 SafePoint。

**SafePoint 是什么？**

SafePoint（安全点）是 JVM 中所有线程暂停执行的一个同步点。在 SafePoint 处，线程的执行状态是确定的，JVM 可以安全地进行需要线程停顿的操作（如 GC、偏向锁撤销、类卸载等）。

SafePoint 的触发：
- 方法返回前
- 循环回跳前
- 异常抛出时
- JNI 调用时

```cpp
// 偏向锁撤销的核心方法
static BiasedLocking::Condition revoke_bias(oop obj, bool allow_rebias, bool is_bulk, JavaThread* requesting_thread) {
    markOop mark = obj->mark();

    // 如果不是偏向锁状态，直接返回
    if (!mark->has_bias_pattern()) {
        return BiasedLocking::NOT_BIASED;
    }

    // 获取偏向的线程
    JavaThread* biased_thread = mark->biased_locker();

    if (biased_thread == NULL) {
        // 匿名偏向，没有线程持有 → 直接撤销为无锁
        markOop new_mark = mark->set_biased_lock_bits(false);
        obj->set_mark(new_mark);
        return BiasedLocking::BIAS_REVOKED;
    }

    // 检查偏向线程是否还存活
    if (!biased_thread->is_alive()) {
        // 线程已死 → 直接撤销
        ...
    }

    // 检查偏向线程是否还持有该锁（遍历线程栈）
    GrowableArray<MonitorInfo*>* cached_monitor_info =
        get_or_compute_monitor_info(biased_thread);

    bool thread_holds_lock = false;
    for (int i = 0; i < cached_monitor_info->length(); i++) {
        MonitorInfo* monitor_info = cached_monitor_info->at(i);
        if (monitor_info->owner() == obj) {
            thread_holds_lock = true;
            break;
        }
    }

    if (thread_holds_lock) {
        // 线程仍在 synchronized 块中 → 升级为轻量级锁
        markOop new_mark = ...;
        obj->set_mark(new_mark);
    } else {
        // 线程已退出 synchronized 块 → 允许重偏向或撤销为无锁
        if (allow_rebias) {
            markOop new_mark = mark->set_biased_lock_bits(false);
            obj->set_mark(new_mark);
        } else {
            markOop new_mark = markOopDesc::encode(
                mark->hash(), mark->age(), false, 0);
            obj->set_mark(new_mark);
        }
    }

    return BiasedLocking::BIAS_REVOKED;
}
```

### 3.5 批量重偏向（Bulk Rebiasing）与 epoch 机制

#### 问题场景

假设一个类创建了大量对象，线程 A 对这些对象全部获取了偏向锁。然后线程 B 尝试获取这些锁，每次都需要撤销偏向锁，造成大量性能开销。

批量重偏向的目的是：**当某个类的对象频繁发生偏向锁撤销时，不直接禁用偏向锁，而是尝试将锁"重偏向"到新的线程**。

#### epoch 机制详解

每个类（Klass）维护一个 `epoch`（纪元）字段，全局也维护一个 `epoch`：

- **类级 epoch**：存储在类的 `prototype_header` 中，2 bit
- **对象级 epoch**：存储在对象 Mark Word 的 `epoch` 字段中，2 bit

工作原理：

```
初始状态：
- 全局 epoch = 0
- 类的 epoch = 0
- 所有该类对象的 epoch = 0

当该类的偏向锁撤销次数达到阈值（默认 20）时：
- 全局 epoch = 1
- 类的 epoch = 1
- 已有对象的 epoch 仍然是 0 → 这些对象在下次被访问时会被重偏向
- 新创建对象的 epoch = 1（自动获取类的最新 epoch）
```

#### 批量重偏向的触发阈值

- `-XX:BiasedLockingBulkRebiasThreshold=20`（默认值 20）
- 当某个类的偏向锁撤销次数达到 20 次时，触发批量重偏向

#### 批量重偏向的流程

```
1. 统计该类对象的偏向锁撤销次数
2. 撤销次数 >= BulkRebiasThreshold(20)
3. 递增全局 epoch
4. 更新该类的 prototype_header 中的 epoch
5. 在 SafePoint 遍历所有线程栈，将所有仍被偏向的该类对象的 epoch 更新为最新值
6. 撤销次数未达到 BulkRevokeThreshold(40) 的对象，epoch 不匹配 → 允许重偏向
```

#### 重偏向的实现

当线程尝试获取偏向锁，发现对象的 `epoch` 与类的 `epoch` 不一致时：

```cpp
// epoch 不匹配时，说明对象是"过时"的偏向
// 这种情况下，允许通过 CAS 重偏向到新线程
if (mark->epoch() != prototype_header->epoch()) {
    // 过时的偏向 → 尝试重偏向
    markOop new_mark = markOopDesc::encode_thread_id(
        THREAD, mark->age(), prototype_header->epoch());
    if (obj->cas_set_mark(new_mark, mark) == mark) {
        // 重偏向成功
        return BiasedLocking::BIAS_REVOKED_AND_REBIASED;
    }
}
```

### 3.6 批量撤销（Bulk Revocation）

当偏向锁撤销更加频繁时，说明该类的对象**完全不适合偏向锁**，此时触发批量撤销。

#### 批量撤销的触发阈值

- `-XX:BiasedLockingBulkRevokeThreshold=40`（默认值 40）
- 当某个类的偏向锁撤销次数达到 40 次时，触发批量撤销

#### 批量撤销的效果

**该类的所有对象永远不再使用偏向锁**，即使后续只有一个线程访问。类的 `prototype_header` 中的 `biased_lock` 被设为 0，新创建的对象也不会进入偏向锁状态。

#### 批量撤销与批量重偏向的区别

| 特性          | 批量重偏向                        | 批量撤销                           |
|--------------|----------------------------------|-----------------------------------|
| 触发阈值      | 20 次                            | 40 次                              |
| 效果         | 允许对象重偏向到新线程             | 该类所有对象永久禁用偏向锁          |
| epoch 处理    | 递增 epoch，允许重偏向             | 将类的 biased_lock 设为 0          |
| 后续行为      | 新线程仍可获取偏向锁               | 新线程只能走轻量级锁/重量级锁       |
| 可逆性       | 可逆（下次 epoch 变化时可以再次重偏向）| 不可逆（该类永久禁用偏向锁）        |

#### 批量撤销的流程

```
1. 某类的偏向锁撤销次数 >= 40
2. 在 SafePoint 遍历所有线程栈
3. 撤销所有该类对象的偏向锁
4. 将该类的 prototype_header 设置为不可偏向（biased_lock=0）
5. 后续该类的新对象创建时 Mark Word 的 biased_lock=0（无锁）
```

### 3.7 偏向锁撤销的性能代价

偏向锁撤销的代价是**很高**的：

1. **需要等待 SafePoint**：可能造成线程停顿
2. **需要遍历线程栈**：检查持有偏向锁的线程的状态
3. **可能涉及批量操作**：遍历所有线程栈中的所有 MonitorInfo

这就是为什么偏向锁在高竞争场景下反而会降低性能——频繁的撤销操作开销远超锁本身的开销。

### 3.8 JDK 15 废弃偏向锁——JEP 374

#### 废弃原因

JEP 374（Biased Locking Is Deprecated for Removal）在 JDK 15 中宣布偏向锁将被移除，原因如下：

1. **现代硬件上 CAS 已经非常快**：偏向锁优化的"避免 CAS"在早期硬件上收益明显，但现代 CPU 的 CAS 指令（如 `CMPXCHG`）已经非常高效，偏向锁的收益大幅降低。

2. **撤销代价过高**：偏向锁撤销需要 SafePoint，在高并发场景下频繁撤销会严重影响性能，甚至比不使用偏向锁更差。

3. **与现代 GC 不兼容**：Shenandoah、ZGC 等低延迟垃圾收集器致力于消除 STW（Stop-The-World）暂停，而偏向锁撤销需要在 SafePoint 执行，这与低延迟 GC 的目标相矛盾。

4. **维护成本高**：偏向锁的代码遍布 JVM 各处，增加了 JVM 的复杂性和维护成本。

5. **实际收益有限**：在很多真实工作负载中，偏向锁的性能提升已经微乎其微。

#### 废弃后的替代方案

- 轻量级锁 + 自旋 → 重量级锁的升级路径仍然保留
- 原有的无锁 → 偏向锁 → 轻量级锁 → 重量级锁 变为 **无锁 → 轻量级锁 → 重量级锁**

#### 相关 JVM 参数

```bash
# JDK 15+ 禁用偏向锁
-XX:-UseBiasedLocking

# JDK 15+ 启用偏向锁（已废弃，仍可用但不推荐）
-XX:+UseBiasedLocking
```

---

## 四、轻量级锁详解

### 4.1 轻量级锁的设计目标

轻量级锁的设计基于以下观察：

> **对于绝大多数的锁，在整个同步周期内都不存在竞争。即使有竞争，持有锁的时间也非常短，线程等待的时间也很短。**

在这种场景下，**让线程通过自旋（CAS）来等待锁，比让线程阻塞（需要用户态→内核态切换）更高效**。

轻量级锁的核心思想：**用 CAS 操作替代互斥量，在没有真正竞争的情况下避免内核态切换**。

### 4.2 Lock Record（锁记录）

Lock Record（也叫 Lock Record、BasicLock）是轻量级锁的核心数据结构，它存在于**线程栈**中。

#### Lock Record 的结构

```cpp
// src/hotspot/share/oops/basicLock.hpp
class BasicLock {
private:
    // 存储 Mark Word 的副本（Displaced Mark Word）
    volatile markOop _displaced_header;
};

class BasicObjectLock {
    BasicLock _lock;            // 锁记录
    oop       _obj;             // 指向锁对象
};
```

在 Java 层面，每个线程在进入 `synchronized` 块时，会在**当前栈帧**中创建一个 Lock Record：

```
线程栈:
+-----------------------------------+
|  栈帧 (当前方法)                    |
|  +-------------------------------+|
|  | 局部变量表                      ||
|  | 操作数栈                       ||
|  | Lock Record:                   ||
|  |   _displaced_header = Mark Word ||
|  |   _obj = 锁对象引用             ||
|  +-------------------------------+|
+-----------------------------------+
```

### 4.3 Displaced Mark Word（ displaced 标记字）

**Displaced Mark Word** 是 Lock Record 中最关键的概念：

- 在获取轻量级锁时，JVM 将对象 Mark Word 的**原始值**拷贝到 Lock Record 的 `_displaced_header` 中
- 然后用 CAS 将对象 Mark Word 替换为指向 Lock Record 的指针
- 在释放锁时，用 CAS 将 Displaced Mark Word 恢复回对象的 Mark Word

**为什么需要 Displaced Mark Word？**

因为 Mark Word 在轻量级锁状态下被替换为指向 Lock Record 的指针，原来的信息（如 hashCode、GC 年龄等）被"挤出"了。这些信息需要被保存在某个地方，以便在锁释放后恢复。Displaced Mark Word 就是保存这些信息的容器。

### 4.4 轻量级锁的加锁过程

#### 详细步骤

```
步骤1: 在当前栈帧中创建 Lock Record
       → _displaced_header 初始化为 NULL
       → _obj 指向锁对象

步骤2: 将对象的 Mark Word 拷贝到 Lock Record 的 _displaced_header
       → 这就是 Displaced Mark Word

步骤3: 通过 CAS 操作，尝试将对象的 Mark Word 替换为指向 Lock Record 的指针
       CAS(obj->mark_word(), expected=原始MarkWord, new=LockRecord指针|00)
       ├── CAS 成功 → 获取轻量级锁成功
       │   → 对象 Mark Word 变为: [ptr_to_lock_record:62bit | lock:00]
       │   → Lock Record 中 _displaced_header 保存原始 Mark Word
       └── CAS 失败 → 说明有竞争
           → 检查 Mark Word 是否指向当前线程的 Lock Record
               ├── 是 → 锁重入（见下文）
               └── 否 → 存在真正竞争，膨胀为重量级锁

步骤4: 获取成功后进入 synchronized 块
```

#### 加锁流程图

```
         尝试获取轻量级锁
               |
        创建 Lock Record
               |
     拷贝 Mark Word 到 Displaced Header
               |
     CAS 替换 Mark Word → Lock Record 指针
          /            \
     CAS 成功        CAS 失败
        |               |
   获取锁成功     检查 Mark Word 是否指向当前线程栈
                    /              \
               是(重入)          否(竞争)
                |                  |
          Lock Record 的       自旋等待/
          _displaced_header    膨胀为重量级锁
          设为 NULL
```

### 4.5 锁重入——Displaced Mark Word 为 NULL

当线程已经持有轻量级锁，再次进入同一个锁的 `synchronized` 块时（**锁重入**），处理方式如下：

1. 创建新的 Lock Record
2. 检查 Mark Word 是否指向当前线程的栈 → 是，说明是重入
3. 将新 Lock Record 的 `_displaced_header` 设为 **NULL**
4. 不需要 CAS（因为已经持有锁）
5. `_obj` 仍然指向锁对象

**为什么要设为 NULL？**

因为解锁时需要根据 `_displaced_header` 的值来判断：
- 如果 `_displaced_header` 为 NULL → 这是重入，只需简单地删除 Lock Record
- 如果 `_displaced_header` 不为 NULL → 这是最后一次解锁，需要用 CAS 将 Displaced Mark Word 恢复到对象 Mark Word

#### 重入的 Lock Record 链

```
线程栈:
+-----------------------------------+
|  栈帧                              |
|  +-------------------------------+|
|  | Lock Record 1 (最外层):        ||
|  |   _displaced_header = 原始Mark ||
|  |   _obj = 锁对象                ||
|  +-------------------------------+|
|  | Lock Record 2 (重入1):        ||
|  |   _displaced_header = NULL     ||
|  |   _obj = 锁对象                ||
|  +-------------------------------+|
|  | Lock Record 3 (重入2):        ||
|  |   _displaced_header = NULL     ||
|  |   _obj = 锁对象                ||
|  +-------------------------------+|
+-----------------------------------+
```

### 4.6 轻量级锁的解锁过程

#### 详细步骤

```
步骤1: 从栈中取出最近的一个 Lock Record

步骤2: 检查 _displaced_header 是否为 NULL
       ├── 为 NULL → 这是重入的解锁
       │   → 直接删除该 Lock Record，不需要操作 Mark Word
       │   → 继续检查下一个 Lock Record
       └── 不为 NULL → 这是最后一次解锁
           → 需要将 Displaced Mark Word 恢复回对象

步骤3: 通过 CAS 操作，将 Displaced Mark Word 恢复回对象的 Mark Word
       CAS(obj->mark_word(), expected=LockRecord指针, new=DisplacedMarkWord)
       ├── CAS 成功 → 解锁成功，对象恢复为无锁状态
       └── CAS 失败 → 说明有竞争（其他线程在自旋等待或已膨胀为重量级锁）
           → 需要膨胀为重量级锁，然后唤醒被阻塞的线程
```

#### 解锁失败的CAS——膨胀触发

解锁时 CAS 失败意味着：在当前线程持有锁的期间，有其他线程尝试获取锁，并且锁已经膨胀为重量级锁（Mark Word 已被修改为指向 ObjectMonitor 的指针）。此时当前线程需要：

1. 释放 ObjectMonitor
2. 唤醒等待的线程

### 4.7 轻量级锁的 HotSpot 源码

```cpp
// src/hotspot/share/runtime/synchronizer.cpp

// 轻量级锁获取
void ObjectSynchronizer::slow_enter(Handle obj, BasicLock* lock, TRAPS) {
    markOop mark = obj->mark();

    if (mark->is_neutral()) {
        // 无锁状态
        // 将 Mark Word 拷贝到 Lock Record
        lock->set_displaced_header(mark);
        // CAS 替换 Mark Word
        if (obj->cas_set_mark((markOop) lock, mark) == mark) {
            // CAS 成功，获取轻量级锁
            return;
        }
        // CAS 失败，存在竞争 → 膨胀
    } else if (mark->has_locker() &&
               THREAD->is_lock_owned((address)mark->locker())) {
        // 锁重入
        assert(mark != markOopDesc::INFLATING(), "inflating?");
        // Displaced Mark Word 设为 NULL
        lock->set_displaced_header(NULL);
        return;
    } else {
        // 其他情况（有竞争）
        lock->set_displaced_header(markOopDesc::unused_mark());
    }
    // 膨胀为重量级锁
    ObjectSynchronizer::inflate(THREAD, obj)->enter(THREAD);
}

// 轻量级锁释放
void ObjectSynchronizer::fast_exit(oop object, BasicLock* lock, TRAPS) {
    markOop mark = object->mark();
    markOop dhw = lock->displaced_header();

    if (dhw == NULL) {
        // 重入解锁，无需操作
        return;
    }

    // CAS 恢复 Mark Word
    if (object->cas_set_mark(dhw, mark) == mark) {
        // 恢复成功
        return;
    }

    // CAS 失败 → 锁已膨胀为重量级锁
    ObjectSynchronizer::inflate(THREAD, object)->exit(true, THREAD);
}
```

### 4.8 轻量级锁的适用场景与局限

**适用场景**：
- 两个线程交替执行 synchronized 块
- 锁持有时间非常短
- 没有真正的并发竞争（交替执行而非同时执行）

**不适用场景**：
- 多个线程同时竞争同一把锁
- 锁持有时间较长
- 自旋消耗 CPU 时间过长

---

## 五、自旋优化

### 5.1 自旋等待的原理

当轻量级锁 CAS 获取失败时，JVM 不会立即将锁膨胀为重量级锁，而是让当前线程执行一个**空循环（自旋）**，看看持有锁的线程是否很快就会释放锁。

```java
// 自旋等待的伪代码
while (!tryLock()) {
    // 空循环，什么都不做，只是反复尝试
    // 这就是"自旋"
}
```

自旋等待的**核心假设**：**线程持有锁的时间通常很短，自旋等待的代价小于线程阻塞/唤醒的代价**。

### 5.2 自旋 vs 阻塞的代价对比

| 操作            | 自旋等待            | 线程阻塞/唤醒         |
|----------------|--------------------|--------------------|
| CPU 消耗       | 持续消耗 CPU         | 不消耗 CPU           |
| 上下文切换      | 无                  | 两次（阻塞+唤醒）      |
| 用户态/内核态   | 始终用户态           | 需要切换到内核态       |
| 响应速度       | 立即（锁释放后马上获取）| 需要等待调度          |
| 适用场景       | 锁持有时间短          | 锁持有时间长          |

### 5.3 适应性自旋（Adaptive Spinning）

JDK 6 引入了**适应性自旋**（Adaptive Spinning），这是对简单自旋的重要优化。

#### 适应性自旋的核心思想

自旋的次数**不是固定的**，而是根据**前一次在同一个锁上的自旋情况**动态调整：

- **上次自旋成功获取了锁** → 认为这次自旋也有很大概率成功 → **增加自旋次数**
- **上次自旋失败（最终膨胀为重量级锁）** → 认为这次自旋大概率也会失败 → **减少自旋次数甚至跳过自旋**

#### 自旋次数的默认值

```cpp
// 默认自旋次数
int ObjectSynchronizer::Knob_FixedSpin = 0;       // 固定自旋次数（默认不使用）
int ObjectSynchronizer::Knob_PreSpinSpin   = 10;   // 适应性自旋的初始次数
int ObjectSynchronizer::Knob_PremiumLimit  = 100;  // 适应性自旋的最大次数
```

JVM 通过 `-XX:PreBlockSpin` 参数可以设置自旋次数（JDK 6 早期），但在适应性自旋中这个值已经意义不大，因为 JVM 会自动调整。

#### 适应性自旋的实现

```cpp
// src/hotspot/share/runtime/objectMonitor.cpp
void ObjectMonitor::EnterI(TRAPS) {
    // ...
    // 自旋等待
    int ctr = 0;
    for (;;) {
        // 尝试获取锁
        if (TryLock(THREAD) > 0) {
            // 自旋成功
            // 更新自旋成功统计
            return;
        }
        ctr++;
        if (ctr > adaptive_spin_count) {
            // 自旋次数超过阈值，退出自旋
            break;
        }
        // 自旋等待（可能使用 PAUSE 指令减少功耗）
        SpinPause();
    }
    // 自旋失败，进入阻塞
    // ...
}
```

### 5.4 自旋优化中的 PAUSE 指令

在自旋循环中，JVM 会插入 `PAUSE` 指令（x86 架构）：

```asm
; x86 架构下的自旋
spin_loop:
    pause        ; 提示 CPU 这是一个自旋循环
    cmp [lock], 0
    jne spin_loop
```

`PAUSE` 指令的作用：
1. **减少 CPU 功耗**：提示 CPU 这是一个自旋等待循环，CPU 可以降低流水线功耗
2. **避免内存顺序违规**：在自旋循环中，`PAUSE` 可以避免因推测执行导致的内存顺序违规惩罚
3. **在超线程 CPU 上让出执行资源**：让同一核心上的另一个超线程有更多执行机会

### 5.5 自旋失败的后果

如果自旋超过阈值仍然没有获取到锁，轻量级锁就会**膨胀为重量级锁**。此后竞争线程将进入阻塞状态，不再自旋。

**注意**：自旋等待只发生在轻量级锁阶段。一旦膨胀为重量级锁，竞争线程就会直接进入 ObjectMonitor 的 EntryList 阻塞等待。

---

## 六、重量级锁详解

### 6.1 重量级锁的设计目标

重量级锁是 synchronized 的**最终形态**，当轻量级锁的自旋也无法获取锁时，锁就会膨胀为重量级锁。重量级锁使用操作系统提供的**互斥量（Mutex）**来实现同步，涉及**用户态到内核态的切换**，开销最大，但也最可靠。

### 6.2 ObjectMonitor 结构

ObjectMonitor 是 HotSpot 中重量级锁的核心数据结构，每个被膨胀为重量级锁的对象都关联一个 ObjectMonitor 实例。

#### ObjectMonitor 的完整结构

```cpp
// src/hotspot/share/runtime/objectMonitor.hpp
class ObjectMonitor : public CHeapObj<mtObjectMonitor> {
private:
    // -------- Header --------
    volatile markOop _header;          // 保存对象的 Mark Word（原始值）

    // -------- Owner --------
    void* volatile _owner;             // 指向持有锁的线程（Thread* 或 JavaThread*）

    // -------- Counters --------
    volatile jint _count;              // 许可计数（用于 count 防止虚假唤醒）
    volatile jint _waiters;            // 在 WaitSet 中等待的线程数量

    // -------- Recursions --------
    volatile jint _recursions;         // 重入次数

    // -------- Entry List --------
    ObjectWaiter* volatile _EntryList; // 阻塞等待锁的线程队列（CXQ + EntryList）

    // -------- Wait Set --------
    ObjectWaiter* volatile _WaitSet;   // 调用 wait() 后等待的线程队列

    // -------- CXQ (Contention Queue) --------
    ObjectWaiter* volatile _cxq;       // 竞争队列（最近来竞争的线程）

    // -------- Other --------
    Thread* _Responsible;              // 负责唤醒的线程（避免惊群效应）
    volatile jint _SpinFreq;           // 自旋频率
    volatile jint _SpinClock;          // 自旋时钟
    volatile intptr_t _SpinState;      // 自旋状态
    volatile jint _SpinDuration;       // 自旋持续时间
};
```

#### 各字段详解

| 字段           | 类型               | 说明                                              |
|---------------|--------------------|---------------------------------------------------|
| `_header`     | `markOop`          | 保存对象原始的 Mark Word（包含 hashCode、age 等）    |
| `_owner`      | `void*`            | 指向当前持有锁的线程                                |
| `_recursions` | `jint`             | 锁重入次数（0 表示首次获取）                        |
| `_EntryList`  | `ObjectWaiter*`    | 阻塞等待获取锁的线程队列（已经排好队的）             |
| `_cxq`        | `ObjectWaiter*`    | 竞争队列（新来的竞争者先入这个队列）                 |
| `_WaitSet`    | `ObjectWaiter*`    | 调用 `wait()` 后等待被 notify 的线程队列            |
| `_count`      | `jint`             | 辅助计数器                                          |
| `_waiters`    | `jint`             | WaitSet 中的线程数量                                |
| `_Responsible`| `Thread*`          | 负责唤醒的线程，避免所有等待线程同时被唤醒（惊群效应）|

#### ObjectWaiter 结构

```cpp
class ObjectWaiter : public CHeapObj<mtObjectMonitor> {
public:
    enum TStates { TS_UNDEF, TS_READY, TS_RUN, TS_WAIT, TS_ENTER, TS_WAIT_SUSPEND };

    ObjectWaiter* volatile _next;      // 下一个节点
    ObjectWaiter* volatile _prev;      // 上一个节点
    Thread*       _thread;             // 关联的线程
    jlong         _notifier_tid;       // 通知者的线程 ID
    TStates       _state;              // 线程状态
    ParkEvent*    _event;              // 用于阻塞/唤醒的 ParkEvent
};
```

### 6.3 重量级锁的加锁流程

#### 完整加锁流程

```
步骤1: 尝试快速获取锁（_owner == NULL）
       CAS(_owner, NULL, current_thread)
       ├── CAS 成功 → 获取锁成功
       │   → _recursions = 0
       │   → 返回
       └── CAS 失败 → 锁已被占用，进入步骤2

步骤2: 检查是否是锁重入（_owner == current_thread）
       ├── 是重入 → _recursions++
       │   → 返回
       └── 不是重入 → 进入步骤3

步骤3: 尝试自旋获取锁（适应性自旋）
       ├── 自旋成功 → 获取锁成功，返回
       └── 自旋失败 → 进入步骤4

步骤4: 将当前线程封装为 ObjectWaiter，加入 CXQ 竞争队列
       → _cxq = new ObjectWaiter(current_thread)
       → 如果 _cxq 不为空，新节点插入到 _cxq 头部

步骤5: 进入循环等待
       for (;;) {
           ├── 尝试获取锁（TryLock）
           │   ├── 成功 → 获取锁，返回
           │   └── 失败 → 继续
           ├── 尝试自旋
           │   ├── 成功 → 获取锁，返回
           │   └── 失败 → 继续
           └── 调用 ParkEvent::Park() 阻塞当前线程
               → 线程进入 BLOCKED 状态
               → 等待被唤醒后重新尝试获取锁
       }
```

#### 加锁流程图

```
尝试 CAS 获取锁(_owner=NULL?)
      |
  成功/重入 → 获取成功
      |
  失败 → 自旋等待
      |
  自旋成功 → 获取成功
      |
  自旋失败 → 加入 CXQ 队列
      |
  Park 阻塞 → 等待唤醒
      |
  唤醒后重试获取锁
```

### 6.4 重量级锁的解锁流程

#### 完整解锁流程

```
步骤1: 检查 _owner 是否为当前线程
       → 确保只有锁持有者才能解锁

步骤2: 检查重入次数
       if (_recursions > 0) {
           _recursions--;
           return;  // 还没完全释放
       }

步骤3: 准备释放锁
       → 将 _header（保存的原始 Mark Word）准备好
       → 但不立即写回对象的 Mark Word（对象仍然处于重量级锁状态）

步骤4: 唤醒等待的线程
       ├── EntryList 不为空 → 从 EntryList 头部取一个线程唤醒
       ├── EntryList 为空但 CXQ 不为空 → 将 CXQ 中的节点移动到 EntryList，然后唤醒
       └── 都为空 → 没有等待线程，直接释放

步骤5: 通过 unpark 唤醒选中的线程
       → 被唤醒的线程会重新尝试获取锁
       → 获取成功后 _owner 指向该线程

步骤6: 将 _owner 设为 NULL
       → 但注意：对象的 Mark Word 仍然指向 ObjectMonitor
       → 重量级锁不会降级
```

#### CXQ 与 EntryList 的关系

CXQ 和 EntryList 都是 ObjectWaiter 队列，但作用不同：

- **CXQ（Contention Queue）**：新来的竞争者先进入这个队列。采用**栈结构（LIFO）**，新节点插入头部。
- **EntryList**：当锁释放时，CXQ 中的节点会被**移动到 EntryList**。EntryList 采用**队列结构（FIFO）**，保证公平性。

```
锁释放时的操作：
1. 检查 EntryList 是否为空
2. 如果为空，将 CXQ 中的所有节点按逆序移入 EntryList
3. 从 EntryList 头部取一个线程唤醒
```

这种**两段式队列**的设计原因：
- CXQ 使用 LIFO 可以减少 CAS 竞争（新来的线程插入头部，不需要遍历整个队列）
- EntryList 使用 FIFO 保证了一定的公平性

### 6.5 wait() / notify() 机制

#### wait() 流程

当线程调用 `obj.wait()` 时：

```
步骤1: 检查当前线程是否持有 obj 的锁
       → 如果没有持有，抛出 IllegalMonitorStateException

步骤2: 将当前线程封装为 ObjectWaiter
       → _state = TS_WAIT

步骤3: 将 ObjectWaiter 加入 WaitSet
       → 插入到 WaitSet 的尾部（双向链表）

步骤4: 释放锁
       → _owner = NULL
       → _recursions = 0
       → 保存 _recursions 用于被唤醒后恢复

步骤5: 阻塞当前线程
       → 调用 ParkEvent::Park()

步骤6: 被唤醒后（notify 或超时）
       → 重新尝试获取锁（和普通竞争者一样）
       → 恢复重入次数
```

#### notify() 流程

当线程调用 `obj.notify()` 时：

```
步骤1: 检查当前线程是否持有 obj 的锁

步骤2: 从 WaitSet 中取出第一个 ObjectWaiter
       → 从 WaitSet 双向链表中移除

步骤3: 将取出的 ObjectWaiter 移入 CXQ 或 EntryList
       → 优先放入 EntryList
       → 如果 EntryList 不为空，放入 CXQ

步骤4: 唤醒该线程
       → 调用 ObjectWaiter._event->unpark()

注意：被唤醒的线程不会立即获取锁！
它需要和其他竞争线程一起竞争获取锁。
```

#### notifyAll() 流程

```
1. 遍历 WaitSet 中的所有 ObjectWaiter
2. 逐个从 WaitSet 移除
3. 逐个移入 CXQ 或 EntryList
4. 唤醒所有线程
5. 所有被唤醒的线程竞争获取锁
```

### 6.6 用户态与内核态切换的代价

重量级锁最大的性能开销来自于**用户态与内核态的切换**。

#### 切换过程

```
线程A释放锁（用户态）
    |
    ↓
调用 futex_wake / pthread_mutex_unlock（系统调用）
    |
    ↓ ---- 切换到内核态 ----
    |
内核查找等待的线程B
    |
    ↓
唤醒线程B（将线程B从等待队列移入就绪队列）
    |
    ↓ ---- 切换回用户态 ----
    |
线程A继续执行
    |
    ↓
内核调度线程B运行
    |
    ↓ ---- 上下文切换 ----
    |
线程B获取锁，继续执行
```

#### 代价分析

| 操作                    | 代价（CPU 周期）  | 说明                    |
|------------------------|:----------------:|------------------------|
| CAS 操作（用户态）       | ~10-100          | 纯用户态，非常快         |
| 自旋等待（用户态）       | ~100-1000        | 消耗 CPU，但无上下文切换 |
| 系统调用（用户态→内核态）| ~1000-10000      | 需要保存/恢复寄存器      |
| 线程阻塞+唤醒           | ~10000-100000    | 涉及内核调度、上下文切换  |
| 完整锁竞争+上下文切换    | ~50000+          | 最坏情况                 |

### 6.7 锁膨胀（Inflation）过程

当轻量级锁 CAS 获取失败且自旋也失败时，需要将锁膨胀为重量级锁。

#### 膨胀流程

```
步骤1: 分配 ObjectMonitor 对象
       → 从全局空闲列表或直接 new 一个

步骤2: 初始化 ObjectMonitor
       → _header = 对象的 Mark Word（保存原始值）
       → _owner = 持有锁的线程
       → _recursions = 0
       → _EntryList = NULL
       → _WaitSet = NULL
       → _cxq = NULL

步骤3: 通过 CAS 将对象的 Mark Word 替换为指向 ObjectMonitor 的指针
       CAS(obj->mark_word(), expected=当前值, new=ObjectMonitor指针|10)
       ├── CAS 成功 → 膨胀成功
       └── CAS 失败 → 其他线程已经完成了膨胀，重试

步骤4: 竞争线程进入 ObjectMonitor 的 CXQ 队列
```

#### 膨胀中的状态标记

在膨胀过程中，Mark Word 会被临时设置为 `INFLATING` 状态（一个特殊值），其他线程看到这个值时会自旋等待膨胀完成。

```cpp
// 膨胀过程的源码简化
ObjectMonitor* ObjectSynchronizer::inflate(TRAPS, oop object) {
    for (;;) {
        markOop mark = object->mark();

        if (mark->has_monitor()) {
            // 已经是重量级锁，直接返回
            return mark->monitor();
        }

        if (mark == markOopDesc::INFLATING()) {
            // 正在膨胀中，等待
            continue;
        }

        // 设置 INFLATING 标记
        if (object->cas_set_mark(markOopDesc::INFLATING(), mark) != mark) {
            continue; // CAS 失败，重试
        }

        // 分配并初始化 ObjectMonitor
        ObjectMonitor* m = omAlloc(Self);
        m->Init();
        m->set_header(mark);  // 保存原始 Mark Word
        m->set_owner(mark->has_locker() ? (void*)mark->locker() : NULL);
        // ... 其他初始化 ...

        // 将 Mark Word 设置为指向 ObjectMonitor 的指针
        object->release_set_mark(markOopDesc::encode(m));
        return m;
    }
}
```

---

## 七、锁升级全链路总结图

### 7.1 完整升级路径

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         synchronized 锁升级全链路                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│   对象刚创建                                                                            │
│       │                                                                                 │
│       ▼                                                                                 │
│  ┌──────────┐                                                                           │
│  │  无锁状态  │  lock=01, biased_lock=0                                                   │
│  │          │  Mark Word: [unused:25|hashcode:31|unused:1|age:4|biased_lock:0|lock:01]  │
│  └────┬─────┘                                                                           │
│       │                                                                                 │
│       │ 线程A首次进入synchronized（偏向锁已启用且无hashCode）                                │
│       │ CAS将threadId写入Mark Word                                                        │
│       ▼                                                                                 │
│  ┌──────────┐                                                                           │
│  │  偏向锁   │  lock=01, biased_lock=1                                                   │
│  │          │  Mark Word: [threadId:54|epoch:2|unused:1|age:4|biased_lock:1|lock:01]    │
│  └────┬─────┘                                                                           │
│       │                                                                                 │
│       │ 线程B尝试获取锁（存在竞争）                                                       │
│       │ 偏向锁撤销（SafePoint）                                                           │
│       ▼                                                                                 │
│  ┌──────────┐                                                                           │
│  │ 轻量级锁  │  lock=00                                                                  │
│  │          │  Mark Word: [ptr_to_lock_record:62|lock:00]                                │
│  └────┬─────┘                                                                           │
│       │                                                                                 │
│       │ CAS竞争失败 + 自旋失败                                                            │
│       │ 真正的多线程竞争                                                                  │
│       ▼                                                                                 │
│  ┌──────────┐                                                                           │
│  │ 重量级锁  │  lock=10                                                                  │
│  │          │  Mark Word: [ptr_to_heavyweight_monitor:62|lock:10]                        │
│  └────┬─────┘                                                                           │
│       │                                                                                 │
│       ▼                                                                                 │
│  线程阻塞等待（ObjectMonitor）                                                            │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 每步触发条件汇总

| 升级路径              | 触发条件                                            | Mark Word 变化                          |
|----------------------|----------------------------------------------------|-----------------------------------------|
| 无锁 → 偏向锁        | 线程首次进入 synchronized，且对象无 hashCode，偏向锁已启用 | threadId 写入，biased_lock=1             |
| 无锁 → 轻量级锁      | 对象已有 hashCode（无法偏向），或偏向锁延迟未开启         | ptr_to_lock_record 替换整个 Mark Word    |
| 偏向锁 → 轻量级锁    | 其他线程尝试获取偏向锁（撤销偏向锁）                    | 偏向锁撤销，ptr_to_lock_record 替换 Mark Word |
| 轻量级锁 → 重量级锁  | CAS 竞争失败 + 自旋超时                              | ptr_to_ObjectMonitor 替换 Mark Word       |

### 7.3 锁不可降级

**关键结论：锁只能升级，不能降级。**

一旦锁从轻量级锁膨胀为重量级锁，即使后来没有任何线程竞争，锁也不会降回轻量级锁或无锁状态。

#### 为什么不可降级？

1. **复杂性**：降级需要在 SafePoint 遍历所有线程栈，检查是否有线程还在使用 ObjectMonitor，代价高昂
2. **收益不确定**：如果之前存在竞争导致膨胀，后续很可能还会有竞争，降级后很快又要膨胀
3. **设计简化**：不可降级让 JVM 的锁管理更加简单和可靠

#### 唯一的例外

在 **STW（Stop-The-World）** 期间（如 Full GC），JVM 理论上可以回收不再使用的 ObjectMonitor，但这不属于"降级"——对象 Mark Word 中的锁标志位仍然保持为重量级锁。

### 7.4 特殊路径——调用 hashCode 的影响

```
对象创建 → 无锁
    │
    ├── 调用 hashCode() → hashCode 写入 Mark Word → 只能走轻量级锁
    │   └── 首次进入 synchronized → 直接轻量级锁（跳过偏向锁）
    │
    └── 不调用 hashCode() → 可以走偏向锁
        └── 首次进入 synchronized → 偏向锁
```

### 7.5 特殊路径——偏向锁延迟的影响

```
JVM 启动后 4 秒内：
    对象进入 synchronized → 直接轻量级锁（偏向锁尚未启用）

JVM 启动 4 秒后：
    对象进入 synchronized → 偏向锁（如果无 hashCode）
```

### 7.6 全链路状态流转详细表

| 当前状态    | 事件                         | 目标状态    | 操作                                                |
|-----------|------------------------------|-----------|-----------------------------------------------------|
| 无锁       | 线程A进入 synchronized        | 偏向锁     | CAS 写入 threadId                                   |
| 无锁       | 线程A进入 synchronized（有hashCode）| 轻量级锁  | CAS 替换 Mark Word 为 Lock Record 指针               |
| 偏向锁     | 线程A重入 synchronized        | 偏向锁     | 直接进入（零开销）                                    |
| 偏向锁     | 线程B尝试获取                 | 轻量级锁   | 撤销偏向锁（SafePoint），升级为轻量级锁                |
| 偏向锁     | 调用 hashCode()              | 轻量级锁   | 撤销偏向锁，hashCode 写入 Mark Word                    |
| 偏向锁     | 批量撤销                     | 无锁/轻量级锁| 该类所有对象不再使用偏向锁                              |
| 轻量级锁   | 线程A重入                     | 轻量级锁   | 新增 Lock Record，_displaced_header=NULL              |
| 轻量级锁   | 线程B CAS 竞争 + 自旋成功     | 轻量级锁   | 线程B 通过自旋获取锁                                  |
| 轻量级锁   | 线程B CAS 竞争 + 自旋失败     | 重量级锁   | 膨胀为 ObjectMonitor                                 |
| 重量级锁   | 线程释放锁                    | 重量级锁   | 唤醒 EntryList 中的线程，锁仍为重量级                  |
| 重量级锁   | 无竞争                        | 重量级锁   | 不降级！                                              |

---

## 八、锁粗化与锁消除

### 8.1 锁粗化（Lock Coarsening）

#### 什么是锁粗化？

当 JVM 检测到**连续多次对同一个对象加锁/解锁**时，会将多个加锁操作**合并为一个更大范围的锁**，减少加锁/解锁的次数。

#### 示例

优化前：

```java
public void method() {
    Object lock = new Object();
    synchronized (lock) {
        // 操作1
    }
    synchronized (lock) {
        // 操作2
    }
    synchronized (lock) {
        // 操作3
    }
}
```

优化后（锁粗化）：

```java
public void method() {
    Object lock = new Object();
    synchronized (lock) {
        // 操作1
        // 操作2
        // 操作3
    }
}
```

#### 锁粗化的典型场景

1. **循环内加锁**：
   ```java
   // 优化前
   for (int i = 0; i < 100; i++) {
       synchronized (lock) {
           list.add(i);
       }
   }

   // 优化后（锁粗化）
   synchronized (lock) {
       for (int i = 0; i < 100; i++) {
           list.add(i);
       }
   }
   ```

2. **连续的 synchronized 块**：如上例所示

#### JIT 编译器中的实现

锁粗化由 JIT 编译器在**编译期**完成。当 JIT 将字节码编译为本地机器码时，会分析同步块的序列，如果发现可以粗化，就合并这些同步块。

```cpp
// src/hotspot/share/opto/escape.cpp
// JIT 中的锁粗化逻辑
void ConnectionGraph::process_lock_coarsening() {
    // 遍历所有同步块
    // 如果发现连续的、对同一对象的加锁/解锁操作
    // 则合并为一个更大范围的锁
}
```

### 8.2 锁消除（Lock Elimination）

#### 什么是锁消除？

当 JVM 通过**逃逸分析（Escape Analysis）**确定一个锁对象**不可能被其他线程访问到**时，就会**消除这个锁**，完全去除同步操作。

#### 逃逸分析

逃逸分析是 JIT 编译器的一种分析技术，用于确定对象的作用域：

- **不逃逸（NoEscape）**：对象只在方法内部使用，不会逃逸到方法外
- **方法逃逸（ArgEscape）**：对象作为参数传递给其他方法
- **线程逃逸（GlobalEscape）**：对象可能被其他线程访问

只有**不逃逸**的对象上的锁才能被消除。

#### 示例

```java
public void method() {
    // 这个 lock 对象是局部变量，不会逃逸到其他线程
    Object lock = new Object();
    synchronized (lock) {
        // JVM 确定锁不会被其他线程访问 → 锁消除
        System.out.println("hello");
    }
}
```

优化后：

```java
public void method() {
    Object lock = new Object();
    // synchronized 被完全消除
    System.out.println("hello");
}
```

#### 常见锁消除场景——StringBuffer / Vector

```java
public String concat(String s1, String s2) {
    // StringBuffer 的 append 方法是 synchronized 的
    // 但 sb 是局部变量，不会逃逸 → 锁消除
    StringBuffer sb = new StringBuffer();
    sb.append(s1);
    sb.append(s2);
    return sb.toString();
}
```

```java
public int sum(List<Integer> list) {
    // Vector 的 get 方法是 synchronized 的
    // 但 list 不会逃逸 → 锁消除
    Vector<Integer> v = new Vector<>();
    v.add(1);
    v.add(2);
    return v.get(0) + v.get(1);
}
```

#### 逃逸分析的条件

JVM 需要满足以下条件才能进行逃逸分析和锁消除：

1. **开启了逃逸分析**：`-XX:+DoEscapeAnalysis`（JDK 7+ 默认开启）
2. **对象不逃逸**：对象的生命周期限于方法内部
3. **JIT 编译**：只有在 JIT 编译的代码中才会进行锁消除（解释执行不会）
4. **足够高的执行频率**：方法需要被频繁调用，触发 JIT 编译

#### 验证锁消除

```java
public class LockEliminationTest {
    static int I = 0;

    public static void main(String[] args) {
        // -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:+PrintEliminateLocks
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000000; i++) {
            concat("a", "b");
        }
        long end = System.currentTimeMillis();
        System.out.println("耗时: " + (end - start) + "ms");
    }

    public static String concat(String s1, String s2) {
        StringBuffer sb = new StringBuffer();
        sb.append(s1);  // synchronized 方法，但锁可消除
        sb.append(s2);
        return sb.toString();
    }
}
```

对比：
- 开启锁消除（`-XX:+EliminateLocks`）：非常快
- 关闭锁消除（`-XX:-EliminateLocks`）：明显更慢

### 8.3 锁粗化 vs 锁消除

| 特性       | 锁粗化                               | 锁消除                          |
|-----------|-------------------------------------|-------------------------------|
| 核心思想   | 合并多个小锁为一个大的锁                | 完全去除不必要的锁              |
| 前提条件   | 连续多次对同一对象加锁/解锁              | 锁对象不逃逸（逃逸分析）        |
| 结果      | 减少加锁/解锁次数                      | 完全消除同步开销               |
| JVM 参数  | `-XX:+EliminateLocks`              | `-XX:+DoEscapeAnalysis`      |

---

## 九、常见面试问题

### Q1: synchronized 的锁升级过程是怎样的？

**答**：

synchronized 的锁升级过程为：**无锁 → 偏向锁 → 轻量级锁 → 重量级锁**，且只能升级不可降级。

1. **无锁 → 偏向锁**：当线程首次进入 synchronized 块时，如果对象没有 hashCode 且偏向锁已启用，JVM 通过 CAS 将线程 ID 写入 Mark Word，对象进入偏向锁状态。
2. **偏向锁 → 轻量级锁**：当其他线程尝试获取偏向锁时，需要撤销偏向锁（在 SafePoint），然后通过 CAS 将 Mark Word 替换为指向 Lock Record 的指针，升级为轻量级锁。
3. **轻量级锁 → 重量级锁**：当 CAS 竞争失败且自旋也失败时，锁膨胀为重量级锁，分配 ObjectMonitor，竞争线程进入阻塞状态。

### Q2: 为什么调用 hashCode() 后无法进入偏向锁？

**答**：

因为在 64 位 Mark Word 中，`identity_hashcode`（31 bit）和 `threadId`（54 bit）占据**同一块内存区域**，二者互斥。调用 `hashCode()` 后，哈希值被写入 Mark Word 的这个区域，偏向锁需要写入 `threadId` 时就没有空间了，因此对象无法进入偏向锁状态，只能从无锁直接升级到轻量级锁。

### Q3: 偏向锁为什么需要延迟开启？

**答**：

JVM 在启动过程中，大量核心类（如 ClassLoader、System 等）的初始化代码使用 synchronized，且这些场景中存在多线程竞争。如果一开始就启用偏向锁，会导致大量偏向锁撤销操作（需要在 SafePoint 执行，代价高昂），反而降低启动性能。因此 JVM 延迟约 4 秒后（`-XX:BiasedLockingStartupDelay`）再启用偏向锁。

### Q4: 偏向锁撤销为什么要在 SafePoint？

**答**：

撤销偏向锁需要检查持有偏向锁的线程的状态——是否还在 synchronized 块中。这需要遍历线程栈中的 Lock Record，而遍历线程栈必须保证线程栈的一致性（不能在遍历过程中线程还在修改栈帧），因此需要等到所有线程到达 SafePoint（全局暂停）才能安全地进行。

### Q5: 轻量级锁中 Lock Record 的 _displaced_header 为什么重入时设为 NULL？

**答**：

设为 NULL 是为了在解锁时区分"重入解锁"和"最终解锁"：

- `_displaced_header = NULL` → 这是重入的解锁，只需简单地从栈中移除 Lock Record，不需要操作对象的 Mark Word
- `_displaced_header != NULL` → 这是最后一次解锁，需要用 CAS 将 Displaced Mark Word 恢复到对象 Mark Word

如果不区分，每次解锁都尝试 CAS 恢复 Mark Word，会导致重入场景下频繁的 CAS 操作，而且中间的 CAS 操作可能与其他线程的 CAS 产生竞争，导致错误。

### Q6: 锁升级为什么不可降级？

**答**：

1. **降级代价高昂**：需要在 SafePoint 遍历所有线程栈，检查是否有线程还在使用 ObjectMonitor
2. **收益不确定**：如果之前存在竞争导致膨胀，后续很可能还会有竞争，降级后又要膨胀
3. **设计简化**：不可降级让 JVM 的锁管理更加简单可靠

实际上，在某些 GC 的 STW 阶段，JVM 会回收不再使用的 ObjectMonitor，但这不属于主动降级。

### Q7: 批量重偏向和批量撤销的区别？

**答**：

| 特性       | 批量重偏向                           | 批量撤销                        |
|-----------|-------------------------------------|-------------------------------|
| 触发阈值   | 20 次撤销                            | 40 次撤销                      |
| 效果      | 允许对象重偏向到新线程                  | 该类所有对象永久禁用偏向锁       |
| epoch 处理 | 递增 epoch，允许重新 CAS 写入 threadId | 将类的 biased_lock 设为 0      |
| 可逆性    | 可逆（下次 epoch 变化时可再次重偏向）    | 不可逆（该类永久禁用偏向锁）     |

### Q8: 重量级锁中 CXQ 和 EntryList 的区别？

**答**：

- **CXQ（Contention Queue）**：新来的竞争者首先进入这个队列，采用栈结构（LIFO），新节点插入头部。使用 LIFO 可以减少 CAS 竞争。
- **EntryList**：当锁释放时，CXQ 中的节点会被移动到 EntryList，采用队列结构（FIFO），保证一定的公平性。

两段式队列的设计平衡了**插入效率**（CXQ 的 LIFO 快速插入）和**公平性**（EntryList 的 FIFO 保证先来先服务）。

### Q9: 适应性自旋是什么？

**答**：

适应性自旋是 JDK 6 引入的优化，自旋次数不是固定的，而是根据**前一次在同一个锁上的自旋结果**动态调整：

- 上次自旋成功 → 增加自旋次数（认为这次也大概率成功）
- 上次自旋失败 → 减少自旋次数甚至跳过自旋（认为这次也大概率失败）

这样可以根据锁的实际竞争情况自适应调整，避免在竞争激烈时白白浪费 CPU 自旋。

### Q10: JDK 15 为什么废弃偏向锁？

**答**：

JEP 374 废弃偏向锁的原因：

1. **现代硬件 CAS 已经非常快**：偏向锁"避免 CAS"的优化在早期硬件上收益明显，但现代 CPU 的 CAS 指令已经非常高效
2. **撤销代价过高**：偏向锁撤销需要 SafePoint，高并发下频繁撤销反而降低性能
3. **与低延迟 GC 不兼容**：Shenandoah、ZGC 等致力于消除 STW，而偏向锁撤销需要 SafePoint
4. **维护成本高**：偏向锁代码遍布 JVM 各处，增加复杂性
5. **实际收益有限**：真实工作负载中偏向锁的性能提升已微乎其微

### Q11: synchronized 和 ReentrantLock 的区别？

**答**：

| 特性          | synchronized                    | ReentrantLock                  |
|--------------|--------------------------------|-------------------------------|
| 实现层面      | JVM 层面（字节码指令）            | API 层面（Java 类）             |
| 锁获取方式    | 自动获取/释放                    | 手动 lock()/unlock()           |
| 可中断性      | 不可中断                         | 可中断（lockInterruptibly()）   |
| 公平性       | 非公平                           | 可选公平/非公平                 |
| 条件变量      | 单个（wait/notify）              | 多个（Condition）              |
| 锁升级       | 有（偏向→轻量→重量）              | 无（基于 CAS + AQS）           |
| 可重入性      | 可重入                           | 可重入                         |
| 锁绑定条件    | 单条件                           | 多条件                         |

### Q12: 对象的 Mark Word 在 GC 中有什么作用？

**答**：

Mark Word 中的 `age` 字段（4 bit）记录了对象的 GC 分代年龄。每次 Minor GC 后，如果对象在 Survivor 区存活，age 就 +1。当 age 达到阈值（默认 15，由 `-XX:MaxTenuringThreshold` 控制）时，对象从 Survivor 区晋升到老年代。

另外，在 GC 的标记阶段，Mark Word 会被临时设置为 `lock=11`（GC 标记状态），用于记录对象是否被标记为存活。

### Q13: 锁消除的逃逸分析是如何工作的？

**答**：

逃逸分析由 JIT 编译器在方法编译时进行，分析对象的作用域：

1. **方法内分析**：分析对象的引用是否"逃逸"出方法
2. **线程内分析**：分析对象的引用是否"逃逸"到其他线程
3. 如果对象**不逃逸**，则该对象上的所有同步操作都可以安全消除

逃逸分析不仅能用于锁消除，还能用于：
- **栈上分配**：不逃逸的对象可以在栈上分配（而非堆），方法结束自动回收
- **标量替换**：将对象拆解为基本类型，直接在寄存器中操作

### Q14: 什么是 ABA 问题？与 synchronized 锁升级有关吗？

**答**：

ABA 问题是指：CAS 操作读取的值从 A 变成 B 又变回 A，CAS 无法感知中间的变化。在 synchronized 锁升级中，ABA 问题基本不存在，因为：

1. Mark Word 的值在锁升级过程中是**单调变化**的（无锁→偏向→轻量→重量）
2. 锁不可降级，所以不会出现 Mark Word 回退的情况
3. 每次升级 Mark Word 都包含了不同的信息（线程 ID、Lock Record 指针、ObjectMonitor 指针）

但在偏向锁的批量重偏向中，epoch 值会循环使用（2 bit，0→1→2→3→0...），理论上存在 epoch 回绕的可能，但实际中 2 bit 的 epoch 足够使用，回绕的概率极低。

### Q15: 如何查看对象的锁状态？

**答**：

可以使用 JOL（Java Object Layout）工具查看对象的 Mark Word：

```xml
<dependency>
    <groupId>org.openjdk.jol</groupId>
    <artifactId>jol-core</artifactId>
    <version>0.17</version>
</dependency>
```

```java
import org.openjdk.jol.info.ClassLayout;

public class LockStateTest {
    public static void main(String[] args) throws InterruptedException {
        // 等待偏向锁启用
        Thread.sleep(5000);

        Object lock = new Object();
        System.out.println("无锁状态：");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());

        synchronized (lock) {
            System.out.println("偏向锁状态：");
            System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        }
    }
}
```

输出示例：
```
无锁状态：
java.lang.Object object internals:
 OFFSET  SIZE   TYPE DESCRIPTION                               VALUE
      0     4        (object header)                           01 00 00 00 (00000001 00000000 00000000 00000000) (1)
      4     4        (object header)                           00 00 00 00 (00000000 00000000 00000000 00000000) (0)
      8     4        (object header)                           e5 01 00 f8 (11100101 00000001 00000000 11111000) (-134217243)

偏向锁状态：
java.lang.Object object internals:
 OFFSET  SIZE   TYPE DESCRIPTION                               VALUE
      0     4        (object header)                           05 80 80 3b (00000101 10000000 10000000 00111011) (998242309)
      4     4        (object header)                           00 00 00 00 (00000000 00000000 00000000 00000000) (0)
      8     4        (object header)                           e5 01 00 f8 (11100101 00000001 00000000 11111000) (-134217243)
```

注意最后 3 bit：`001` 为无锁/偏向锁，`000` 为轻量级锁，`010` 为重量级锁。

---

## 附录A：JVM 相关参数速查表

| 参数                                    | 默认值    | 说明                       |
|----------------------------------------|----------|----------------------------|
| `-XX:+UseBiasedLocking`                | true (JDK14-) | 启用偏向锁（JDK15 废弃）    |
| `-XX:BiasedLockingStartupDelay`        | 4000     | 偏向锁延迟启用时间（毫秒）   |
| `-XX:BiasedLockingBulkRebiasThreshold` | 20       | 批量重偏向阈值              |
| `-XX:BiasedLockingBulkRevokeThreshold` | 40       | 批量撤销阈值               |
| `-XX:+DoEscapeAnalysis`                | true     | 启用逃逸分析               |
| `-XX:+EliminateLocks`                  | true     | 启用锁消除                  |
| `-XX:hashCode`                         | 5        | hashCode 生成策略           |
| `-XX:PreBlockSpin`                     | 10       | 自旋次数（JDK6 早期）        |

---

## 附录B：synchronized 字节码层面分析

### synchronized 方法

```java
public synchronized void method() {
    // 方法体
}
```

字节码：
```
public synchronized void method();
    descriptor: ()V
    flags: ACC_PUBLIC, ACC_SYNCHRONIZED
    ...
```

JVM 通过方法的 `ACC_SYNCHRONIZED` 标志位来识别同步方法，**不需要显式的 monitorenter/monitorexit 指令**。

### synchronized 代码块

```java
public void method() {
    synchronized (lock) {
        // 同步代码
    }
}
```

字节码：
```
public void method();
    Code:
      0: aload_1           // 加载 lock 引用
      1: monitorenter      // 进入同步块
      2: // 同步代码
      ...
      n: monitorexit       // 正常退出同步块
      n+1: goto end
      // 异常处理表
      n+2: aload_1
      n+3: monitorexit     // 异常退出同步块
      n+4: athrow
      end: return
    Exception table:
      from  to  target  type
        2    n   n+2    any
```

关键点：
1. `monitorenter` 指令在同步块开始处
2. 有**两个** `monitorexit`：一个正常退出，一个异常退出（保证异常时也能释放锁）
3. 异常处理表确保任何异常都会执行第二个 `monitorexit`

---

## 附录C：完整的锁升级时间线

```
时间线:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

T0: JVM 启动
    │ 偏向锁尚未启用
    │ 所有 synchronized → 直接走轻量级锁
    │
T1: 偏向锁延迟结束（~4秒后）
    │ BiasedLocking::enable_biased_locking()
    │ 所有类的 prototype_header 更新为可偏向
    │
T2: 对象A创建
    │ Mark Word: [0...0|0...0|0|0000|0|01] (无锁)
    │
T3: 线程1进入 synchronized(A)
    │ CAS: threadId → Mark Word
    │ Mark Word: [threadId|epoch|0|0000|1|01] (偏向锁)
    │ 后续重入: 只比较 threadId，零开销
    │
T4: 线程2尝试获取 synchronized(A)
    │ CAS 失败 → 请求撤销偏向锁
    │
T5: SafePoint 到达
    │ 遍历线程1的栈，检查是否还在 synchronized 块中
    │ ├── 在 → 偏向锁升级为轻量级锁
    │ │   Mark Word: [ptr_to_lock_record|00]
    │ └── 不在 → 撤销为无锁
    │     Mark Word: [0...0|hashcode|0|0000|0|01]
    │
T6: 线程2 CAS 获取轻量级锁
    │ 成功: Mark Word: [ptr_to_lock_record_2|00]
    │
T7: 线程3也尝试获取轻量级锁
    │ CAS 失败 → 自旋等待
    │
T8: 自旋超时
    │ 锁膨胀为重量级锁
    │ Mark Word: [ptr_to_ObjectMonitor|10]
    │ 线程3进入 ObjectMonitor._cxq → Park 阻塞
    │
T9: 线程2释放锁
    │ 唤醒 EntryList/CXQ 中的线程3
    │ 线程3获取锁
    │ Mark Word 仍然: [ptr_to_ObjectMonitor|10] (不降级!)
    │
T10: 所有线程退出
    │ Mark Word 仍然: [ptr_to_ObjectMonitor|10] (仍然不降级!)
    │ ObjectMonitor 可能被回收，但对象仍标记为重量级锁
```

---

## 附录D：ObjectMonitor 中线程的状态流转

```
                    ┌──────────────────┐
                    │   NEW / RUNNABLE  │
                    └────────┬─────────┘
                             │
                    进入 synchronized
                             │
                    ┌────────▼─────────┐
                    │   尝试获取锁       │
                    │  CAS(_owner=NULL)  │
                    └────────┬─────────┘
                       /              \
                   成功               失败
                    /                    \
           ┌───────▼──────┐    ┌─────────▼─────────┐
           │   获得锁      │    │  加入 CXQ 竞争队列  │
           │   _owner=Self │    │  自旋等待           │
           └───────┬──────┘    └─────────┬─────────┘
                   │                     │
              执行同步代码          自旋超时 / CAS失败
                   │                     │
              ┌────▼────┐         ┌──────▼──────┐
              │ 调用wait()│        │  Park 阻塞   │
              └────┬────┘         │  BLOCKED     │
                   │              └──────┬──────┘
          ┌────────▼────────┐            │
          │ 加入 WaitSet     │       被唤醒(unpark)
          │ WAITING/TIMED_   │            │
          │ WAITING          │     ┌──────▼──────┐
          └────────┬────────┘     │  重新尝试获取锁 │
                   │              └──────────────┘
             被notify()唤醒
                   │
          ┌────────▼────────┐
          │ 移出 WaitSet     │
          │ 加入 CXQ/EntryList│
          │ 重新竞争锁        │
          └─────────────────┘
```

---

## 附录E：自旋优化细节补充

### E.1 自旋等待的三种模式

HotSpot 中的自旋等待有三种模式：

1. **固定自旋（Fixed Spin）**：自旋固定次数后放弃
2. **适应性自旋（Adaptive Spin）**：根据历史数据动态调整自旋次数
3. **无自旋（No Spin）**：直接进入阻塞

### E.2 自旋在 ObjectMonitor 中的位置

在重量级锁的 `enter()` 方法中，自旋等待发生在**加入 CXQ 之前**：

```
尝试 CAS 获取锁
    ↓ 失败
第一次自旋（快速自旋，次数较少）
    ↓ 失败
尝试 CAS 获取锁
    ↓ 失败
加入 CXQ 队列
    ↓
在 CXQ 中循环（包含自旋 + Park 交替）
    ↓
    ├── 自旋期间获取锁成功 → 移出 CXQ
    └── 自旋超时 → Park 阻塞
```

### E.3 自旋与 CPU 缓存的关系

自旋等待有一个重要的**副作用**：自旋期间，线程会反复读取 Mark Word，这会导致该缓存行始终保持在当前 CPU 核心的 L1/L2 缓存中。当锁释放时（Mark Word 被修改），自旋线程可以**几乎立即感知**到锁的释放，减少了缓存一致性协议（MESI）带来的延迟。

---

## 附录F：Mark Word 与锁状态的完整映射表（64位 JVM）

| bit 位置 | 63-39 (25bit) | 38-8 (31bit) | 7 (1bit) | 6-3 (4bit) | 2 (1bit) | 1-0 (2bit) | 状态     |
|----------|:------------:|:------------:|:--------:|:----------:|:--------:|:----------:|---------|
| 无锁     | unused(0)     | hashcode     | unused(0)| age        | 0        | 01         | 无锁     |
| 偏向锁   | threadId(54bit) ←─────→ | epoch(2bit) | unused(0)| age        | 1        | 01         | 偏向锁   |
| 轻量级锁 | ptr_to_lock_record(62bit) ←────────────────────────────────────→ |        | 00         | 轻量级锁 |
| 重量级锁 | ptr_to_ObjectMonitor(62bit) ←──────────────────────────────────→ |        | 10         | 重量级锁 |
| GC标记   | 由 GC 使用 ←─────────────────────────────────────────────────→ |        | 11         | GC标记   |

> 注意：偏向锁的 threadId 是 54 bit，从 bit 8 开始到 bit 61；epoch 是 2 bit，在 bit 6-7 位置。这与上表中的分段略有不同，但核心思想一致——threadId + epoch 共占 56 bit，替代了无锁状态下的 unused(25bit) + hashcode(31bit)。

---

## 附录G：HotSpot 源码关键文件索引

| 文件路径                                        | 内容                          |
|------------------------------------------------|-------------------------------|
| `src/hotspot/share/oops/markOop.hpp`           | Mark Word 定义与操作           |
| `src/hotspot/share/oops/oop.cpp`               | 对象基础操作，hashCode 计算     |
| `src/hotspot/share/runtime/synchronizer.cpp`   | 锁获取/释放核心逻辑            |
| `src/hotspot/share/runtime/objectMonitor.cpp`  | ObjectMonitor 实现             |
| `src/hotspot/share/runtime/objectMonitor.hpp`   | ObjectMonitor 数据结构         |
| `src/hotspot/share/runtime/biasedLocking.cpp`   | 偏向锁逻辑                     |
| `src/hotspot/share/interpreter/interpreterRuntime.cpp` | 解释器中的 monitorenter 实现 |
| `src/hotspot/share/oops/basicLock.hpp`         | Lock Record 定义               |
| `src/hotspot/share/opto/escape.cpp`            | JIT 逃逸分析与锁消除           |

---

> **总结**：synchronized 锁升级是 HotSpot JVM 在 JDK 6 引入的重要优化，通过"锁升级"策略，让 synchronized 在不同竞争程度下使用不同级别的锁，尽量以最小的代价完成同步。理解锁升级全链路的核心是理解 **Mark Word 的空间复用**——不同锁状态下同一块内存存储不同信息，这决定了锁升级的每一步都伴随着 Mark Word 的变化。而锁升级的单向性（不可降级）则体现了 JVM 在设计上的取舍——宁可保留更高开销的锁状态，也不愿冒降级后再升级的风险。
