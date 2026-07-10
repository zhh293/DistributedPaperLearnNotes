# JVM 与 GC 优化架构设计

## 一、问题背景

### 1.1 为什么需要关注 JVM 与 GC

在大规模 Java 后端服务中，JVM（Java Virtual Machine）是所有业务逻辑的运行载体。JVM 的内存管理与垃圾回收（Garbage Collection）机制直接决定了服务的延迟、吞吐量与稳定性。一个未经优化的 JVM 配置可能导致以下严重问题：

**延迟毛刺问题：**

- GC 的 Stop-The-World（STW）暂停直接影响请求响应时间
- 一次 Full GC 可能导致数百毫秒甚至数秒的停顿，触发上游超时
- TP99/TP9999 指标因 GC 暂停而急剧恶化，影响用户体验

**吞吐量下降问题：**

- 频繁的 GC 导致 CPU 资源被大量消耗在垃圾回收上
- GC 吞吐量低于 99.99% 时，服务整体处理能力显著下降
- 当每分钟 GC 平均耗时（gc.meantime）超过 6ms 时，单机 GC 吞吐量将低于四个九

**内存溢出问题：**

- 内存泄漏（Memory Leak）导致可用堆空间逐渐缩小，最终 OOM（OutOfMemoryError）
- 大对象分配不当导致频繁 Full GC
- MetaSpace 膨胀导致类加载空间不足

**服务稳定性问题：**

- GC 导致的长时间 STW 触发健康检查失败，节点被摘除
- Promotion Failure 引发长时间 Full GC，服务不可用
- Concurrent Mode Failure 导致 CMS 退化为 Serial Old，停顿时间骤增

### 1.2 GC 的三层语义

理解 GC 需要区分三个不同层面的含义：

| 层面 | 英文 | 含义 | 说明 |
|------|------|------|------|
| 技术理论 | Garbage Collection | 垃圾回收技术 | 一种自动内存管理的理论与方法论 |
| 具体工具 | Garbage Collector | 垃圾回收器 | JVM 中具体的 GC 实现，如 CMS、G1、ZGC |
| 执行动作 | Garbage Collecting | 垃圾回收动作 | 一次具体的 GC 执行过程 |

### 1.3 核心评估指标

评估 GC 表现需要关注两个核心指标：

**延迟（Latency）：**

- 定义：单次 GC STW 暂停的最大时间
- 目标：单次 GC 暂停时间 ≤ 应用 TP9999 要求
- 计算：`max_gc_pause ≤ service_tp9999_target`
- 例如服务 TP9999 要求 200ms，则单次 GC 暂停不应超过 200ms

**吞吐量（Throughput）：**

- 定义：应用线程执行时间占总运行时间的比例
- 目标：GC 吞吐量 ≥ 99.99%（四个九）
- 计算：`gc_throughput = 1 - Σ(gc_count × avg_gc_pause) / total_running_time`
- 每分钟 GC 平均耗时超过 6ms 意味着 GC 吞吐量已低于四个九

### 1.4 引入 GC 调优带来的复杂度

GC 调优并非简单的参数调整，它涉及对 JVM 内存模型、对象生命周期、应用访问模式的深入理解：

1. **参数相互影响**：JVM 参数之间存在复杂的关联关系，单一参数调整可能引发连锁反应
2. **场景差异大**：不同业务场景（高吞吐 vs 低延迟）适用不同的 GC 策略
3. **版本演进快**：从 CMS 到 G1 再到 ZGC，每代 GC 的调优方法论差异显著
4. **诊断门槛高**：GC 问题的根因分析需要结合 GC 日志、堆转储、线程分析等多维度信息
5. **线上调优风险**：不当的参数调整可能导致线上服务不可用

本文将系统性地阐述 JVM 内存模型、GC 算法原理、主流收集器特性、GC 问题诊断方法与调优实践。

---

## 二、整体架构设计

### 2.1 JVM 内存模型总览

JVM 内存分为多个区域，每个区域承担不同的职责，GC 主要管理堆内存，但其他区域的异常同样会影响服务稳定性：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              JVM 进程内存                                    │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          堆内存 (Heap)                                │  │
│  │                     -Xms / -Xmx 控制大小                              │  │
│  │                                                                       │  │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────────┐ │  │
│  │  │    年轻代 (Young Generation) │  │     老年代 (Old Generation)      │ │  │
│  │  │        -Xmn 控制大小         │  │                                 │ │  │
│  │  │                             │  │  存放长期存活的对象                │ │  │
│  │  │  ┌───────┐ ┌────┐ ┌────┐   │  │  经过多次 Minor GC 晋升而来       │ │  │
│  │  │  │ Eden  │ │ S0 │ │ S1 │   │  │  大对象也可能直接分配到这里        │ │  │
│  │  │  │       │ │    │ │    │   │  │                                 │ │  │
│  │  │  │ 80%   │ │10% │ │10% │   │  │  CMS / G1 / ZGC 主要工作区域     │ │  │
│  │  │  └───────┘ └────┘ └────┘   │  │                                 │ │  │
│  │  │  SurvivorRatio=8 (默认)     │  │                                 │ │  │
│  │  └─────────────────────────────┘  └─────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────┐  ┌───────────────────────┐                      │
│  │   MetaSpace (元空间)    │  │  Direct Memory (堆外)  │                      │
│  │   存放类元信息、方法数据  │  │  DirectByteBuffer       │                      │
│  │   替代 JDK7 的 PermGen  │  │  通过 Cleaner#clean 回收 │                      │
│  │   -XX:MaxMetaspaceSize │  │  -XX:MaxDirectMemorySize│                      │
│  └───────────────────────┘  └───────────────────────┘                      │
│                                                                             │
│  ┌───────────────────────┐  ┌───────────────────────┐                      │
│  │   线程栈 (Thread Stack) │  │  代码缓存 (Code Cache) │                      │
│  │   -Xss 控制每线程大小    │  │  存放 JIT 编译的机器码   │                      │
│  │   默认 1MB (64位JVM)    │  │  -XX:ReservedCodeCacheSize│                    │
│  └───────────────────────┘  └───────────────────────┘                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 堆内存分代模型

JVM 堆内存采用分代模型（Generational Model），基于"大多数对象朝生暮死"的弱分代假说：

```
┌────────────────────────────────────────────────────────────────────┐
│                        堆内存 (Heap)                                │
│                                                                    │
│   Young Generation (年轻代)          Old Generation (老年代)         │
│   ┌──────────────────────┐          ┌────────────────────────┐     │
│   │                      │          │                        │     │
│   │  ┌────────────────┐  │  晋升     │  长期存活对象            │     │
│   │  │     Eden       │  │ ──────→  │  大对象直接分配          │     │
│   │  │  新对象分配区    │  │          │                        │     │
│   │  │  TLAB 优化分配   │  │          │  触发条件:              │     │
│   │  └────────────────┘  │          │  1. 年龄达到阈值         │     │
│   │  ┌──────┐ ┌──────┐  │          │  2. Survivor放不下       │     │
│   │  │  S0  │ │  S1  │  │          │  3. 大对象直接分配        │     │
│   │  │(From)│ │ (To) │  │          │  4. 动态年龄判断          │     │
│   │  └──────┘ └──────┘  │          │                        │     │
│   │                      │          │                        │     │
│   │  Minor GC / Young GC │          │  Major GC / Full GC    │     │
│   └──────────────────────┘          └────────────────────────┘     │
└────────────────────────────────────────────────────────────────────┘
```

### 2.3 关键内存概念

**TLAB（Thread Local Allocation Buffer）：**

TLAB 是 JVM 为每个线程在 Eden 区预留的一小块内存。对象优先在 TLAB 中分配，避免多线程竞争。TLAB 分配基于 CAS（Compare-And-Swap）操作，实现线程本地的高效 Eden 区分配：

```java
/**
 * TLAB 分配原理示意（伪代码）
 * 每个线程持有自己的 TLAB，对象分配无需全局锁
 */
public class TLABAllocationDemo {

    /**
     * 对象分配的尝试路径：
     * 1. 尝试在当前线程的 TLAB 中分配（最快，无锁）
     * 2. TLAB 空间不足时，申请新的 TLAB（CAS 操作）
     * 3. TLAB 分配失败，在 Eden 区直接分配（CAS 操作）
     * 4. Eden 区空间不足，触发 Young GC
     * 5. Young GC 后仍不足，尝试在 Old Gen 分配
     * 6. Old Gen 也不足，触发 Full GC
     * 7. Full GC 后仍不足，抛出 OOM
     */
    public void allocationPath() {
        // JVM 参数控制 TLAB:
        // -XX:+UseTLAB              启用 TLAB（默认开启）
        // -XX:TLABSize=256k         初始 TLAB 大小
        // -XX:+ResizeTLAB           允许 JVM 动态调整 TLAB 大小
        // -XX:TLABWasteTargetPercent TLAB 浪费比例阈值
        byte[] obj = new byte[1024]; // 优先在 TLAB 中分配
    }
}
```

**Card Table（卡表）：**

Card Table 用于跟踪跨代引用关系。Old Gen 中的对象可能引用 Young Gen 中的对象，如果 Minor GC 时要扫描整个 Old Gen 来找出这些引用，效率极低。Card Table 将 Old Gen 划分为若干个 512 字节大小的 Card，当 Old Gen 中某个 Card 包含指向 Young Gen 的引用时，该 Card 被标记为 Dirty：

```java
/**
 * Card Table 原理示意
 * 
 * Old Generation 被划分为多个 Card（每个 512 字节）
 * Card Table 是一个字节数组，每个字节对应一个 Card
 * 
 * Old Gen:  [Card0][Card1][Card2][Card3][Card4]...
 * Card Table: [ 0 ][ 1  ][ 0  ][ 1  ][ 0  ]...
 *                    ↑            ↑
 *              包含跨代引用     包含跨代引用
 * 
 * Minor GC 时只需扫描 Card Table 中标记为 Dirty 的 Card
 * 而不是扫描整个 Old Generation
 */
public class CardTableDemo {

    // 写屏障（Write Barrier）的伪代码
    // 当对象的引用字段被修改时，JVM 插入写屏障代码
    // 将对应的 Card 标记为 Dirty
    
    // CARD_TABLE[address >> 9] = DIRTY;
    // 其中 address 是被修改字段所在对象的地址
    // >> 9 等价于除以 512（一个 Card 的大小）
}
```

**对象分配方式：**

| 分配方式 | 原理 | 优点 | 缺点 | 适用场景 |
|---------|------|------|------|---------|
| 空闲列表（Free List） | 维护一个可用内存块链表，分配时查找合适大小的块 | 支持非连续空间，适合有碎片的场景 | 随机 I/O 变为顺序 I/O 开销，需额外空间维护链表 | CMS（Mark-Sweep 产生碎片） |
| 指针碰撞（Bump Pointer） | 维护一个指针，分配时指针向前移动对象大小的距离 | 极高效率，只需移动指针 | 要求内存空间完全连续 | 复制算法、标记-整理算法 |

### 2.4 GC 调优整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        GC 调优整体架构                               │
│                                                                     │
│  ┌───────────────┐    ┌───────────────┐    ┌───────────────────┐   │
│  │  监控采集层     │    │   诊断分析层    │    │    决策执行层       │   │
│  │               │    │               │    │                   │   │
│  │ ● GC 日志     │    │ ● GC 日志解析  │    │ ● 参数调优建议     │   │
│  │ ● JMX 指标    │ →  │ ● 堆转储分析   │ →  │ ● 收集器选型      │   │
│  │ ● 堆转储      │    │ ● 线程分析     │    │ ● 内存容量规划     │   │
│  │ ● arthas      │    │ ● 根因定位     │    │ ● 代码级优化      │   │
│  └───────────────┘    └───────────────┘    └───────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                     持续反馈闭环                               │   │
│  │                                                             │   │
│  │  调优前基线 → 制定方案 → 灰度验证 → 效果评估 → 全量推广      │   │
│  │       ↑                                         │           │   │
│  │       └─────────── 持续监控迭代 ←───────────────┘           │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 三、核心链路设计

### 3.1 垃圾识别算法

#### 3.1.1 引用计数法（Reference Counting）

引用计数法为每个对象维护一个引用计数器，当有新引用指向该对象时计数器加 1，当引用失效时计数器减 1，计数器为 0 的对象即为垃圾：

```java
/**
 * 引用计数法原理演示
 * 
 * 注意：JVM 主流实现并未采用引用计数法
 * 这里仅作为理解 GC 算法的参考
 */
public class ReferenceCountingDemo {

    // 模拟引用计数
    private int referenceCount = 0;
    private Object field;

    /**
     * 引用计数法的特点：
     * 1. 每次引用变更都需要更新计数器
     * 2. 在多线程环境下，计数器的更新需要同步（开销大）
     * 3. 常见误区："引用计数法无法处理循环引用"
     *    实际上可以通过 Recycler 算法等手段处理循环引用
     *    但在高性能 JVM 场景下，同步开销是更主要的放弃原因
     */
    public void demonstrate() {
        // 场景1: 普通引用
        Object a = new Object();   // Object 引用计数 = 1
        Object b = a;              // Object 引用计数 = 2
        a = null;                  // Object 引用计数 = 1
        b = null;                  // Object 引用计数 = 0 → 可回收

        // 场景2: 循环引用
        ReferenceCountingDemo objA = new ReferenceCountingDemo();  // A.count = 1
        ReferenceCountingDemo objB = new ReferenceCountingDemo();  // B.count = 1
        objA.field = objB;  // B.count = 2
        objB.field = objA;  // A.count = 2
        objA = null;        // A.count = 1（仍 > 0，朴素引用计数无法回收）
        objB = null;        // B.count = 1（仍 > 0，朴素引用计数无法回收）
        
        // 虽然 Recycler 算法可以处理循环引用，
        // 但多线程场景下引用计数的同步开销是 JVM 放弃此方案的根本原因
    }

    /**
     * 引用计数法优缺点分析
     * 
     * 优点：
     * - 回收即时性好，计数归零立即可回收
     * - 暂停时间短，分摊在每次引用变更中
     * - 实现相对简单
     * 
     * 缺点：
     * - 多线程环境下计数器同步开销大（需要原子操作或锁）
     * - 每次引用赋值都有额外开销
     * - 朴素实现不能处理循环引用（可通过 Recycler 算法解决）
     * - 内存额外开销（每个对象需要计数器字段）
     */
}
```

#### 3.1.2 可达性分析法（Tracing GC / Reachability Analysis）

JVM 主流采用的垃圾识别方法。从一组称为 GC Roots 的根对象出发，沿着引用链遍历所有可达对象，构成一张连通图。不在连通图中的对象即为垃圾：

```java
/**
 * 可达性分析法演示
 * 
 * GC Roots 包括：
 * 1. 虚拟机栈（栈帧中的本地变量表）中引用的对象
 * 2. 方法区中静态属性引用的对象
 * 3. 方法区中常量引用的对象
 * 4. 本地方法栈中 JNI 引用的对象
 * 5. JVM 内部引用（系统类加载器、基本类型对应的 Class 对象等）
 * 6. 所有被同步锁（synchronized）持有的对象
 * 7. JMXBean、JVMTI 中注册的回调等
 */
public class ReachabilityAnalysisDemo {

    // 静态属性 → GC Root
    private static Object staticRef = new Object();

    // 常量 → GC Root
    private static final String CONSTANT = "gc-root-constant";

    public void demonstrateGCRoots() {
        // 局部变量 → GC Root（在方法执行期间）
        Object localRef = new Object();

        // 从 GC Roots 出发的引用链
        // GC Root (localRef) → ObjectA → ObjectB → ObjectC
        //                                  ↓
        //                               ObjectD
        //
        // 可达对象：ObjectA, ObjectB, ObjectC, ObjectD
        // 不可达对象：ObjectE（无任何 GC Root 可达路径）→ 垃圾

        // 可达性分析需要多轮标记：
        // 第一轮：从 GC Roots 出发标记所有直接引用
        // 后续轮：递归标记间接引用
        // 直到没有新的可达对象被发现
    }

    /**
     * 三色标记法（Tricolor Marking）
     * 
     * 可达性分析的具体实现通常使用三色标记抽象：
     * - 白色：尚未被 GC 扫描到的对象，GC 结束时仍为白色则为垃圾
     * - 灰色：已被 GC 扫描到，但其引用的对象尚未全部扫描完
     * - 黑色：已被 GC 扫描到，且其引用的对象也已全部扫描完
     * 
     * 并发标记的两个问题：
     * 1. 浮动垃圾：已标记为黑色的对象在并发标记期间变为垃圾
     *    → 本轮不回收，下轮处理（可容忍）
     * 2. 漏标（对象消失）：应该存活的对象未被标记
     *    → 必须解决，否则程序出错
     * 
     * 解决漏标的两种方案：
     * - 增量更新（Incremental Update）：CMS 采用，记录新增引用
     * - 原始快照（SATB, Snapshot At The Beginning）：G1 采用，记录删除引用
     */
}
```

#### 3.1.3 Java 引用类型体系

```java
import java.lang.ref.*;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 四种引用类型与 GC 的关系
 */
public class ReferenceTypesDemo {

    /**
     * 1. 强引用（Strong Reference）
     * 最常见的引用类型，只要强引用存在，GC 绝不回收该对象
     */
    public void strongReference() {
        Object obj = new Object(); // 强引用
        obj = null; // 断开强引用后，对象才可被 GC 回收
    }

    /**
     * 2. 软引用（Soft Reference）
     * 内存不足时才会被回收，适用于内存敏感的缓存
     */
    public void softReference() {
        // 适用场景：图片缓存、页面缓存等
        SoftReference<byte[]> softRef = new SoftReference<>(new byte[10 * 1024 * 1024]);
        
        System.out.println("GC 前: " + softRef.get()); // 非 null
        System.gc();
        System.out.println("GC 后（内存充足）: " + softRef.get()); // 通常仍非 null
        
        // 当 JVM 内存不足，准备抛出 OOM 之前，会回收所有软引用对象
        // 适合做内存敏感的缓存：内存够用时保留，不够用时释放
    }

    /**
     * 3. 弱引用（Weak Reference）
     * 下次 GC 时一定被回收，不论内存是否充足
     */
    public void weakReference() {
        WeakReference<Object> weakRef = new WeakReference<>(new Object());
        
        System.out.println("GC 前: " + weakRef.get()); // 非 null
        System.gc();
        System.out.println("GC 后: " + weakRef.get()); // 大概率为 null

        // 典型应用：WeakHashMap
        // ThreadLocal 的 Entry 也使用弱引用指向 Key
        Map<Object, String> weakMap = new WeakHashMap<>();
        Object key = new Object();
        weakMap.put(key, "value");
        key = null; // Key 无强引用后，下次 GC 时 Entry 会被清除
    }

    /**
     * 4. 虚引用（Phantom Reference）
     * 最弱的引用，无法通过虚引用获取对象，仅用于跟踪对象被回收的时机
     */
    public void phantomReference() {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        PhantomReference<Object> phantomRef = new PhantomReference<>(new Object(), queue);
        
        System.out.println("get(): " + phantomRef.get()); // 永远返回 null
        
        System.gc();
        
        // 对象被回收后，虚引用会被加入 ReferenceQueue
        // 典型应用：DirectByteBuffer 通过 Cleaner（虚引用的子类）
        // 在堆外内存的 DirectByteBuffer 对象被 GC 回收时，触发堆外内存释放
        Reference<?> ref = queue.poll();
        if (ref != null) {
            System.out.println("对象已被回收，可执行清理逻辑");
        }
    }
}
```

### 3.2 垃圾回收算法

#### 3.2.1 标记-清除算法（Mark-Sweep）

```java
/**
 * 标记-清除算法原理
 * 
 * 两个阶段：
 * 1. 标记阶段：从 GC Roots 出发，标记所有可达对象，时间复杂度 O(L)，L 为存活对象数量
 * 2. 清除阶段：遍历整个堆，清除未被标记的对象，时间复杂度 O(H)，H 为堆大小
 * 
 * 标记阶段使用位图（Bitmap）标记，避免修改对象头
 * 
 * 内存布局变化示意：
 * 
 * 标记前：  [A][B][C][ ][D][ ][E][F][ ][G]
 * 标记后：  [A*][ ][C*][ ][D*][ ][ ][F*][ ][ ]     (* 表示可达对象)
 * 清除后：  [A][ ][C][ ][D][ ][ ][F][ ][ ]
 *               ↑        ↑     ↑ ↑     ↑ ↑
 *             碎片      碎片   碎片    碎片          ← 产生内存碎片
 * 
 * 优点：
 * - 不需要移动对象，对象地址不变
 * - 与使用该对象地址的外部代码兼容性好
 * 
 * 缺点：
 * - 产生内存碎片，可能导致大对象分配失败
 * - 清除阶段需要遍历整个堆，效率与堆大小相关
 * - 需要使用空闲列表（Free List）管理可用空间
 */
public class MarkSweepDemo {

    // CMS 收集器的 Concurrent Sweep 阶段采用此算法
    // 这也是 CMS 需要 -XX:+UseCMSCompactAtFullCollection 进行压缩的原因
    
    // 清除效率比较：
    // 1. 标记阶段时间取决于存活对象数量 O(L)
    // 2. 清除阶段时间取决于堆空间大小 O(H)
    // 3. 总耗时 = O(L) + O(H)
}
```

#### 3.2.2 标记-整理算法（Mark-Compact）

```java
/**
 * 标记-整理算法原理
 * 
 * 两个阶段：
 * 1. 标记阶段：同标记-清除，标记所有可达对象，时间复杂度 O(L)
 * 2. 整理阶段：将存活对象向一端移动压缩，时间复杂度 O(L)
 * 
 * 内存布局变化示意：
 * 
 * 标记前：  [A][ ][C][ ][D][ ][ ][F][ ][ ]
 * 整理后：  [A][C][D][F][ ][ ][ ][ ][ ][ ]
 *           ←── 紧凑 ──→ ←── 连续可用空间 ──→
 * 
 * 三种经典整理算法：
 * 
 * 1. Two-Finger 算法：
 *    - 两个指针分别从堆的头和尾向中间移动
 *    - 适用于所有对象大小相同的场景
 * 
 * 2. Lisp2 算法：
 *    - 需要额外的 forwarding pointer 空间
 *    - 三次遍历：计算新地址 → 更新引用 → 移动对象
 * 
 * 3. Threaded Compaction 算法：
 *    - 利用对象引用字段临时存储转发地址
 *    - 不需要额外空间，但实现复杂
 * 
 * 优点：
 * - 无内存碎片，支持指针碰撞（Bump Pointer）分配
 * - 分配效率高
 * 
 * 缺点：
 * - 需要移动对象，更新所有引用
 * - 整理过程需要 STW
 * - 比标记-清除多了对象移动的开销
 */
public class MarkCompactDemo {
    // Serial Old 和 Parallel Old 收集器采用标记-整理算法
    // 总耗时 = O(L) + O(L) = O(L)，与存活对象数量成正比
}
```

#### 3.2.3 复制算法（Copying）

```java
/**
 * 复制算法原理
 * 
 * 将内存分为两个半区（From 和 To），每次只使用其中一个半区。
 * GC 时将存活对象从 From 区复制到 To 区，然后交换角色。
 * 
 * 内存布局变化示意：
 * 
 * GC 前：
 * From: [A][x][C][x][D][x][x][F]    (x = 垃圾对象)
 * To:   [                        ]    (空闲)
 * 
 * GC 后：
 * From: [                        ]    (空闲，下次作为 To)
 * To:   [A][C][D][F][            ]    (存活对象紧凑排列)
 * 
 * 优点：
 * - 时间复杂度 O(L)，只与存活对象数量相关
 * - 无内存碎片
 * - 支持 Bump Pointer 分配（极高效）
 * - 适合存活率低的场景（Young Generation）
 * 
 * 缺点：
 * - 空间利用率低，只能使用 50% 的内存
 * - 存活对象多时复制开销大
 * 
 * JVM 优化：Appel式回收
 * - 不是严格的 1:1 划分，而是 Eden:S0:S1 = 8:1:1
 * - 每次可用空间 = Eden + 一个 Survivor = 90%
 * - 空间利用率从 50% 提升到 90%
 */
public class CopyingAlgorithmDemo {
    // ParNew、Parallel Scavenge 收集器在 Young Gen 采用复制算法
    // G1 的 Young GC 也基于复制算法
}
```

#### 3.2.4 算法复杂度对比

```
算法效率比较（关键关系）：

时间效率:  Compaction ≥ Copying > Marking > Sweeping

空间利用:  Mark-Compact > Mark-Sweep > Copying

碎片程度:  Copying = Mark-Compact < Mark-Sweep

对象移动:  Mark-Sweep (不移动) < Mark-Compact = Copying (需移动)

适用场景:
  ┌─────────────┬────────────────────────────────┐
  │ 算法         │ 最佳适用场景                     │
  ├─────────────┼────────────────────────────────┤
  │ Copying      │ 存活率低的区域（Young Gen）       │
  │ Mark-Sweep   │ 存活率高且注重吞吐量（CMS Old）   │
  │ Mark-Compact │ 存活率高且需要连续空间（Full GC）  │
  └─────────────┴────────────────────────────────┘
```

### 3.3 主流垃圾收集器

#### 3.3.1 ParNew 收集器

```java
/**
 * ParNew 收集器详解
 * 
 * 特点：
 * - 工作在年轻代（Young Generation）
 * - 多线程并行收集（Parallel）
 * - 采用复制算法（Copying）
 * - 收集期间 STW（Stop-The-World）
 * - 是 CMS 收集器在年轻代的搭档
 * 
 * 核心参数：
 * -XX:+UseParNewGC            启用 ParNew 收集器
 * -XX:ParallelGCThreads=N     设置并行 GC 线程数
 *                              默认值 = CPU 核心数（≤8时）
 *                              默认值 = 3 + 5*CPU/8（>8时）
 */
public class ParNewConfiguration {

    /**
     * ParNew 典型配置示例
     */
    public static String[] getJVMFlags() {
        return new String[]{
            // 堆内存配置
            "-Xms4g",                          // 初始堆大小
            "-Xmx4g",                          // 最大堆大小（建议与 Xms 相同，避免扩容开销）
            "-Xmn2g",                          // 年轻代大小

            // 收集器选择
            "-XX:+UseParNewGC",                // 年轻代使用 ParNew
            "-XX:+UseConcMarkSweepGC",         // 老年代使用 CMS

            // ParNew 参数
            "-XX:ParallelGCThreads=8",         // 并行 GC 线程数
            "-XX:SurvivorRatio=8",             // Eden:S0:S1 = 8:1:1
            "-XX:MaxTenuringThreshold=15",     // 对象晋升老年代的年龄阈值
            "-XX:+UseAdaptiveSizePolicy",      // 自适应大小策略（ParNew 下建议关闭）

            // GC 日志
            "-Xloggc:/var/log/gc.log",
            "-XX:+PrintGCDetails",
            "-XX:+PrintGCDateStamps",
            "-XX:+PrintTenuringDistribution"   // 打印对象年龄分布
        };
    }
}
```

#### 3.3.2 CMS 收集器（Concurrent Mark Sweep）

```java
/**
 * CMS 收集器详解
 * 
 * 设计目标：最小化 GC 停顿时间
 * 算法：标记-清除（Mark-Sweep）
 * 工作区域：老年代（Old Generation）
 * 
 * 生命周期：JDK 1.4.2 引入，JDK 9 标记废弃，JDK 14 正式移除
 * 
 * 四个阶段：
 * ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 * │ 1. 初始标记    │ → │ 2. 并发标记    │ → │ 3. 重新标记    │ → │ 4. 并发清除    │
 * │ Initial Mark  │   │ Concurrent   │   │ Remark       │   │ Concurrent   │
 * │              │   │ Mark         │   │              │   │ Sweep        │
 * │   ★ STW ★    │   │   并发执行    │   │   ★ STW ★    │   │   并发执行    │
 * │   速度很快     │   │   耗时最长    │   │   耗时较长    │   │   耗时较长    │
 * └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
 * 
 * 阶段说明：
 * 1. 初始标记（STW）：仅标记 GC Roots 直接关联的对象，速度很快
 * 2. 并发标记（并发）：从 GC Roots 出发遍历整个对象图，与用户线程并发执行
 * 3. 重新标记（STW）：修正并发标记期间因用户线程运行导致的标记变动
 *    使用增量更新（Incremental Update）处理漏标问题
 * 4. 并发清除（并发）：清除未标记的对象，与用户线程并发执行
 */
public class CMSConfiguration {

    /**
     * CMS 核心参数配置
     */
    public static String[] getCMSFlags() {
        return new String[]{
            // 基础配置
            "-XX:+UseConcMarkSweepGC",                    // 启用 CMS
            "-XX:+UseParNewGC",                           // 搭配 ParNew

            // CMS 触发阈值
            "-XX:CMSInitiatingOccupancyFraction=75",      // Old Gen 使用率达 75% 触发 CMS
            "-XX:+UseCMSInitiatingOccupancyOnly",         // 只使用设定的阈值，禁用自适应

            // CMS 阶段优化
            "-XX:+CMSParallelInitialMarkEnabled",         // 初始标记阶段并行化
            "-XX:+CMSParallelRemarkEnabled",              // 重新标记阶段并行化
            "-XX:+CMSScavengeBeforeRemark",               // 重新标记前先做一次 Young GC

            // 碎片整理
            "-XX:+UseCMSCompactAtFullCollection",         // Full GC 时进行压缩整理
            "-XX:CMSFullGCsBeforeCompaction=0",           // 每次 Full GC 后都压缩

            // 并发线程数
            "-XX:ConcGCThreads=4",                        // CMS 并发 GC 线程数
            // 默认值 = (ParallelGCThreads + 3) / 4

            // 大对象直接进入老年代
            "-XX:+UseCMSCompactAtFullCollection",
            "-XX:CMSInitiatingOccupancyFraction=75"
        };
    }

    /**
     * CMS 的三大致命问题
     */
    public void cmsProblems() {
        // 问题1: Concurrent Mode Failure
        // 原因：CMS 并发清除期间，老年代空间不足以容纳新晋升的对象
        // 后果：CMS 退化为 Serial Old（单线程标记-整理），停顿时间暴增
        // 解决：降低 CMSInitiatingOccupancyFraction，提前触发 CMS

        // 问题2: Promotion Failure
        // 原因：Young GC 时，Survivor 放不下，需要晋升到 Old Gen，但 Old Gen 碎片化严重
        //       虽然 Old Gen 总剩余空间够，但没有足够大的连续空间
        // 后果：触发 Full GC
        // 解决：增加堆大小，或启用压缩（UseCMSCompactAtFullCollection）

        // 问题3: 内存碎片
        // 原因：Mark-Sweep 算法不移动对象，产生大量碎片
        // 后果：大对象分配失败，触发 Full GC
        // 解决：定期压缩，或考虑迁移到 G1
    }
}
```

#### 3.3.3 G1 收集器（Garbage-First）

```java
/**
 * G1 收集器详解
 * 
 * 设计目标：兼顾高吞吐量和低停顿时间
 * JDK 9 成为默认收集器
 * 
 * 核心创新：Region 化内存布局
 * 
 * ┌──────────────────────────────────────────────────────────┐
 * │  传统分代布局：                                            │
 * │  [    Young Generation    ][      Old Generation        ] │
 * │                                                          │
 * │  G1 Region 化布局：                                       │
 * │  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐     │
 * │  │ E │ O │ S │ O │ E │ H │ H │ O │ E │ S │ O │ E │     │
 * │  ├───┼───┼───┼───┼───┼───┼───┼───┼───┼───┼───┼───┤     │
 * │  │ O │ E │ O │ S │ E │ O │ E │ O │ H │ H │ H │ O │     │
 * │  ├───┼───┼───┼───┼───┼───┼───┼───┼───┼───┼───┼───┤     │
 * │  │ E │ O │ E │ O │ S │ O │ E │ O │ E │ O │ O │ E │     │
 * │  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘     │
 * │                                                          │
 * │  E = Eden   S = Survivor   O = Old   H = Humongous      │
 * │                                                          │
 * │  每个 Region 大小相同（1MB ~ 32MB，2 的幂次方）              │
 * │  Region 可以动态扮演不同角色                                 │
 * │  Humongous：存放超过 Region 50% 的大对象                    │
 * └──────────────────────────────────────────────────────────┘
 * 
 * GC 模式：
 * 1. Young GC：回收所有 Eden 和 Survivor Region
 * 2. Mixed GC：回收所有 Young Region + 部分收益最高的 Old Region
 * 3. Full GC：退化为单线程整理（应极力避免）
 */
public class G1Configuration {

    /**
     * G1 核心参数配置
     */
    public static String[] getG1Flags() {
        return new String[]{
            // 启用 G1（JDK 9+ 默认）
            "-XX:+UseG1GC",

            // 核心参数
            "-XX:MaxGCPauseMillis=200",              // 目标最大停顿时间（默认 200ms）
            "-XX:G1HeapRegionSize=8m",               // Region 大小（1~32MB, 2的幂）
            "-XX:G1NewSizePercent=5",                // 年轻代最小比例
            "-XX:G1MaxNewSizePercent=60",            // 年轻代最大比例

            // Mixed GC 相关
            "-XX:InitiatingHeapOccupancyPercent=45",  // 整堆使用率达 45% 触发并发标记
            "-XX:G1MixedGCLiveThresholdPercent=85",  // Old Region 存活率低于 85% 才纳入 Mixed GC
            "-XX:G1MixedGCCountTarget=8",            // 一次并发标记后，Mixed GC 最多执行 8 轮
            "-XX:G1OldCSetRegionThresholdPercent=10", // 每次 Mixed GC 最多回收 10% 的 Old Region

            // 堆内存
            "-Xms8g",
            "-Xmx8g",

            // GC 日志（JDK 9+ 统一日志框架）
            "-Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags:filecount=5,filesize=50m"
        };
    }

    /**
     * G1 的 Remember Set（RSet）与 Collection Set（CSet）
     */
    public void g1InternalStructures() {
        // Remember Set (RSet)：
        // 每个 Region 维护一个 RSet，记录其他 Region 中指向本 Region 的引用
        // 用于避免全堆扫描，仅扫描 RSet 中记录的区域
        // RSet 会占用额外内存（通常为堆的 5%~20%）

        // Collection Set (CSet)：
        // 一次 GC 中需要回收的 Region 集合
        // Young GC：CSet = 所有 Eden Region + Survivor Region
        // Mixed GC：CSet = 所有 Young Region + 部分 Old Region（按回收收益排序）
        // G1 名称由来："Garbage-First"即优先回收垃圾最多的 Region

        // Predicted Pause Time Model（预测停顿模型）：
        // G1 通过衰减均值（Decayed Average）预测每个 Region 的回收耗时
        // 选择在目标停顿时间内能回收最多垃圾的 Region 组合
    }
}
```

#### 3.3.4 ZGC 收集器

```java
/**
 * ZGC 收集器详解
 * 
 * 设计目标：超低延迟（亚毫秒级停顿）
 * JDK 11 实验性引入，JDK 15 正式发布
 * 
 * 核心指标：
 * - 停顿时间不超过 1ms（实测 128G 堆最大停顿仅 1.68ms）
 * - 停顿时间不随堆大小增长而增长
 * - 支持 TB 级别堆内存
 * 
 * 核心技术：
 * 1. 染色指针（Colored Pointers）
 * 2. 读屏障（Load Barriers）
 * 3. 内存多重映射（Multi-Mapping）
 * 
 * ┌─────────────────────────────────────────────────────────┐
 * │  ZGC 染色指针 (64位)                                     │
 * │                                                         │
 * │  ┌──────┬──┬──┬──┬──┬────────────────────────────────┐  │
 * │  │unused│M │R │F │Mk│      Object Address (42bit)     │  │
 * │  │ 16bit│1b│1b│1b│1b│      支持 4TB 堆空间             │  │
 * │  └──────┴──┴──┴──┴──┴────────────────────────────────┘  │
 * │                                                         │
 * │  M  = Marked0  (标记位0)                                 │
 * │  R  = Marked1  (标记位1，交替使用)                        │
 * │  F  = Finalizable (是否需要 finalize)                    │
 * │  Mk = Remapped (是否已重映射)                             │
 * │                                                         │
 * │  关键优势：GC 信息存储在指针中，无需修改对象头               │
 * │  读屏障：每次对象访问时检查指针颜色，必要时触发标记/重定位    │
 * └─────────────────────────────────────────────────────────┘
 * 
 * ZGC 工作阶段：
 * 1. 初始标记（STW）    ← 极短，仅标记 GC Roots 直接引用
 * 2. 并发标记
 * 3. 再标记（STW）      ← 极短
 * 4. 并发预备重分配
 * 5. 初始重分配（STW）  ← 极短
 * 6. 并发重分配         ← 对象实际移动发生在这里
 * 7. 并发重映射
 */
public class ZGCConfiguration {

    /**
     * ZGC 参数配置
     */
    public static String[] getZGCFlags() {
        return new String[]{
            // 启用 ZGC
            "-XX:+UseZGC",

            // JDK 21+ 分代 ZGC（推荐）
            // "-XX:+UseZGC",
            // "-XX:+ZGenerational",

            // 堆内存
            "-Xms16g",
            "-Xmx16g",

            // ZGC 特有参数
            "-XX:ZAllocationSpikeTolerance=5",   // 分配速率波动容忍度
            "-XX:ZCollectionInterval=120",       // 定期 GC 间隔（秒），0 表示禁用
            "-XX:ZFragmentationLimit=25",        // 碎片化阈值（百分比）

            // 并发 GC 线程
            "-XX:ConcGCThreads=4",

            // GC 日志
            "-Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags"
        };
    }

    /**
     * ZGC vs G1 vs CMS 对比
     */
    public void comparison() {
        // ┌───────────┬───────────┬───────────┬───────────┐
        // │ 特性       │ CMS       │ G1        │ ZGC       │
        // ├───────────┼───────────┼───────────┼───────────┤
        // │ 最大停顿   │ 100ms~1s  │ 50~200ms  │ <1ms      │
        // │ 堆大小支持 │ <32G      │ <64G      │ 16TB      │
        // │ 算法       │ Mark-Sweep│ 复制+整理  │ 染色指针   │
        // │ 并发度     │ 标记+清除  │ 标记       │ 几乎全程   │
        // │ 碎片       │ 有        │ 无        │ 无         │
        // │ JDK 版本   │ 废弃      │ 9+ 默认   │ 15+ 正式  │
        // └───────────┴───────────┴───────────┴───────────┘
    }
}
```

#### 3.3.5 Shenandoah 收集器

```java
/**
 * Shenandoah 收集器详解
 * 
 * 由 Red Hat 开发，JDK 12 引入
 * 
 * 核心特点：
 * - 与 G1 类似的 Region 化布局
 * - 无需 Remember Set（节省大量内存）
 * - 停顿时间与堆大小无关
 * - 使用 Brooks Pointer（转发指针）实现并发移动
 * 
 * 与 G1 的关键区别：
 * 1. 不维护 Remember Set，通过连接矩阵（Connection Matrix）记录 Region 间引用
 * 2. 支持并发整理（Concurrent Compaction），G1 整理阶段需要 STW
 * 3. 使用读屏障 + 写屏障，G1 仅使用写屏障
 * 
 * Brooks Pointer：
 * 每个对象头额外增加一个转发指针
 * 对象未移动时，转发指针指向自己
 * 对象被移动后，转发指针指向新位置
 * 通过转发指针实现并发移动期间的正确访问
 */
public class ShenandoahConfiguration {

    public static String[] getShenandoahFlags() {
        return new String[]{
            "-XX:+UseShenandoahGC",
            "-Xms8g",
            "-Xmx8g",
            "-XX:ShenandoahGCHeuristics=adaptive",     // 启发式策略
            // 可选值: adaptive, static, compact, aggressive, passive
            "-XX:ConcGCThreads=4",
            "-Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags"
        };
    }
}
```

### 3.4 九大常见 CMS GC 问题诊断

#### 3.4.1 问题一：显式 System.gc() 调用

```java
import java.nio.ByteBuffer;

/**
 * 问题：代码中显式调用 System.gc() 或通过框架间接触发
 * 
 * 常见触发源：
 * 1. 业务代码直接调用 System.gc()
 * 2. NIO 中的 DirectByteBuffer 在堆外内存不足时触发
 *    DirectByteBuffer 通过 Cleaner（虚引用）回收堆外内存
 *    当 DirectByteBuffer 分配失败时，会主动调用 System.gc()
 *    等待 Cleaner 回收已无引用的 DirectByteBuffer 关联的堆外内存
 * 3. RMI 框架定期触发（默认每小时一次）
 * 4. 第三方库的不当调用
 */
public class SystemGCProblem {

    /**
     * 问题复现：DirectByteBuffer 触发 System.gc()
     */
    public void directByteBufferGC() {
        // DirectByteBuffer 分配堆外内存的流程：
        // 1. 尝试分配堆外内存
        // 2. 空间不足时，调用 System.gc() 触发 Full GC
        // 3. 等待 Cleaner 线程回收已死亡的 DirectByteBuffer 对应的堆外内存
        // 4. 重试分配
        // 5. 仍然不足则抛出 OOM

        // -XX:MaxDirectMemorySize=256m
        ByteBuffer buffer = ByteBuffer.allocateDirect(128 * 1024 * 1024);
        // 使用完毕后，buffer 对象成为垃圾后，GC 回收 buffer 时触发 Cleaner#clean
        // 释放对应的堆外内存
    }

    /**
     * 诊断方法
     */
    public void diagnose() {
        // 1. 查看 GC 日志，搜索 "System.gc()" 或 "System"
        //    [Full GC (System.gc()) ...]

        // 2. 使用 arthas 查找调用栈
        //    stack java.lang.System gc

        // 3. 全局搜索代码中的 System.gc() 调用
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 禁用显式 GC（慎用）
        // -XX:+DisableExplicitGC
        // 风险：DirectByteBuffer 无法通过 System.gc() 回收堆外内存，可能导致堆外 OOM

        // 方案2: 将显式 GC 改为并发模式（推荐）
        // -XX:+ExplicitGCInvokesConcurrent
        // 效果：System.gc() 触发的是 CMS GC 而非 Full GC，停顿时间大幅降低

        // 方案3: 代码层面消除不必要的 System.gc() 调用
        // 排查所有直接和间接的 System.gc() 调用
    }
}
```

#### 3.4.2 问题二：ParNew 晋升失败（Promotion Failure）

```java
/**
 * 问题：Young GC 时存活对象需要晋升到 Old Gen，但 Old Gen 空间不足
 * 
 * 触发条件：
 * 1. Survivor 区空间不足以容纳存活对象
 * 2. 需要将对象晋升到 Old Gen
 * 3. Old Gen 虽有剩余空间但碎片化严重，没有足够大的连续空间
 * 
 * 后果：触发 Full GC（Serial Old 单线程收集），停顿时间可能达到秒级
 */
public class PromotionFailureProblem {

    /**
     * GC 日志特征
     */
    public void gcLogPattern() {
        // GC 日志中出现类似：
        // [GC (Allocation Failure)
        //   [ParNew (promotion failed): 460096K->460096K(460096K), 0.5123456 secs]
        //   [CMS: 3145728K->2097152K(3145728K), 8.1234567 secs]
        //   3605824K->2097152K(3605824K),
        //   [Metaspace: 65536K->65536K(1114112K)], 8.6358023 secs]
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 增大老年代空间
        // 增大 -Xmx 或减小 -Xmn 来给老年代更多空间

        // 方案2: 降低 CMS 触发阈值，提前回收
        // -XX:CMSInitiatingOccupancyFraction=60

        // 方案3: 启用压缩，减少碎片
        // -XX:+UseCMSCompactAtFullCollection
        // -XX:CMSFullGCsBeforeCompaction=0

        // 方案4: 检查是否有大对象分配
        // 大对象直接进入老年代，加速碎片化
        // -XX:PretenureSizeThreshold=1m  (超过 1MB 的对象直接进老年代)

        // 方案5: 调整晋升年龄
        // -XX:MaxTenuringThreshold=6  (减少晋升年龄，让短命对象在 Young Gen 回收)
    }
}
```

#### 3.4.3 问题三：CMS Concurrent Mode Failure

```java
/**
 * 问题：CMS 并发回收的速度跟不上对象分配的速度
 * Old Gen 在 CMS 并发清除期间就已被填满
 * 
 * 触发条件：
 * CMS GC 正在并发执行时，Old Gen 空间不足以容纳新晋升或直接分配的对象
 * 
 * 后果：CMS 被迫中断，退化为 Serial Old 收集器
 * 单线程执行 Mark-Compact，停顿时间可能达到数秒甚至数十秒
 */
public class ConcurrentModeFailureProblem {

    /**
     * GC 日志特征
     */
    public void gcLogPattern() {
        // [GC (CMS Initial Mark) ...]
        // [CMS-concurrent-mark-start]
        // [CMS-concurrent-mark: 0.234/0.256 secs]
        // [GC (CMS Final Remark) ...]
        // [CMS-concurrent-sweep-start]
        // [concurrent mode failure]: 3145728K->2621440K(3145728K), 12.3456789 secs
        //                            ↑ 关键标识
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 降低 CMS 触发阈值（核心方案）
        // -XX:CMSInitiatingOccupancyFraction=65
        // 更早触发 CMS，给并发回收留出更多时间

        // 方案2: 增大老年代空间
        // 增大 -Xmx，给老年代更多缓冲空间

        // 方案3: 增加 CMS 并发线程数
        // -XX:ConcGCThreads=8
        // 加快并发回收速度

        // 方案4: 排查是否有内存泄漏
        // 对象持续增长导致老年代增速超过回收速度

        // 方案5: 考虑迁移到 G1
        // G1 通过 Mixed GC 和可预测停顿模型更好地管理大堆
    }
}
```

#### 3.4.4 问题四：大对象分配失败

```java
import java.util.ArrayList;
import java.util.List;

/**
 * 问题：大对象（Humongous Object）分配导致 GC 问题
 * 
 * CMS 场景：
 * - 大对象直接在 Old Gen 分配（绕过 Young Gen）
 * - 加速老年代空间消耗和碎片化
 * 
 * G1 场景：
 * - 超过 Region 大小 50% 的对象被标记为 Humongous
 * - Humongous 对象占用连续的 Region
 * - 回收 Humongous 对象需要特殊处理
 */
public class HumongousObjectProblem {

    /**
     * 典型触发场景
     */
    public void typicalScenarios() {
        // 场景1: 大数组分配
        byte[] largeArray = new byte[10 * 1024 * 1024]; // 10MB 数组

        // 场景2: 大批量数据查询结果
        List<Object> hugeResultSet = new ArrayList<>(1_000_000);
        // 从数据库查询百万级数据加载到内存

        // 场景3: 大 JSON/XML 序列化
        // 将整个大对象序列化为一个巨大的字符串

        // 场景4: 大文件读入内存
        // byte[] fileContent = Files.readAllBytes(Paths.get("huge_file.dat"));
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 代码层面避免大对象
        // 使用流式处理替代全量加载
        // 分页查询替代全量查询

        // 方案2: G1 调整 Region 大小
        // -XX:G1HeapRegionSize=16m
        // 增大 Region 大小，减少 Humongous 对象的产生

        // 方案3: CMS 场景设置大对象阈值
        // -XX:PretenureSizeThreshold=5m
        // 控制直接进入老年代的对象大小阈值

        // 方案4: 使用对象池复用大对象
        // 避免频繁创建和回收大对象
    }
}
```

#### 3.4.5 问题五：MetaSpace 扩容触发 Full GC

```java
/**
 * 问题：MetaSpace（元空间）动态扩容过程中触发 Full GC
 * 
 * MetaSpace 在 JDK 8 中替代了 PermGen（永久代）
 * MetaSpace 使用本地内存（Native Memory），不在 JVM 堆中
 * 但 MetaSpace 空间不足时仍会触发 Full GC
 * 
 * 常见原因：
 * 1. MetaSpace 初始大小设置过小，频繁扩容
 * 2. 大量动态生成类（如 CGLib、反射代理、Lambda、Groovy 脚本等）
 * 3. 类加载器泄漏（ClassLoader Leak），类无法卸载
 */
public class MetaSpaceProblem {

    /**
     * GC 日志特征
     */
    public void gcLogPattern() {
        // [Full GC (Metadata GC Threshold)
        //   [CMS: 524288K->262144K(3145728K), 2.3456789 secs]
        //   3605824K->262144K(3605824K),
        //   [Metaspace: 262144K->131072K(1114112K)], 2.3456789 secs]
        //              ↑ MetaSpace 触发的 Full GC
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 合理设置 MetaSpace 大小
        // -XX:MetaspaceSize=256m           初始大小（触发 GC 的阈值）
        // -XX:MaxMetaspaceSize=512m        最大大小
        // 建议 MetaspaceSize 和 MaxMetaspaceSize 设为相同值，避免动态扩容

        // 方案2: 排查动态类生成
        // 使用 arthas 查看类加载情况：
        // classloader -t             查看类加载器树
        // sc -d *GeneratedClass*     搜索动态生成的类
        // jad com.example.Generated  反编译查看动态类内容

        // 方案3: 监控 MetaSpace 使用量
        // 通过 JMX 监控 java.lang:type=MemoryPool,name=Metaspace

        // 方案4: 检查 ClassLoader 泄漏
        // 热部署、OSGi、自定义 ClassLoader 场景容易产生泄漏
    }
}
```

#### 3.4.6 问题六：Finalizer 导致对象存活延长

```java
import java.util.concurrent.BlockingQueue;

/**
 * 问题：大量对象重写了 finalize() 方法
 * 这些对象在第一次 GC 时不会被直接回收，而是放入 Finalizer 队列
 * 由 Finalizer 线程执行 finalize() 后才能在下一次 GC 中回收
 * 
 * 后果：
 * 1. 对象至少存活两个 GC 周期
 * 2. Finalizer 线程是低优先级线程，处理速度可能跟不上创建速度
 * 3. finalize() 中的异常被静默吞掉，增加排查难度
 * 4. finalize() 中对象可能被重新强引用（"对象复活"）
 */
public class FinalizerProblem {

    /**
     * 反面示例：不应使用 finalize()
     */
    static class ResourceHolder {
        private byte[] resource = new byte[1024 * 1024]; // 1MB

        @Override
        @Deprecated // JDK 9 起已标记废弃
        protected void finalize() throws Throwable {
            try {
                // 资源清理逻辑
                resource = null;
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * 正面示例：使用 try-with-resources 或 Cleaner
     */
    static class BetterResourceHolder implements AutoCloseable {
        private byte[] resource = new byte[1024 * 1024];

        @Override
        public void close() {
            resource = null;
            // 显式资源清理
        }
    }

    /**
     * JDK 9+ 推荐：使用 java.lang.ref.Cleaner 替代 finalize()
     */
    static class ModernResourceHolder {
        private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

        private final java.lang.ref.Cleaner.Cleanable cleanable;
        private final byte[] resource;

        public ModernResourceHolder() {
            this.resource = new byte[1024 * 1024];
            // Cleaning action 不应持有外部对象的引用
            byte[] res = this.resource;
            this.cleanable = CLEANER.register(this, () -> {
                // 清理逻辑：释放资源
                // 注意：这里不能引用 this，否则会阻止 GC
                System.out.println("Cleaning up resource of size: " + res.length);
            });
        }
    }

    /**
     * 诊断方法
     */
    public void diagnose() {
        // 1. 查看 Finalizer 队列长度
        //    jmap -finalizerinfo <pid>

        // 2. 查看 Finalizer 线程状态
        //    jstack <pid> | grep -A 20 "Finalizer"

        // 3. 使用 arthas
        //    thread -n 3  查看最忙的线程
        //    如果 Finalizer 线程持续繁忙，说明有大量 finalize 对象
    }
}
```

#### 3.4.7 问题七：引用处理开销

```java
import java.lang.ref.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 问题：大量使用软引用、弱引用、虚引用，GC 时引用处理开销大
 * 
 * GC 在标记阶段后需要额外处理这些特殊引用：
 * 1. 遍历所有 SoftReference，判断是否需要回收
 * 2. 遍历所有 WeakReference，清除已死对象的引用
 * 3. 处理所有 PhantomReference，将其加入 ReferenceQueue
 * 4. 处理所有 FinalReference（finalize 对象）
 * 
 * 这个过程在 GC Remark 阶段执行，是 STW 的
 */
public class ReferenceProcessingProblem {

    /**
     * GC 日志特征
     */
    public void gcLogPattern() {
        // [GC remark ...
        //   [weak refs processing, 0.1234567 secs]   ← 引用处理耗时
        //   [class unloading, 0.0123456 secs]
        //   [scrub symbol table, 0.0012345 secs]
        //   [scrub string table, 0.0001234 secs]
        // ]
        // 如果 weak refs processing 超过 100ms，说明引用数量过多
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 开启引用处理并行化
        // -XX:+ParallelRefProcEnabled
        // 使用多线程并行处理引用，缩短 Remark 暂停时间

        // 方案2: 减少不必要的软引用/弱引用使用
        // 评估是否真的需要这些特殊引用类型
        // 简单的缓存场景可以使用 LRU 淘汰替代软引用

        // 方案3: 控制软引用的存活时间
        // -XX:SoftRefLRUPolicyMSPerMB=1000
        // 含义：每 MB 空闲内存允许软引用存活 1000ms
        // 减小此值可以更积极地回收软引用
    }
}
```

#### 3.4.8 问题八：JNI GCLocker 延迟

```java
/**
 * 问题：JNI 临界区阻止 GC 执行
 * 
 * 当 Java 线程通过 JNI 进入临界区（GetPrimitiveArrayCritical / GetStringCritical）时
 * GC 无法执行，因为这些操作直接获取了堆中对象的指针
 * 如果此时需要 GC，GC 必须等待所有 JNI 临界区退出
 * 
 * GC 日志中出现：GCLocker Initiated GC
 * 表示 GC 被 JNI 临界区延迟后重新触发
 */
public class GCLockerProblem {

    /**
     * GC 日志特征
     */
    public void gcLogPattern() {
        // [GC (GCLocker Initiated GC)
        //   [ParNew: 460096K->51200K(460096K), 0.0234567 secs]
        //   460096K->102400K(3605824K), 0.0234567 secs]
    }

    /**
     * 解决方案
     */
    public void solutions() {
        // 方案1: 减少 JNI 临界区的持有时间
        // 尽快释放通过 GetPrimitiveArrayCritical 获取的指针
        // 使用 ReleasePrimitiveArrayCritical 及时释放

        // 方案2: 使用 GetByteArrayRegion 替代 GetPrimitiveArrayCritical
        // 前者会复制数据，不会锁定 GC
        // 后者直接获取指针，会锁定 GC

        // 方案3: 排查使用 JNI 的第三方库
        // 部分压缩库、加密库可能使用 JNI 临界区
    }
}
```

#### 3.4.9 问题九：内存泄漏

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问题：对象被持续创建但无法被 GC 回收，老年代使用率持续增长
 * 最终导致频繁 Full GC 甚至 OOM
 */
public class MemoryLeakProblem {

    /**
     * 常见内存泄漏场景
     */

    // 场景1: 静态集合持续增长
    private static final List<Object> LEAK_LIST = new ArrayList<>();

    public void staticCollectionLeak() {
        // 对象被加入静态集合后不会被回收
        // 静态集合作为 GC Root，其中的所有对象都是可达的
        LEAK_LIST.add(new byte[1024]); // 每次调用都会增长
    }

    // 场景2: 未关闭的资源
    public void unclosedResourceLeak() throws Exception {
        // 数据库连接、文件流、网络连接等未关闭
        // 这些对象持有 native 资源，GC 无法释放底层资源
        java.io.InputStream is = new java.io.FileInputStream("/tmp/test.txt");
        // 忘记调用 is.close()，连接泄漏
        // 正确做法：使用 try-with-resources
    }

    // 场景3: HashMap 的 Key 对象未正确实现 hashCode/equals
    public void hashMapKeyLeak() {
        Map<Object, String> map = new HashMap<>();
        // 如果 Key 对象未正确实现 equals/hashCode
        // 相同逻辑的 Key 会产生多个 Entry，无法通过 put 覆盖
        // 导致 Map 持续增长
    }

    // 场景4: ThreadLocal 泄漏
    private static final ThreadLocal<byte[]> THREAD_LOCAL = new ThreadLocal<>();

    public void threadLocalLeak() {
        // 线程池场景下，ThreadLocal 的值不会被自动清理
        THREAD_LOCAL.set(new byte[1024 * 1024]); // 1MB per thread
        // 忘记调用 THREAD_LOCAL.remove()
        // 线程池中线程长期存活，ThreadLocal 值一直被引用
    }

    // 场景5: 监听器/回调未注销
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public void listenerLeak() {
        // 注册了事件监听器但从未注销
        LISTENERS.add(() -> System.out.println("event"));
        // 监听器持有外部对象的引用，导致外部对象无法被回收
    }

    // 场景6: 缓存无淘汰策略
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    public void unboundedCacheLeak() {
        // 缓存只增不减，无容量上限，无过期策略
        String key = "key_" + System.currentTimeMillis();
        CACHE.put(key, new byte[1024]);
        // 正确做法：使用有容量上限的缓存（如 Caffeine、Guava Cache）
    }

    /**
     * 内存泄漏诊断流程
     */
    public void diagnosisWorkflow() {
        // Step 1: 确认症状
        // - Old Gen 使用率持续增长，Full GC 后回收效果越来越差
        // - GC 频率逐渐增加
        // - 最终触发 OOM

        // Step 2: 获取堆转储
        // jmap -dump:format=b,file=heap.hprof <pid>
        // 或配置自动转储：-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/

        // Step 3: 分析堆转储
        // 使用 MAT（Memory Analyzer Tool）或 JProfiler
        // 查看 Dominator Tree，找到占用内存最大的对象
        // 查看 Leak Suspects Report，自动分析可能的泄漏点
        // 通过 GC Roots 最短路径查看对象为什么不能被回收

        // Step 4: 定位代码
        // 根据堆分析结果，定位到具体的类和字段
        // 分析为什么对象被强引用持有

        // Step 5: 修复并验证
        // 修复代码后，在预发布环境长时间运行
        // 观察 Old Gen 使用率是否稳定
    }
}
```

### 3.5 GC 日志分析实战

#### 3.5.1 GC 日志配置

```java
/**
 * GC 日志配置最佳实践
 */
public class GCLogConfiguration {

    /**
     * JDK 8 GC 日志配置
     */
    public static String[] jdk8GCLogFlags() {
        return new String[]{
            // 基本日志
            "-XX:+PrintGCDetails",              // 打印详细 GC 日志
            "-XX:+PrintGCDateStamps",            // 打印日期时间戳
            "-XX:+PrintGCTimeStamps",            // 打印 JVM 启动后的相对时间
            "-XX:+PrintGCApplicationStoppedTime", // 打印应用暂停时间
            "-XX:+PrintGCApplicationConcurrentTime", // 打印应用并发运行时间

            // 详细信息
            "-XX:+PrintTenuringDistribution",    // 打印对象年龄分布
            "-XX:+PrintHeapAtGC",               // GC 前后打印堆信息
            "-XX:+PrintReferenceGC",             // 打印引用处理信息
            "-XX:+PrintAdaptiveSizePolicy",      // 打印自适应大小策略

            // 日志文件
            "-Xloggc:/var/log/app/gc-%t.log",   // GC 日志文件（%t=时间戳）
            "-XX:+UseGCLogFileRotation",         // 启用日志轮转
            "-XX:NumberOfGCLogFiles=10",         // 保留 10 个日志文件
            "-XX:GCLogFileSize=100M"             // 每个文件最大 100MB
        };
    }

    /**
     * JDK 9+ 统一日志框架（ULF）配置
     */
    public static String[] jdk9PlusGCLogFlags() {
        return new String[]{
            // 基本 GC 日志
            "-Xlog:gc:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m",

            // 详细 GC 日志（推荐用于问题排查）
            "-Xlog:gc*,gc+age=trace,gc+heap=debug:file=/var/log/app/gc-detail.log:time,uptime,level,tags:filecount=10,filesize=100m",

            // 安全点日志
            "-Xlog:safepoint:file=/var/log/app/safepoint.log:time,uptime,level,tags:filecount=5,filesize=50m"
        };
    }
}
```

#### 3.5.2 GC 日志解析示例

```
# CMS GC 日志解析示例

# Young GC (ParNew)
2024-01-15T10:30:15.123+0800: 12345.678: [GC (Allocation Failure)
  2024-01-15T10:30:15.123+0800: 12345.678: [ParNew: 460096K->51200K(460096K), 0.0234567 secs]
  3605824K->512000K(3605824K), 0.0234891 secs]
  [Times: user=0.18 sys=0.01, real=0.02 secs]

# 日志解析：
# ParNew: 460096K->51200K(460096K)
#   460096K  = Young Gen 回收前使用量
#   51200K   = Young Gen 回收后使用量
#   (460096K)= Young Gen 总容量
#   回收了 408896K = 460096K - 51200K
#
# 3605824K->512000K(3605824K)
#   3605824K  = 堆回收前使用量
#   512000K   = 堆回收后使用量
#   (3605824K)= 堆总容量
#
# 晋升到老年代的量 = (堆回收前 - 堆回收后) - (Young回收前 - Young回收后)
#                  = (3605824 - 512000) - (460096 - 51200)
#                  = 3093824 - 408896 = 2684928K（如果为正，说明有晋升）

# CMS Old GC
2024-01-15T10:31:00.000+0800: 12400.000: [GC (CMS Initial Mark) [1 CMS-initial-mark: 2048000K(3145728K)] 2508800K(3605824K), 0.0023456 secs]
  [Times: user=0.01 sys=0.00, real=0.00 secs]
2024-01-15T10:31:00.003+0800: 12400.003: [CMS-concurrent-mark-start]
2024-01-15T10:31:00.456+0800: 12400.456: [CMS-concurrent-mark: 0.453/0.453 secs]
  [Times: user=1.80 sys=0.05, real=0.45 secs]
2024-01-15T10:31:00.457+0800: 12400.457: [CMS-concurrent-preclean-start]
2024-01-15T10:31:00.489+0800: 12400.489: [CMS-concurrent-preclean: 0.032/0.032 secs]
2024-01-15T10:31:00.490+0800: 12400.490: [GC (CMS Final Remark) [YG occupancy: 230400K (460096K)]
  2024-01-15T10:31:00.490+0800: 12400.490: [Rescan (parallel) , 0.0123456 secs]
  2024-01-15T10:31:00.502+0800: 12400.502: [weak refs processing, 0.0012345 secs]
  2024-01-15T10:31:00.504+0800: 12400.504: [class unloading, 0.0023456 secs]
  [1 CMS-remark: 2048000K(3145728K)] 2278400K(3605824K), 0.0167890 secs]
2024-01-15T10:31:00.507+0800: 12400.507: [CMS-concurrent-sweep-start]
2024-01-15T10:31:01.234+0800: 12401.234: [CMS-concurrent-sweep: 0.727/0.727 secs]
2024-01-15T10:31:01.235+0800: 12401.235: [CMS-concurrent-reset-start]
2024-01-15T10:31:01.267+0800: 12401.267: [CMS-concurrent-reset: 0.032/0.032 secs]
```

### 3.6 GC 问题分析关键 Cause 速查

```
┌─────────────────────────────────────┬──────────────────────────────────────────────┐
│  GC Cause                           │  含义与处理建议                                │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  System.gc()                        │  显式调用 GC，考虑                              │
│                                     │  -XX:+ExplicitGCInvokesConcurrent            │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  CMS Initial Mark                   │  CMS 初始标记 STW，正常行为                     │
│                                     │  关注耗时是否过长                               │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  CMS Final Remark                   │  CMS 重新标记 STW，可启用                       │
│                                     │  -XX:+CMSScavengeBeforeRemark 优化            │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  Promotion Failure                  │  Old Gen 空间不足或碎片化                       │
│                                     │  增大堆或开启压缩                               │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  Concurrent Mode Failure            │  CMS 回收速度跟不上分配速度                      │
│                                     │  降低 CMSInitiatingOccupancyFraction           │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  GCLocker Initiated GC              │  JNI 临界区延迟了 GC                            │
│                                     │  减少 JNI 临界区持有时间                         │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  Allocation Failure                 │  正常的 Young GC 触发，Eden 区满                 │
│                                     │  频率过高时考虑增大 Young Gen                    │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  Metadata GC Threshold              │  MetaSpace 触发 GC                             │
│                                     │  增大 MetaspaceSize 初始值                      │
├─────────────────────────────────────┼──────────────────────────────────────────────┤
│  Heap Dump Initiated GC             │  堆转储触发的 GC                                │
│                                     │  正常行为，无需优化                              │
└─────────────────────────────────────┴──────────────────────────────────────────────┘
```

---

## 四、异常处理

### 4.1 OOM 异常分类与处理

```java
import java.util.*;
import java.nio.ByteBuffer;

/**
 * JVM 常见 OOM 异常分类与处理策略
 */
public class OOMClassification {

    /**
     * 1. java.lang.OutOfMemoryError: Java heap space
     * 原因：堆内存不足
     */
    public void heapSpaceOOM() {
        // 触发场景：
        // 1. 堆内存设置过小
        // 2. 内存泄漏导致可用空间持续缩小
        // 3. 一次性创建过多/过大的对象

        // 解决方案：
        // 1. 增大堆内存：-Xmx
        // 2. 启用堆转储：-XX:+HeapDumpOnOutOfMemoryError
        // 3. 使用 MAT 分析堆转储，定位内存泄漏
        // 4. 优化代码，减少对象创建
    }

    /**
     * 2. java.lang.OutOfMemoryError: GC overhead limit exceeded
     * 原因：GC 耗时占比超过 98%，但回收的内存不足 2%
     */
    public void gcOverheadOOM() {
        // JVM 判断 GC 效率极低，抛出此异常防止应用"假死"
        // 本质上是堆内存不足的另一种表现

        // 解决方案：
        // 1. 同 heap space OOM
        // 2. 可临时关闭（不推荐）：-XX:-UseGCOverheadLimit
    }

    /**
     * 3. java.lang.OutOfMemoryError: Metaspace
     * 原因：元空间不足
     */
    public void metaspaceOOM() {
        // 触发场景：
        // 1. 大量动态类生成（CGLib、反射代理、Lambda）
        // 2. 类加载器泄漏
        // 3. MetaSpace 上限设置过小

        // 解决方案：
        // -XX:MaxMetaspaceSize=512m
        // 排查动态类生成和类加载器泄漏
    }

    /**
     * 4. java.lang.OutOfMemoryError: Direct buffer memory
     * 原因：堆外内存（Direct Memory）不足
     */
    public void directMemoryOOM() {
        // 触发场景：NIO 中大量使用 DirectByteBuffer
        // ByteBuffer.allocateDirect() 分配的是堆外内存

        // 解决方案：
        // 1. 增大堆外内存：-XX:MaxDirectMemorySize=1g
        // 2. 及时释放：((sun.nio.ch.DirectBuffer) buffer).cleaner().clean()
        // 3. 使用池化的 ByteBuffer（如 Netty 的 PooledByteBufAllocator）
    }

    /**
     * 5. java.lang.OutOfMemoryError: unable to create new native thread
     * 原因：操作系统无法创建更多线程
     */
    public void threadOOM() {
        // 触发场景：
        // 1. 应用创建了过多线程
        // 2. 每个线程的栈空间（-Xss）设置过大
        // 3. 操作系统线程数限制（ulimit -u）

        // 可用线程数 ≈ (系统最大内存 - JVM堆 - MetaSpace) / 线程栈大小

        // 解决方案：
        // 1. 减小线程栈：-Xss512k（默认 1MB）
        // 2. 使用线程池，控制线程数量上限
        // 3. 调大操作系统限制：ulimit -u 65535
    }

    /**
     * 6. java.lang.StackOverflowError
     * 原因：线程栈深度超过限制（虽然不是 OOM，但同属内存异常）
     */
    public void stackOverflow() {
        // 触发场景：
        // 1. 递归调用深度过大
        // 2. 方法调用链过长

        // 解决方案：
        // 1. 增大线程栈：-Xss2m
        // 2. 改递归为迭代
        // 3. 检查是否有死循环递归
    }
}
```

### 4.2 JVM 崩溃与故障转储

```java
/**
 * JVM 崩溃保护与故障转储配置
 */
public class JVMCrashProtection {

    /**
     * 故障转储参数配置
     */
    public static String[] getCrashProtectionFlags() {
        return new String[]{
            // OOM 时自动堆转储
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/var/log/app/heapdump/",

            // OOM 时执行自定义脚本（如告警、重启）
            "-XX:OnOutOfMemoryError=\"sh /opt/scripts/oom-handler.sh %p\"",

            // JVM 崩溃时生成 hs_err 日志
            "-XX:ErrorFile=/var/log/app/hs_err_pid%p.log",

            // 崩溃时生成 core dump
            "-XX:+CreateCoredumpOnCrash",

            // Fatal Error 日志
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+LogVMOutput",
            "-XX:LogFile=/var/log/app/jvm.log"
        };
    }

    /**
     * OOM 处理脚本示例
     */
    public void oomHandlerScript() {
        // oom-handler.sh 内容：
        // #!/bin/bash
        // PID=$1
        // TIMESTAMP=$(date +%Y%m%d_%H%M%S)
        // 
        // # 1. 发送告警
        // curl -X POST "http://alert-service/api/alert" \
        //   -d "{\"level\":\"critical\",\"message\":\"OOM on PID $PID\"}"
        // 
        // # 2. 保存线程转储
        // jstack $PID > /var/log/app/thread_dump_$TIMESTAMP.txt 2>/dev/null
        // 
        // # 3. 优雅退出（让容器编排系统自动重启）
        // kill -15 $PID
    }
}
```

### 4.3 安全点（Safepoint）问题

```java
/**
 * 安全点相关问题与诊断
 * 
 * 安全点（Safepoint）是 JVM 中所有线程都暂停的点
 * GC STW 就是通过安全点机制实现的
 * 
 * 安全点的两个阶段：
 * 1. Time-To-Safepoint (TTSP)：从发起安全点请求到所有线程都到达安全点的时间
 * 2. Safepoint Operation：在安全点执行的操作（如 GC）
 * 
 * 问题：TTSP 过长会导致额外的停顿时间
 */
public class SafepointProblem {

    /**
     * 导致 TTSP 过长的常见原因
     */
    public void longTTSPCauses() {
        // 1. 可数循环（Counted Loop）
        // JIT 编译器优化后的可数循环不会插入安全点检查
        int[] array = new int[1_000_000_000];
        for (int i = 0; i < array.length; i++) { // 可数循环，无安全点
            array[i] = i;
        }
        // 解决：-XX:+UseCountedLoopSafepoints（JDK 14+默认开启）

        // 2. 大对象数组复制
        // System.arraycopy() 是 native 方法，期间不检查安全点

        // 3. 长时间运行的 JNI 代码
        // JNI 方法执行期间不检查安全点
    }

    /**
     * 安全点日志配置与分析
     */
    public void safepointDiagnosis() {
        // JDK 8:
        // -XX:+PrintSafepointStatistics
        // -XX:PrintSafepointStatisticsCount=1

        // JDK 9+:
        // -Xlog:safepoint:file=/var/log/safepoint.log

        // 日志中关注：
        // spin: 线程等待到达安全点的时间
        // block: 线程阻塞等待的时间
        // sync: 总的同步时间 = spin + block
        // 如果 sync 时间过长（>10ms），需要排查原因
    }
}
```

---

## 五、性能优化

### 5.1 GC 收集器选型决策

```
GC 收集器选型决策树：

                    ┌─────────────────┐
                    │  业务对延迟敏感度  │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
         极度敏感          中等敏感        吞吐优先
        (<1ms)          (<200ms)       (最大化处理量)
              │              │              │
              ▼              ▼              ▼
        ┌─────────┐   ┌─────────┐   ┌──────────────┐
        │  ZGC    │   │ 堆大小?  │   │ Parallel GC  │
        │(JDK15+) │   └────┬────┘   │ -XX:+UseParal│
        └─────────┘        │        │ lelGC         │
                     ┌─────┴──────┐ └──────────────┘
                     │            │
                  <8GB         ≥8GB
                     │            │
                     ▼            ▼
              ┌──────────┐ ┌──────────┐
              │   CMS    │ │   G1     │
              │(JDK<14)  │ │(JDK 9+  │
              │   或 G1   │ │ 默认)    │
              └──────────┘ └──────────┘

选型建议：
┌───────────┬──────────────┬─────────────────────────────────┐
│ 收集器     │ 适用场景      │ 关键指标                          │
├───────────┼──────────────┼─────────────────────────────────┤
│ Parallel  │ 批处理、离线计算│ 最大化吞吐量                      │
│ CMS       │ Web 服务(旧)  │ TP99 < 100ms, 堆 < 8GB          │
│ G1        │ Web 服务(新)  │ TP99 < 200ms, 堆 4-64GB         │
│ ZGC       │ 金融/交易系统  │ TP9999 < 1ms, 堆 8GB-16TB       │
│ Shenandoah│ 类似 ZGC     │ 低延迟, Red Hat JDK 支持          │
└───────────┴──────────────┴─────────────────────────────────┘
```

### 5.2 JVM 参数调优实战

#### 5.2.1 通用基础配置

```java
/**
 * JVM 参数调优模板
 */
public class JVMTuningTemplates {

    /**
     * 通用 Web 服务 JVM 配置模板
     * 适用于 4 核 8GB 的服务器，日均 QPS 500-2000
     */
    public static String[] webServiceTemplate() {
        return new String[]{
            // ===== 堆内存 =====
            "-Xms4g",                           // 初始堆 = 最大堆，避免运行时扩缩容
            "-Xmx4g",                           // 最大堆
            "-Xmn2g",                           // 年轻代 2G（堆的 1/2 到 2/3）
            "-Xss512k",                         // 线程栈 512KB

            // ===== 元空间 =====
            "-XX:MetaspaceSize=256m",            // 初始元空间（同最大值，避免扩容触发 FGC）
            "-XX:MaxMetaspaceSize=256m",         // 最大元空间

            // ===== 堆外内存 =====
            "-XX:MaxDirectMemorySize=1g",        // 堆外内存上限

            // ===== GC 收集器 (G1) =====
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",          // 目标停顿 200ms
            "-XX:G1HeapRegionSize=8m",           // Region 大小 8MB
            "-XX:InitiatingHeapOccupancyPercent=45", // 堆使用率 45% 触发并发标记

            // ===== 引用处理 =====
            "-XX:+ParallelRefProcEnabled",       // 并行引用处理

            // ===== OOM 保护 =====
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/var/log/app/heapdump/",
            "-XX:+ExitOnOutOfMemoryError",       // OOM 时退出（让容器自动重启）

            // ===== GC 日志 (JDK 9+) =====
            "-Xlog:gc*,gc+age=trace:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m"
        };
    }

    /**
     * 高吞吐批处理服务 JVM 配置
     * 适用于数据处理、ETL 等吞吐量优先的场景
     */
    public static String[] batchProcessingTemplate() {
        return new String[]{
            "-Xms8g",
            "-Xmx8g",
            "-XX:+UseParallelGC",                // 吞吐优先收集器
            "-XX:ParallelGCThreads=8",
            "-XX:+UseAdaptiveSizePolicy",         // 自适应大小策略
            "-XX:GCTimeRatio=99",                 // GC 时间占比不超过 1%
            "-XX:MaxGCPauseMillis=500",           // 允许较长停顿
            "-XX:MetaspaceSize=256m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/var/log/app/heapdump/"
        };
    }

    /**
     * 超低延迟交易服务 JVM 配置
     * 适用于金融交易、实时计算等对延迟极度敏感的场景
     */
    public static String[] lowLatencyTemplate() {
        return new String[]{
            "-Xms16g",
            "-Xmx16g",
            "-XX:+UseZGC",                       // ZGC 超低延迟
            // JDK 21+: "-XX:+ZGenerational",    // 分代 ZGC
            "-XX:ConcGCThreads=4",
            "-XX:+AlwaysPreTouch",                // 启动时预分配物理内存
            "-XX:-UseBiasedLocking",              // 关闭偏向锁（JDK 15+ 已默认关闭）
            "-XX:+UseNUMA",                       // NUMA 感知内存分配
            "-XX:+UseLargePages",                 // 大页内存
            "-XX:MetaspaceSize=256m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=/var/log/app/heapdump/",
            "-Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m"
        };
    }
}
```

#### 5.2.2 CMS 专项调优

```java
/**
 * CMS 收集器专项调优策略
 */
public class CMSTuning {

    /**
     * CMS 核心调优参数详解
     */
    public void cmsParameters() {
        // 1. 触发阈值调优
        // -XX:CMSInitiatingOccupancyFraction=75
        // 含义：Old Gen 使用率达到 75% 时触发 CMS GC
        // 调优依据：
        //   - 设置过高：可能来不及回收，导致 Concurrent Mode Failure
        //   - 设置过低：GC 过于频繁，浪费 CPU
        //   - 建议值：根据 Old Gen 增长速率和 CMS 回收耗时计算
        //   - 公式：阈值 = 1 - (CMS回收期间Old Gen增长量 / Old Gen总容量) - 安全余量

        // 2. 年轻代与老年代比例
        // -Xmn 控制年轻代大小
        // 年轻代过大：Young GC 耗时长（存活对象多时复制开销大）
        // 年轻代过小：Young GC 频率高，对象过早晋升到老年代
        // 建议：年轻代 = 堆的 1/3 到 1/2

        // 3. Survivor 区调优
        // -XX:SurvivorRatio=8（Eden:S0:S1 = 8:1:1）
        // -XX:MaxTenuringThreshold=6
        // 通过 -XX:+PrintTenuringDistribution 观察对象年龄分布
        // 如果大量对象在较小年龄就晋升，考虑增大 Survivor

        // 4. Remark 优化
        // -XX:+CMSScavengeBeforeRemark
        // 在 CMS Remark 前先做一次 Young GC
        // 减少年轻代对象引用老年代的扫描量
        // 可以显著缩短 Remark 停顿时间
    }

    /**
     * CMS 调优诊断流程
     */
    public void cmsTuningWorkflow() {
        // Step 1: 建立基线
        //   记录当前 GC 频率、停顿时间、吞吐量
        //   工具：GCeasy（在线 GC 日志分析）、GCViewer

        // Step 2: 分析 GC 日志
        //   关注：Young GC 频率、Old GC 频率、STW 时间
        //   关注：是否有 Promotion Failure 或 Concurrent Mode Failure

        // Step 3: 制定调优方案
        //   根据分析结果调整对应参数

        // Step 4: 灰度验证
        //   在少量机器上应用新参数
        //   运行足够长的时间（至少 24 小时）
        //   对比调优前后的指标

        // Step 5: 全量推广
        //   确认无回归后全量推广
    }
}
```

#### 5.2.3 G1 专项调优

```java
/**
 * G1 收集器专项调优策略
 */
public class G1Tuning {

    /**
     * G1 核心调优参数详解
     */
    public void g1Parameters() {
        // 1. 目标停顿时间
        // -XX:MaxGCPauseMillis=200 (默认 200ms)
        // 这是一个"软目标"，G1 会尽力但不保证达到
        // 设置过小：G1 每次只回收少量 Region，回收速度跟不上分配速度
        //          可能导致 Mixed GC 频繁或触发 Full GC
        // 设置过大：停顿时间过长
        // 建议：根据业务 SLA 设定，通常 100-500ms

        // 2. Region 大小
        // -XX:G1HeapRegionSize=N (1MB~32MB, 必须是 2 的幂)
        // 默认值 = 堆大小 / 2048
        // 调大 Region：减少 Humongous 对象（>50% Region 大小的对象）
        // 调小 Region：更细粒度的回收控制

        // 3. IHOP (Initiating Heap Occupancy Percent)
        // -XX:InitiatingHeapOccupancyPercent=45
        // 整堆使用率达到 45% 时开始并发标记
        // G1 5.0+ 默认开启自适应 IHOP
        // -XX:-G1UseAdaptiveIHOP 关闭自适应

        // 4. Mixed GC 调优
        // -XX:G1MixedGCCountTarget=8
        //   并发标记后，最多执行 8 轮 Mixed GC
        // -XX:G1MixedGCLiveThresholdPercent=85
        //   只回收存活率低于 85% 的 Old Region
        // -XX:G1OldCSetRegionThresholdPercent=10
        //   每轮 Mixed GC 最多回收 10% 的 Old Region
    }

    /**
     * G1 常见问题与优化
     */
    public void g1CommonIssues() {
        // 问题1: Full GC
        // G1 的 Full GC 是单线程的（JDK 10 之前），耗时非常长
        // JDK 10 之后 Full GC 已改为并行
        // 排查：是否有大量 Humongous 分配？IHOP 是否设置过高？

        // 问题2: To-space exhausted
        // 复制阶段 To 空间不足
        // 排查：堆内存是否不足？是否存在大量存活对象？
        // 解决：增大堆内存，或增大 G1ReservePercent
        // -XX:G1ReservePercent=10 (默认 10%)

        // 问题3: Evacuation Failure
        // 类似 CMS 的 Promotion Failure
        // 堆空间碎片化或不足
        // 解决：增大堆，或减小 MaxGCPauseMillis 让 G1 更积极回收

        // 问题4: Humongous 分配导致频繁 GC
        // 超过 50% Region 大小的对象直接分配为 Humongous
        // 解决：增大 Region 大小，或优化代码避免大对象
    }
}
```

### 5.3 代码级 GC 优化

```java
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代码层面的 GC 友好实践
 */
public class CodeLevelGCOptimization {

    /**
     * 1. 对象复用：使用对象池
     */
    public void objectPooling() {
        // 反面示例：频繁创建 StringBuilder
        // for (int i = 0; i < 1000000; i++) {
        //     StringBuilder sb = new StringBuilder();
        //     sb.append("data").append(i);
        //     process(sb.toString());
        // }

        // 正面示例：复用 StringBuilder
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < 1000000; i++) {
            sb.setLength(0);  // 清空而非重新创建
            sb.append("data").append(i);
            process(sb.toString());
        }
    }

    /**
     * 2. 集合容量预估：避免扩容导致的数组复制
     */
    public void collectionSizing() {
        // 反面示例：默认容量，多次扩容
        List<String> list = new ArrayList<>(); // 默认容量 10
        for (int i = 0; i < 10000; i++) {
            list.add("item_" + i); // 多次扩容，产生大量被丢弃的旧数组
        }

        // 正面示例：预估容量
        List<String> betterList = new ArrayList<>(10000);
        for (int i = 0; i < 10000; i++) {
            betterList.add("item_" + i); // 无扩容
        }

        // HashMap 同理
        // initialCapacity = expectedSize / loadFactor + 1
        Map<String, Object> map = new HashMap<>(16000 / 3 * 4 + 1);
    }

    /**
     * 3. 避免在热路径中创建临时对象
     */
    public void avoidTemporaryObjects() {
        // 反面示例：日期格式化器每次创建
        // for (Date date : dates) {
        //     SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        //     String formatted = sdf.format(date);
        // }

        // 正面示例：ThreadLocal 缓存或使用 DateTimeFormatter（线程安全）
        java.time.format.DateTimeFormatter formatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        // DateTimeFormatter 是线程安全的，可以安全共享
    }

    /**
     * 4. 流式处理替代全量加载
     */
    public void streamProcessing() {
        // 反面示例：全量加载到内存
        // List<Record> allRecords = database.queryAll(); // 可能百万条
        // List<Result> results = allRecords.stream()
        //     .filter(r -> r.isActive())
        //     .map(this::transform)
        //     .collect(Collectors.toList());

        // 正面示例：分页处理或游标查询
        // int pageSize = 1000;
        // int offset = 0;
        // while (true) {
        //     List<Record> page = database.query(offset, pageSize);
        //     if (page.isEmpty()) break;
        //     page.forEach(this::processAndFlush);
        //     offset += pageSize;
        // }
    }

    /**
     * 5. 注意自动装箱/拆箱
     */
    public void avoidAutoboxing() {
        // 反面示例：大量自动装箱
        Long sum = 0L;
        for (long i = 0; i < 1_000_000; i++) {
            sum += i; // 每次 += 都会创建新的 Long 对象
        }

        // 正面示例：使用原始类型
        long betterSum = 0L;
        for (long i = 0; i < 1_000_000; i++) {
            betterSum += i; // 无装箱
        }
    }

    /**
     * 6. 及时释放不再使用的引用
     */
    public void nullifyReferences() {
        // 在长方法中，大对象用完后主动置 null
        byte[] largeBuffer = new byte[100 * 1024 * 1024]; // 100MB
        processBuffer(largeBuffer);
        largeBuffer = null; // 提前允许 GC 回收

        // 后续还有很多耗时操作...
        doOtherExpensiveWork();
    }

    private void process(String s) {}
    private void processBuffer(byte[] buf) {}
    private void doOtherExpensiveWork() {}
}
```

### 5.4 内存分析工具实战

```java
/**
 * JVM 诊断工具使用指南
 */
public class DiagnosticToolsGuide {

    /**
     * 1. jstat - JVM 统计信息监控
     */
    public void jstatUsage() {
        // 查看 GC 统计信息（每 1 秒采样，共采 10 次）
        // jstat -gcutil <pid> 1000 10
        //
        // 输出示例：
        //   S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT
        //   0.00  45.23  67.89  34.56  95.12  92.34   1234    12.345    5    3.456   15.801
        //
        // 字段说明：
        // S0/S1:  Survivor 0/1 使用率（%）
        // E:      Eden 使用率（%）
        // O:      Old Gen 使用率（%）
        // M:      MetaSpace 使用率（%）
        // CCS:    Compressed Class Space 使用率（%）
        // YGC:    Young GC 次数
        // YGCT:   Young GC 总耗时（秒）
        // FGC:    Full GC 次数
        // FGCT:   Full GC 总耗时（秒）
        // GCT:    GC 总耗时（秒）

        // 查看堆内存详情
        // jstat -gccapacity <pid>

        // 查看 GC 原因
        // jstat -gccause <pid> 1000
    }

    /**
     * 2. jmap - 堆内存映射
     */
    public void jmapUsage() {
        // 查看堆内存摘要
        // jmap -heap <pid>

        // 查看对象统计（前 20 个占用最多的类）
        // jmap -histo <pid> | head -20

        // 只看存活对象（会触发一次 Full GC）
        // jmap -histo:live <pid> | head -20

        // 生成堆转储
        // jmap -dump:format=b,file=heap.hprof <pid>

        // 生成存活对象的堆转储（推荐，文件更小）
        // jmap -dump:live,format=b,file=heap_live.hprof <pid>

        // 注意：在大堆场景下，jmap -dump 可能导致长时间 STW
        // 生产环境建议在流量低谷时执行
    }

    /**
     * 3. jstack - 线程转储
     */
    public void jstackUsage() {
        // 打印线程转储
        // jstack <pid>

        // 强制打印（即使 JVM 无响应）
        // jstack -F <pid>

        // 打印锁信息
        // jstack -l <pid>

        // 常用分析方法：
        // 1. 连续打印 3 次，间隔 1 秒
        //    for i in 1 2 3; do jstack <pid> > thread_$i.txt; sleep 1; done
        // 2. 对比 3 次结果，找出持续处于同一位置的线程（可能是死锁或死循环）

        // 查找死锁
        // jstack <pid> 输出末尾会自动检测死锁
        // "Found one Java-level deadlock:"
    }

    /**
     * 4. arthas - 在线诊断工具
     */
    public void arthasUsage() {
        // 启动 arthas
        // java -jar arthas-boot.jar

        // 常用命令：

        // dashboard     实时仪表盘（CPU、内存、GC、线程）
        // thread        查看线程信息
        // thread -n 3   查看 CPU 最高的 3 个线程
        // thread -b     查找阻塞其他线程的线程

        // jvm           查看 JVM 信息
        // memory        查看内存使用
        // gc            查看 GC 信息

        // heapdump /tmp/heap.hprof  生成堆转储
        // heapdump --live /tmp/heap.hprof  只转储存活对象

        // sc -d com.example.MyClass  搜索类信息
        // jad com.example.MyClass    反编译类

        // watch com.example.MyService method "{params,returnObj,throwExp}" -x 3
        //   观察方法调用的参数和返回值

        // trace com.example.MyService method
        //   跟踪方法调用链和耗时

        // profiler start   启动 CPU 火焰图采样
        // profiler stop    停止采样并生成火焰图
        // profiler stop --format html --file /tmp/profiler.html
    }

    /**
     * 5. MAT (Memory Analyzer Tool) - 堆转储分析
     */
    public void matUsage() {
        // 分析 heapdump 文件的步骤：

        // Step 1: 打开 heap.hprof 文件

        // Step 2: 查看 Leak Suspects Report
        //   自动分析可能的内存泄漏点
        //   显示占用内存最大的对象及其引用链

        // Step 3: 查看 Dominator Tree
        //   显示对象支配树
        //   Retained Heap = 如果该对象被回收，可释放的总内存
        //   找到 Retained Heap 最大的对象

        // Step 4: 通过 GC Roots 路径分析
        //   右键对象 → Merge Shortest Paths to GC Roots → exclude weak/soft references
        //   查看对象为什么不能被 GC 回收

        // Step 5: OQL 查询
        //   SELECT * FROM java.util.HashMap WHERE size > 10000
        //   找出大 Map 对象

        // Step 6: 比较两个堆转储
        //   对比泄漏前后的堆，找出增长最多的对象类型
    }

    /**
     * 6. GC 日志分析工具
     */
    public void gcLogAnalysisTools() {
        // 1. GCeasy（https://gceasy.io/）
        //    在线 GC 日志分析工具
        //    自动解析 GC 日志，生成可视化报告
        //    提供调优建议

        // 2. GCViewer
        //    开源 GC 日志可视化工具
        //    支持 CMS、G1、ZGC 等各种 GC 日志格式

        // 3. JProfiler
        //    商业 Java 性能分析工具
        //    实时内存分析、CPU 分析、线程分析

        // 4. VisualVM
        //    JDK 自带的可视化监控工具
        //    支持远程连接、堆分析、线程分析
    }
}
```

### 5.5 JVM 诊断平台建设

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         JVM 诊断平台架构                                  │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │                         数据采集层                                    │ │
│  │                                                                     │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐        │ │
│  │  │ GC 日志   │  │ JMX 指标  │  │ 堆转储    │  │ 线程转储      │        │ │
│  │  │ 实时采集   │  │ 定时采集  │  │ 按需触发  │  │ 自动/手动     │        │ │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────────┘        │ │
│  └──────────────────────────────┬──────────────────────────────────────┘ │
│                                 │                                       │
│  ┌──────────────────────────────▼──────────────────────────────────────┐ │
│  │                         智能分析层                                    │ │
│  │                                                                     │ │
│  │  ┌────────────────────┐  ┌────────────────────┐                    │ │
│  │  │ JVM 参数诊断         │  │ GC 日志分析          │                    │ │
│  │  │                    │  │                    │                    │ │
│  │  │ ● 参数合理性检查    │  │ ● GC 频率分析       │                    │ │
│  │  │ ● 参数冲突检测     │  │ ● 停顿时间分布      │                    │ │
│  │  │ ● 自动调优建议     │  │ ● 吞吐量计算        │                    │ │
│  │  │ ● 与同类服务对比   │  │ ● 异常 GC 检测      │                    │ │
│  │  └────────────────────┘  └────────────────────┘                    │ │
│  │                                                                     │ │
│  │  ┌────────────────────┐  ┌────────────────────┐                    │ │
│  │  │ 内存分析             │  │ 趋势预测             │                    │ │
│  │  │                    │  │                    │                    │ │
│  │  │ ● 堆转储自动分析   │  │ ● 内存泄漏预警      │                    │ │
│  │  │ ● 大对象检测       │  │ ● OOM 风险预测      │                    │ │
│  │  │ ● 泄漏路径追踪     │  │ ● 容量规划建议      │                    │ │
│  │  │ ● 类加载分析       │  │ ● GC 劣化趋势      │                    │ │
│  │  └────────────────────┘  └────────────────────┘                    │ │
│  └──────────────────────────────┬──────────────────────────────────────┘ │
│                                 │                                       │
│  ┌──────────────────────────────▼──────────────────────────────────────┐ │
│  │                         可视化展示层                                  │ │
│  │                                                                     │ │
│  │  ● GC 停顿时间趋势图          ● 堆内存各分代使用趋势                  │ │
│  │  ● GC 频率与吞吐量仪表盘       ● 对象分配速率监控                     │ │
│  │  ● 参数配置一览表              ● 告警历史与处理记录                    │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 六、最佳实践

### 6.1 JVM 配置检查清单

```
┌─────────────────────────────────────────────────────────────────────┐
│                    JVM 配置检查清单 (Checklist)                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  □ 堆内存                                                           │
│    □ -Xms 与 -Xmx 设为相同值，避免运行时扩缩容                        │
│    □ 堆大小为物理内存的 50-70%（留空间给 OS、MetaSpace、堆外内存）      │
│    □ 年轻代大小（-Xmn）经过评估，不宜过大或过小                        │
│                                                                     │
│  □ 元空间                                                           │
│    □ -XX:MetaspaceSize 与 -XX:MaxMetaspaceSize 设为相同值             │
│    □ 初始值足够大（256m+），避免启动阶段频繁扩容触发 Full GC            │
│                                                                     │
│  □ GC 收集器                                                        │
│    □ 根据业务场景选择合适的收集器                                      │
│    □ 收集器相关参数经过合理配置                                        │
│    □ GC 日志已正确配置并开启日志轮转                                   │
│                                                                     │
│  □ OOM 保护                                                         │
│    □ 已开启 -XX:+HeapDumpOnOutOfMemoryError                          │
│    □ HeapDumpPath 指向有足够空间的目录                                 │
│    □ 已配置 OOM 时的处理策略（告警/重启）                              │
│                                                                     │
│  □ 监控告警                                                          │
│    □ GC 频率告警已配置                                                │
│    □ GC 停顿时间告警已配置                                            │
│    □ 堆内存使用率告警已配置                                            │
│    □ MetaSpace 使用率告警已配置                                       │
│                                                                     │
│  □ 代码层面                                                          │
│    □ 无 System.gc() 显式调用（或有合理理由）                           │
│    □ 资源（连接、流）使用 try-with-resources 管理                      │
│    □ 集合类初始化时预估容量                                            │
│    □ ThreadLocal 在使用后调用 remove()                                 │
│    □ 缓存有容量上限和过期策略                                          │
│    □ 无 finalize() 方法（使用 Cleaner 替代）                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 GC 问题排查标准流程

```java
/**
 * GC 问题排查标准操作流程（SOP）
 */
public class GCTroubleshootingSOP {

    /**
     * Phase 1: 问题发现与确认
     */
    public void phase1_Discovery() {
        // 1. 告警触发或用户反馈延迟升高
        // 2. 确认是否为 GC 问题：
        //    - 检查 GC 频率是否突增
        //    - 检查 GC 停顿时间是否异常
        //    - 检查堆内存使用趋势
        //    - 排除其他原因（网络、数据库、下游服务等）
    }

    /**
     * Phase 2: 信息收集
     */
    public void phase2_DataCollection() {
        // 1. 收集 GC 日志
        //    确保 GC 日志已开启并可获取

        // 2. 收集 JVM 运行时信息
        //    jstat -gcutil <pid> 1000 30   （每秒采样，共 30 次）
        //    jstat -gccause <pid> 1000 10  （查看 GC 原因）

        // 3. 如果怀疑内存泄漏
        //    jmap -histo <pid> | head -30  （查看对象分布）
        //    间隔 5 分钟再执行一次，对比增长最快的对象

        // 4. 如果怀疑线程问题
        //    jstack <pid> > thread_dump.txt

        // 5. 如果需要深入分析
        //    jmap -dump:live,format=b,file=heap.hprof <pid>
        //    注意：大堆转储可能导致长时间 STW
    }

    /**
     * Phase 3: 分析定位
     */
    public void phase3_Analysis() {
        // 1. 分析 GC 日志
        //    上传到 GCeasy 或使用 GCViewer
        //    关注：GC Cause、停顿时间分布、各代空间变化

        // 2. 判断问题类型
        //    A. Young GC 频繁 → 检查对象分配速率，考虑增大年轻代
        //    B. Old GC/Full GC 频繁 → 检查是否有内存泄漏或大对象
        //    C. 单次 GC 停顿过长 → 检查堆大小、收集器配置
        //    D. Promotion Failure → 检查老年代碎片化
        //    E. Concurrent Mode Failure → 调整 CMS 触发阈值

        // 3. 如果是内存泄漏
        //    使用 MAT 分析堆转储
        //    定位泄漏对象和持有路径
    }

    /**
     * Phase 4: 制定方案
     */
    public void phase4_Solution() {
        // 1. 参数调优类：制定新的 JVM 参数配置
        // 2. 代码修复类：修复内存泄漏或不合理的对象分配
        // 3. 架构优化类：考虑更换 GC 收集器或调整堆大小
        // 4. 记录调优依据和预期效果
    }

    /**
     * Phase 5: 灰度验证
     */
    public void phase5_Validation() {
        // 1. 在预发布环境验证
        // 2. 选择少量线上机器灰度（如 1-2 台）
        // 3. 运行至少 24 小时，覆盖业务高峰
        // 4. 对比调优前后指标：
        //    - GC 频率
        //    - GC 停顿时间（TP50/TP99/TP999/Max）
        //    - GC 吞吐量
        //    - 服务延迟指标
        //    - Old Gen 使用趋势
    }

    /**
     * Phase 6: 全量推广与监控
     */
    public void phase6_Rollout() {
        // 1. 确认灰度无回归后，全量推广
        // 2. 推广后持续监控 7 天
        // 3. 记录调优经验，更新团队知识库
        // 4. 完善监控告警规则
    }
}
```

### 6.3 各版本 JDK GC 演进

```
┌────────┬──────────────────────────────────────────────────────────┐
│ JDK 版本│ GC 关键变化                                              │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 7  │ G1 可用（实验性）                                         │
│        │ PermGen 仍存在                                           │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 8  │ PermGen 移除，替换为 MetaSpace                            │
│        │ 默认收集器：Parallel GC (Server) / Serial GC (Client)     │
│        │ G1 进入生产可用状态                                        │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 9  │ G1 成为默认收集器                                         │
│        │ CMS 标记为废弃（-XX:+UseConcMarkSweepGC 触发警告）         │
│        │ 统一日志框架（-Xlog）                                      │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 10 │ G1 Full GC 改为并行                                       │
│        │ 应用类数据共享（AppCDS）                                   │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 11 │ ZGC 实验性引入（Linux only）                               │
│        │ Epsilon GC（No-Op GC）实验性引入                           │
│        │ LTS 版本                                                  │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 12 │ Shenandoah GC 实验性引入                                  │
│        │ G1 支持可中断的 Mixed GC                                   │
│        │ G1 支持自动归还未使用内存                                   │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 13 │ ZGC 支持最大 16TB 堆                                      │
│        │ ZGC 支持归还未使用内存                                     │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 14 │ CMS 正式移除                                              │
│        │ ZGC 支持 macOS 和 Windows                                 │
│        │ G1 支持 NUMA 感知内存分配                                  │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 15 │ ZGC 正式发布（Production Ready）                           │
│        │ Shenandoah 正式发布                                       │
│        │ 偏向锁默认关闭                                            │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 17 │ LTS 版本                                                  │
│        │ ZGC 性能持续优化                                           │
├────────┼──────────────────────────────────────────────────────────┤
│ JDK 21 │ 分代 ZGC（Generational ZGC）                               │
│        │ LTS 版本                                                  │
│        │ 虚拟线程正式发布（对 GC 有间接影响）                        │
└────────┴──────────────────────────────────────────────────────────┘
```

### 6.4 常见误区纠正

```java
/**
 * JVM 与 GC 领域常见误区与纠正
 */
public class CommonMisconceptions {

    /**
     * 误区1: "引用计数法无法处理循环引用"
     * 纠正: 引用计数法可以通过 Recycler 算法等方式处理循环引用
     * 实际上 JVM 未采用引用计数的主要原因是多线程环境下计数器同步开销过大
     */
    public void misconception1() {}

    /**
     * 误区2: "Full GC 一定会回收 Young Gen + Old Gen + MetaSpace"
     * 纠正: Full GC 的行为取决于收集器实现
     * 在某些收集器中，Full GC 可能只回收 Old Gen
     * JDK 规范中 Full GC 并无严格定义
     */
    public void misconception2() {}

    /**
     * 误区3: "调大堆内存就能解决 GC 问题"
     * 纠正: 更大的堆意味着单次 Full GC 的停顿时间更长
     * 如果问题根因是内存泄漏，调大堆只是推迟了 OOM
     * 需要根据具体问题选择合适的方案
     */
    public void misconception3() {}

    /**
     * 误区4: "GC 调优就是调 JVM 参数"
     * 纠正: GC 调优应该优先从代码层面优化
     * 减少不必要的对象创建 > 选择合适的数据结构 > 调整 JVM 参数
     * 参数调优是最后手段
     */
    public void misconception4() {}

    /**
     * 误区5: "ZGC 是银弹，所有服务都应该用 ZGC"
     * 纠正: ZGC 虽然暂停时间极低，但有额外开销
     * - 读屏障有运行时开销
     * - 内存占用略高（染色指针需要额外空间）
     * - 对 CPU 有更高要求
     * 批处理、数据分析等吞吐优先场景，Parallel GC 可能更合适
     */
    public void misconception5() {}

    /**
     * 误区6: "-XX:+DisableExplicitGC 可以放心使用"
     * 纠正: 此参数会禁用所有 System.gc() 调用
     * 但 NIO DirectByteBuffer 依赖 System.gc() 触发堆外内存回收
     * 禁用后可能导致堆外内存 OOM
     * 更好的方案是 -XX:+ExplicitGCInvokesConcurrent
     */
    public void misconception6() {}

    /**
     * 误区7: "对象都在堆上分配"
     * 纠正: JVM 有以下优化可能让对象不在堆上分配
     * 1. 逃逸分析 + 标量替换：对象拆解为基本类型，分配在栈上
     * 2. TLAB：仍在堆上但通过线程本地缓冲区提高效率
     * -XX:+DoEscapeAnalysis（JDK 8+ 默认开启）
     * -XX:+EliminateAllocations（标量替换，默认开启）
     */
    public void misconception7() {}
}
```

### 6.5 生产环境 JVM 参数模板汇总

```java
/**
 * 生产环境 JVM 参数模板
 * 根据不同场景和 JDK 版本选择对应模板
 */
public class ProductionJVMTemplates {

    /**
     * 模板1: JDK 8 + CMS (兼容老系统)
     * 适用：4C8G 服务器，Web 服务，QPS 1000-5000
     */
    public static final String[] JDK8_CMS = {
        "-server",
        "-Xms4g", "-Xmx4g",
        "-Xmn2g",
        "-Xss512k",
        "-XX:MetaspaceSize=256m", "-XX:MaxMetaspaceSize=256m",
        "-XX:+UseConcMarkSweepGC", "-XX:+UseParNewGC",
        "-XX:CMSInitiatingOccupancyFraction=70",
        "-XX:+UseCMSInitiatingOccupancyOnly",
        "-XX:+CMSParallelRemarkEnabled",
        "-XX:+CMSScavengeBeforeRemark",
        "-XX:+UseCMSCompactAtFullCollection",
        "-XX:CMSFullGCsBeforeCompaction=0",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:+ParallelRefProcEnabled",
        "-XX:SurvivorRatio=8",
        "-XX:MaxTenuringThreshold=8",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=/var/log/app/heapdump/",
        "-XX:ErrorFile=/var/log/app/hs_err_pid%p.log",
        "-Xloggc:/var/log/app/gc-%t.log",
        "-XX:+PrintGCDetails", "-XX:+PrintGCDateStamps",
        "-XX:+PrintTenuringDistribution",
        "-XX:+UseGCLogFileRotation",
        "-XX:NumberOfGCLogFiles=10",
        "-XX:GCLogFileSize=100M"
    };

    /**
     * 模板2: JDK 11/17 + G1 (推荐默认)
     * 适用：4C8G 或 8C16G 服务器，通用 Web 服务
     */
    public static final String[] JDK11_G1 = {
        "-server",
        "-Xms6g", "-Xmx6g",
        "-Xss512k",
        "-XX:MetaspaceSize=256m", "-XX:MaxMetaspaceSize=256m",
        "-XX:MaxDirectMemorySize=1g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-XX:G1HeapRegionSize=8m",
        "-XX:InitiatingHeapOccupancyPercent=45",
        "-XX:+ParallelRefProcEnabled",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:+AlwaysPreTouch",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=/var/log/app/heapdump/",
        "-XX:ErrorFile=/var/log/app/hs_err_pid%p.log",
        "-Xlog:gc*,gc+age=trace,gc+heap=debug:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m"
    };

    /**
     * 模板3: JDK 17/21 + ZGC (超低延迟)
     * 适用：8C16G+ 服务器，对延迟极度敏感的服务
     */
    public static final String[] JDK17_ZGC = {
        "-server",
        "-Xms12g", "-Xmx12g",
        "-Xss512k",
        "-XX:MetaspaceSize=256m", "-XX:MaxMetaspaceSize=256m",
        "-XX:MaxDirectMemorySize=2g",
        "-XX:+UseZGC",
        // JDK 21: "-XX:+ZGenerational",
        "-XX:ConcGCThreads=4",
        "-XX:+AlwaysPreTouch",
        "-XX:+UseNUMA",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:+ParallelRefProcEnabled",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=/var/log/app/heapdump/",
        "-XX:ErrorFile=/var/log/app/hs_err_pid%p.log",
        "-Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=10,filesize=100m"
    };
}
```

### 6.6 GC 调优经验总结

```
┌─────────────────────────────────────────────────────────────────────┐
│                     GC 调优核心经验 (Top 10)                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. 代码优化优先，参数调优其次                                        │
│     减少对象创建 > 优化数据结构 > 调整 GC 参数                        │
│                                                                     │
│  2. -Xms 与 -Xmx 设为相同值                                         │
│     避免运行时堆扩缩容带来的性能抖动                                   │
│                                                                     │
│  3. MetaspaceSize 与 MaxMetaspaceSize 设为相同值                     │
│     避免 MetaSpace 扩容触发不必要的 Full GC                           │
│                                                                     │
│  4. 始终开启 GC 日志                                                 │
│     GC 日志开销极小（<1%），但出问题时是最重要的诊断依据                │
│                                                                     │
│  5. 始终配置 HeapDumpOnOutOfMemoryError                              │
│     OOM 发生时如果没有堆转储，等同于丢失案发现场                        │
│                                                                     │
│  6. 不要使用 -XX:+DisableExplicitGC                                  │
│     改用 -XX:+ExplicitGCInvokesConcurrent                            │
│     避免影响 NIO DirectByteBuffer 的堆外内存回收                      │
│                                                                     │
│  7. 关注 GC 吞吐量指标                                               │
│     GC 吞吐量 ≥ 99.99%（四个九）为健康状态                            │
│     gc.meantime per minute > 6ms → 低于四个九                        │
│                                                                     │
│  8. G1 不需要手动设置 -Xmn                                           │
│     G1 会自动管理年轻代大小，手动设置反而干扰 G1 的自适应策略            │
│                                                                     │
│  9. 调优前建立基线，调优后量化对比                                     │
│     所有调优必须有数据支撑，不能"凭感觉"                               │
│                                                                     │
│  10. 灰度发布 JVM 参数变更                                           │
│      JVM 参数变更等同于核心配置变更，必须灰度验证                       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 附录

### A. JVM 核心参数速查表

```
┌───────────────────────────────────────┬─────────────────────────────────────┐
│  参数                                 │  说明                               │
├───────────────────────────────────────┼─────────────────────────────────────┤
│  -Xms                                │  初始堆大小                          │
│  -Xmx                                │  最大堆大小                          │
│  -Xmn                                │  年轻代大小                          │
│  -Xss                                │  线程栈大小                          │
│  -XX:MetaspaceSize                   │  初始 MetaSpace 大小                 │
│  -XX:MaxMetaspaceSize                │  最大 MetaSpace 大小                 │
│  -XX:MaxDirectMemorySize             │  最大堆外内存                        │
│  -XX:SurvivorRatio                   │  Eden:Survivor 比例                  │
│  -XX:MaxTenuringThreshold            │  晋升年龄阈值（默认 15）              │
│  -XX:PretenureSizeThreshold          │  直接进入老年代的对象大小阈值         │
│  -XX:ParallelGCThreads               │  并行 GC 线程数                      │
│  -XX:ConcGCThreads                   │  并发 GC 线程数                      │
│  -XX:CMSInitiatingOccupancyFraction  │  CMS 触发 Old Gen 使用率阈值         │
│  -XX:MaxGCPauseMillis                │  G1/ZGC 目标最大停顿时间             │
│  -XX:G1HeapRegionSize                │  G1 Region 大小                      │
│  -XX:InitiatingHeapOccupancyPercent  │  G1 触发并发标记的堆使用率            │
│  -XX:+UseG1GC                        │  使用 G1 收集器                      │
│  -XX:+UseZGC                         │  使用 ZGC 收集器                     │
│  -XX:+UseShenandoahGC                │  使用 Shenandoah 收集器              │
│  -XX:+ParallelRefProcEnabled         │  并行引用处理                        │
│  -XX:+ExplicitGCInvokesConcurrent    │  System.gc() 使用并发模式            │
│  -XX:+AlwaysPreTouch                 │  启动时预分配物理内存                 │
│  -XX:+HeapDumpOnOutOfMemoryError     │  OOM 时自动堆转储                    │
│  -XX:HeapDumpPath                    │  堆转储文件路径                      │
└───────────────────────────────────────┴─────────────────────────────────────┘
```

### B. 诊断工具速查表

```
┌──────────────┬───────────────────┬─────────────────────────────────────────┐
│  工具          │  定位               │  核心用途                                │
├──────────────┼───────────────────┼─────────────────────────────────────────┤
│  jstat        │  JDK 内置           │  实时查看 GC 统计信息                     │
│  jmap         │  JDK 内置           │  堆内存映射、对象统计、堆转储              │
│  jstack       │  JDK 内置           │  线程转储、死锁检测                       │
│  jcmd         │  JDK 内置           │  综合诊断命令（替代 jstat/jmap/jstack）    │
│  arthas       │  开源工具           │  在线诊断、方法追踪、火焰图                │
│  MAT          │  Eclipse 插件       │  堆转储深度分析、泄漏检测                  │
│  JProfiler    │  商业工具           │  全方位性能分析（CPU/内存/线程）            │
│  VisualVM     │  JDK 工具           │  可视化监控与采样分析                      │
│  GCeasy       │  在线工具           │  GC 日志自动分析与调优建议                 │
│  GCViewer     │  开源工具           │  GC 日志可视化                            │
│  async-profiler│ 开源工具           │  低开销 CPU/内存火焰图                     │
└──────────────┴───────────────────┴─────────────────────────────────────────┘
```

### C. GC 问题应急处理决策树

```
                        ┌───────────────────┐
                        │ 服务延迟突增 / OOM  │
                        └─────────┬─────────┘
                                  │
                     ┌────────────┴────────────┐
                     │     是否 GC 导致？        │
                     │  (检查 GC 日志/监控)      │
                     └────────────┬────────────┘
                            是    │     否
                     ┌────────────┘     └──→ 排查其他原因
                     │
              ┌──────┴───────┐
              │ 哪种 GC 异常？ │
              └──────┬───────┘
                     │
     ┌───────────────┼───────────────┬───────────────┐
     │               │               │               │
  Young GC       Old GC/CMS      Full GC          OOM
  频率过高        停顿过长       频率过高
     │               │               │               │
     ▼               ▼               ▼               ▼
  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐
  │增大Young │  │降低CMS    │  │查堆转储   │  │收集堆转储     │
  │Gen       │  │触发阈值   │  │分析内存   │  │分析泄漏根因   │
  │检查分配率│  │增大堆     │  │泄漏       │  │紧急扩容      │
  │优化代码  │  │换G1/ZGC  │  │优化代码   │  │重启恢复      │
  └─────────┘  └──────────┘  └──────────┘  └──────────────┘
```