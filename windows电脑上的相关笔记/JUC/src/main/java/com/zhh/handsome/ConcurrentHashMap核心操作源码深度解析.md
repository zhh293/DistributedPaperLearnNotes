# ConcurrentHashMap 核心操作源码深度解析（JDK 8）

> 基于 JDK 8 源码，逐行剖析 `putVal` / `transfer` 等核心方法，揭示 ConcurrentHashMap 的高并发设计精髓。

---

## 目录

1. [整体架构回顾](#1-整体架构回顾)
2. [spread() 哈希方法分析](#2-spread-哈希方法分析)
3. [initTable() 源码逐行分析](#3-inittable-源码逐行分析)
4. [putVal() 完整源码逐行深度分析](#4-putval-完整源码逐行深度分析)
5. [addCount() 源码分析](#5-addcount-源码分析)
6. [transfer() 完整源码逐行深度分析](#6-transfer-完整源码逐行深度分析)
7. [helpTransfer() 方法分析](#7-helptransfer-方法分析)
8. [get() 方法源码分析](#8-get-方法源码分析)
9. [size() / mappingCount() 计数方式](#9-size--mappingcount-计数方式)
10. [treeifyBin() 树化条件](#10-treeifybin-树化条件)
11. [关键设计总结](#11-关键设计总结)
12. [常见面试问题](#12-常见面试问题)

---

## 1. 整体架构回顾

### 1.1 Java 7 的分段锁架构

Java 7 的 ConcurrentHashMap 采用 **Segment 分段锁** 设计：

```
ConcurrentHashMap
  └── Segment[] (默认16个Segment)
        └── HashEntry[] (每个Segment内部是一个小HashMap)
              └── HashEntry (链表节点)
```

- 每个 Segment 继承自 `ReentrantLock`，锁住一个 Segment 内的所有操作
- 默认 16 个 Segment，理论上最大支持 16 个线程并发写
- 缺点：Segment 数量一旦初始化不能扩容，并发度固定；锁粒度仍偏粗

### 1.2 Java 8 的全新架构

Java 8 彻底重构，采用 **数组 + 链表/红黑树 + CAS + synchronized**：

```
ConcurrentHashMap
  └── Node[] table (哈希桶数组)
        ├── null              → 空桶，直接CAS写入
        ├── Node              → 链表头节点，synchronized锁头节点
        ├── TreeBin           → 红黑树包装节点，synchronized锁TreeBin
        └── ForwardingNode    → 扩容标记节点(hash=-1)，说明该桶正在迁移
```

### 1.3 Java 7 vs Java 8 核心对比

| 维度 | Java 7 | Java 8 |
|------|--------|--------|
| 锁机制 | Segment 分段锁（ReentrantLock） | CAS + synchronized（锁桶头节点） |
| 锁粒度 | Segment 级别（每个Segment含多个桶） | 桶级别（单个桶的头节点） |
| 并发度 | 固定（默认16） | 动态（等于桶数组长度） |
| 哈希冲突 | 链表 | 链表 + 红黑树（链表≥8且数组≥64时树化） |
| 扩容 | 单线程扩容 | 多线程协同扩容（stride分块） |
| 计数 | Segment.count 遍历求和 | LongAdder 思想（baseCount + CounterCell[]） |
| 查询 | 需要先定位Segment再定位桶 | 直接定位桶，volatile保证可见性 |

### 1.4 核心内部类

```java
// 普通链表节点
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;          // 节点哈希值
    final K key;
    volatile V val;          // volatile保证可见性
    volatile Node<K,V> next; // volatile保证可见性
}

// 转发节点（扩容期间占位）
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable; // 指向新数组
    // hash固定为MOVED(-1)
}

// 红黑树节点
static final class TreeNode<K,V> extends Node<K,V> {
    TreeNode<K,V> parent;
    TreeNode<K,V> left;
    TreeNode<K,V> right;
    TreeNode<K,V> prev;    // 便于删除时断链
    boolean red;
}

// 红黑树包装节点（锁的持有者）
static final class TreeBin<K,V> extends Node<K,V> {
    TreeNode<K,V> root;          // 红黑树根节点
    volatile TreeNode<K,V> first;// 链表头（用于遍历）
    volatile Thread waiter;      // 等待锁的线程
    volatile int lockState;      // 读写锁状态
    // lockState: 1=写锁 2=等待写锁 4/8/...=读锁
}
```

### 1.5 核心常量

```java
private static final int MOVED     = -1;       // ForwardingNode的hash值
private static final int TREEBIN   = -2;       // TreeBin的hash值
private static final int RESERVED  = -3;       // ReservationNode的hash值
static final int HASH_BITS = 0x7fffffff;       // 31位正整数掩码 0111...1
private static final int DEFAULT_CAPACITY = 16; // 默认初始容量
private static final float LOAD_FACTOR = 0.75f; // 负载因子
static final int TREEIFY_THRESHOLD = 8;        // 链表转红黑树阈值
static final int UNTREEIFY_THRESHOLD = 6;      // 红黑树退化链表阈值
static final int MIN_TREEIFY_CAPACITY = 64;    // 树化时数组最小长度
private static final int MIN_TRANSFER_STRIDE = 16; // 扩容时每线程最小步长
private static final int RESIZE_STAMP_BITS = 16;   // 扩容戳位数
private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1; // 最大扩容线程数
```

---

## 2. spread() 哈希方法分析

### 2.1 源码

```java
static final int spread(int h) {
    return (h ^ (h >>> 16)) & HASH_BITS;
}
```

### 2.2 逐步拆解

**第一步：`h >>> 16`（无符号右移16位）**

假设 h = `0x12345678`：
```
原始: 0001 0010 0011 0100 0101 0110 0111 1000
右移: 0000 0000 0000 0000 0001 0010 0011 0100
```

**第二步：`h ^ (h >>> 16)`（高16位与低16位异或）**

```
原始: 0001 0010 0011 0100 | 0101 0110 0111 1000
右移: 0000 0000 0000 0000 | 0001 0010 0011 0100
异或: 0001 0010 0011 0100 | 0100 0100 0100 1100
```

这样做的原因：
- 哈希表长度一般远小于 2^16，取模时只有低几位参与运算
- 如果原始哈希值的高位没参与，冲突概率大增
- 异或让高位信息"混入"低位，类似 HashMap 的扰动函数

**第三步：`& HASH_BITS`（`& 0x7fffffff`）**

`0x7fffffff` = `0111 1111 1111 1111 1111 1111 1111 1111`

- 将最高位（符号位）强制置为 0，确保结果为正数
- **为什么必须为正？** ConcurrentHashMap 中 hash 值有特殊含义：
  - `-1 (MOVED)`：ForwardingNode
  - `-2 (TREEBIN)`：TreeBin
  - `-3 (RESERVED)`：ReservationNode
  - 正数：普通节点
- 正数范围 `0 ~ 2^31-1`，足够使用

### 2.3 与 HashMap.hash() 的对比

```java
// HashMap的扰动函数
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

ConcurrentHashMap 多了一步 `& HASH_BITS`，确保 hash 不为负数，避免与特殊标记冲突。

---

## 3. initTable() 源码逐行分析

### 3.1 完整源码

```java
private final Node<K,V>[] initTable() {
    Node<K,V>[] tab; int sc;
    // 自旋，直到表初始化完成
    while ((tab = table) == null || tab.length == 0) {
        // 情况1：sizeCtl < 0，说明其他线程正在初始化或扩容
        if ((sc = sizeCtl) < 0) {
            Thread.yield(); // 让出CPU，等待初始化完成
        }
        // 情况2：CAS成功将sizeCtl设为-1，当前线程获得初始化权
        else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
            try {
                // 双重检查：可能在此期间已被其他线程初始化
                if ((tab = table) == null || tab.length == 0) {
                    // sc > 0 表示用户指定了初始容量，否则用默认值16
                    int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                    @SuppressWarnings("unchecked")
                    Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n];
                    table = tab = nt;
                    // 计算扩容阈值：n - n/4 = n * 0.75
                    // 这里用位运算等价：n - (n >>> 2) = n * 3/4
                    sc = n - (n >>> 2);
                }
            } finally {
                // 无论是否成功初始化，都设置sizeCtl
                // 如果tab已初始化，sc=扩容阈值
                // 如果tab未初始化（被其他线程抢先），sc保持原值
                sizeCtl = sc;
            }
            break; // 初始化完成，退出自旋
        }
    }
    return tab;
}
```

### 3.2 sizeCtl 的多重语义

`sizeCtl` 是 ConcurrentHashMap 中最精妙的变量之一，它在不同阶段表达不同含义：

| sizeCtl 值 | 含义 |
|-----------|------|
| > 0 | 初始化前的初始容量，或初始化后的扩容阈值（capacity * 0.75） |
| -1 | 表正在初始化（只有一个线程能初始化） |
| -(1 + nThreads) | 表正在扩容，nThreads 为参与扩容的线程数 |
| 0 | 未初始化，使用默认容量16 |

### 3.3 执行流程图

```
线程A、B同时调用put() → table为null → 进入initTable()
  │
  ├── 线程A: CAS(sizeCtl, 0, -1) 成功
  │     └── 创建数组，设置sizeCtl=阈值，break退出
  │
  └── 线程B: CAS(sizeCtl, 0, -1) 失败
        └── sizeCtl < 0 → Thread.yield() 让出CPU
              └── 再次循环，发现table != null → 退出while → 返回table
```

### 3.4 关键设计点

1. **CAS 竞争初始化**：用 CAS 将 `sizeCtl` 从 0 或正数改为 -1，保证只有一个线程执行初始化
2. **Thread.yield() 而非自旋**：未抢到初始化权的线程不空转，而是让出 CPU
3. **双重检查**：CAS 成功后再次检查 `table == null`，避免重复初始化
4. **finally 保证**：无论是否真正初始化，`sizeCtl` 都会被正确设置

---

## 4. putVal() 完整源码逐行深度分析

### 4.1 完整源码

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    // 【1】null 检查：key和value都不允许为null
    if (key == null || value == null) throw new NullPointerException();
    // 【2】计算hash值（扰动 + 确保正数）
    int hash = spread(key.hashCode());
    // 【3】binCount记录当前桶中元素个数，用于判断是否需要树化
    int binCount = 0;
    // 【4】自旋插入，直到成功
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        // ==========================================
        // 分支1：表未初始化
        // ==========================================
        if (tab == null || (n = tab.length) == 0)
            tab = initTable(); // 初始化表，然后继续自旋
        // ==========================================
        // 分支2：目标桶为空，直接CAS插入
        // ==========================================
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            // CAS尝试在空桶位置插入新节点
            if (casTabAt(tab, i, null,
                         new Node<K,V>(hash, key, value, null)))
                break; // CAS成功，直接退出自旋（无需加锁）
            // CAS失败，说明有竞争，继续自旋
        }
        // ==========================================
        // 分支3：桶头节点的hash为MOVED(-1)，说明正在扩容
        // ==========================================
        else if ((fh = f.hash) == MOVED)
            // 当前线程帮助扩容，扩容完后继续自旋插入
            tab = helpTransfer(tab, f);
        // ==========================================
        // 分支4：桶不为空且不在扩容，需要锁住头节点进行插入
        // ==========================================
        else {
            V oldVal = null;
            // 【4.1】synchronized锁住桶的头节点f
            synchronized (f) {
                // 【4.2】双重检查：再次确认f仍然是桶头节点
                // 防止在获取锁之前f被其他线程移除或改变
                if (tabAt(tab, i) == f) {
                    // 【4.3】fh >= 0 表示是普通链表节点
                    if (fh >= 0) {
                        binCount = 1;
                        // 【4.4】遍历链表
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            // 【4.5】找到相同key，更新value
                            if (e.hash == hash &&
                                ((ek = e.key) == key ||
                                 (ek != null && key.equals(ek)))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent) // onlyIfAbsent=false时覆盖
                                    e.val = value;
                                break; // 更新完成，退出遍历
                            }
                            Node<K,V> pred = e;
                            // 【4.6】到达链表末尾，尾插新节点
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key,
                                                          value, null);
                                break; // 插入完成，退出遍历
                            }
                        }
                    }
                    // 【4.7】fh == TREEBIN(-2)，表示是红黑树
                    else if (f instanceof TreeBin) {
                        Node<K,V> p;
                        binCount = 2;
                        // 调用TreeBin的putTreeVal方法插入红黑树
                        if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                                       value)) != null) {
                            oldVal = p.val;
                            if (!onlyIfAbsent)
                                p.val = value;
                        }
                    }
                }
            }
            // 【4.8】binCount != 0 说明执行了插入或更新操作
            if (binCount != 0) {
                // 【4.9】链表长度 >= 8，尝试树化
                if (binCount >= TREEIFY_THRESHOLD)
                    treeifyBin(tab, i);
                if (oldVal != null) // 是更新操作，直接返回旧值
                    return oldVal;
                break; // 是插入操作，退出自旋
            }
        }
    }
    // 【5】增加计数，并检查是否需要扩容
    addCount(1L, binCount);
    return null;
}
```

### 4.2 四大分支详解

#### 分支1：表未初始化

```java
if (tab == null || (n = tab.length) == 0)
    tab = initTable();
```

- 首次 `put` 时 `table` 为 `null`
- 调用 `initTable()` 完成初始化
- 初始化完成后**不 break**，继续自旋进入下一个分支

#### 分支2：目标桶为空 → CAS 插入

```java
else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
    if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
        break;
}
```

- `(n - 1) & hash`：等价于 `hash % n`（n 为 2 的幂）
- `tabAt()` 使用 `volatile` 语义读取，保证可见性
- `casTabAt()` 使用 CAS 原子操作插入，**无需加锁**
- 如果 CAS 失败（说明有其他线程先插入了），继续自旋
- **这是最轻量级的路径：无锁竞争，O(1) 完成**

为什么用 `tabAt` 而不直接 `tab[i]`？

```java
static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
    return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
}
```

因为 `tab` 数组本身不是 `volatile` 的，直接 `tab[i]` 无法保证读到最新值。通过 `Unsafe.getObjectVolatile` 以 `volatile` 语义读取，确保多线程下看到最新的桶头节点。

#### 分支3：桶头为 ForwardingNode → 帮助扩容

```java
else if ((fh = f.hash) == MOVED)
    tab = helpTransfer(tab, f);
```

- `f.hash == MOVED(-1)` 表示该桶已被迁移到新表
- `f` 是 `ForwardingNode`，其 `nextTable` 指向新数组
- 调用 `helpTransfer()` 帮助扩容，而不是阻塞等待
- 扩容完成后 `tab` 指向新表，继续自旋在新表中插入

#### 分支4：桶不为空 → synchronized 锁头节点

```java
synchronized (f) {
    // 双重检查 + 链表/红黑树操作
}
```

- 锁住桶的头节点 `f`，而非整个表
- **不同桶之间互不影响**，并发度等于桶数量
- 双重检查：`tabAt(tab, i) == f`，确保 `f` 仍然是桶头
- 桶内操作分两种：
  - **链表**（`fh >= 0`）：遍历链表，key 相同则更新，否则尾插
  - **红黑树**（`f instanceof TreeBin`）：调用 `putTreeVal` 插入

### 4.3 为什么锁头节点而不是锁桶索引？

锁索引（`Integer.valueOf(i)`）虽然也能工作，但：
1. 每次需要新建或缓存 Integer 对象
2. 锁的是 Integer 对象而非桶中的实际节点，无法防止桶头被替换
3. 锁头节点更直观——桶头是桶的"入口"，锁住入口就锁住了整个桶

### 4.4 putVal 执行流程总结

```
putVal(key, value, false)
  │
  ├── key/value null检查
  ├── 计算hash = spread(key.hashCode())
  │
  └── for(;;) 自旋
        ├── 表未初始化 → initTable() → 继续自旋
        ├── 桶为空 → CAS插入 → 成功则break
        ├── 桶头hash=MOVED → helpTransfer() → 继续自旋
        └── 桶不为空 → synchronized(f)
              ├── 双重检查
              ├── 链表: 遍历→更新/尾插
              └── 红黑树: putTreeVal
              └── binCount>=8 → treeifyBin()
        │
        └── addCount(1, binCount) → 计数+扩容判断
```

---

## 5. addCount() 源码分析

### 5.1 完整源码

```java
private final void addCount(long x, int check) {
    // =============================================
    // 第一部分：计数累加（LongAdder思想）
    // =============================================
    CounterCell[] as; long b, v; int m;
    // 【1】如果counterCells不为null，或CAS更新baseCount失败
    if ((as = counterCells) != null ||
        !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
        CounterCell a; long s; // s是当前线程要累加的值
        boolean uncontended = true;
        // 【2】进入以下任一条件，说明存在竞争：
        //   - counterCells为null
        //   - counterCells长度为0
        //   - 当前线程对应的cell为null
        //   - CAS更新当前cell失败
        if (as == null || (m = as.length - 1) < 0 ||
            (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
            !(uncontended =
              U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
            // 完整的累加逻辑（包含counterCells初始化/扩容）
            fullAddCount(x, uncontended);
            return; // fullAddCount后直接返回，不检查扩容
        }
    }
    // 【3】check >= 0 才检查是否需要扩容
    // putVal中check=binCount，putAll等批量操作check=-1不检查
    if (check <= 1)
        return;
    // =============================================
    // 第二部分：扩容判断
    // =============================================
    // 计算当前元素总数
    long s = sumCount();
    if (check >= 0) {
        Node<K,V>[] tab, nt; int n, sc;
        // 【4】循环检查，直到不需要扩容
        while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
               (n = tab.length) < MAXIMUM_CAPACITY) {
            // 【5】计算扩容戳
            int rs = resizeStamp(n);
            // 【6】如果正在扩容，尝试加入扩容
            if (sc < 0) {
                // 以下5种情况不参与扩容：
                // sc >>> RESIZE_STAMP_SHIFT != rs → 扩容戳不匹配
                // sc == rs + 1 → 最后一个扩容线程已退出
                // sc == rs + MAX_RESIZERS → 扩容线程已达上限
                // (nt = nextTable) == null → 新表还未创建
                // transferIndex <= 0 → 所有任务已分配完毕
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                    sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                    transferIndex <= 0)
                    break;
                // 【7】CAS将sizeCtl+1，增加一个扩容线程
                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                    transfer(tab, nt); // 参与扩容
            }
            // 【8】没有在扩容，当前线程发起扩容
            // 将sizeCtl设为 (rs << RESIZE_STAMP_SHIFT) + 2
            // 高16位是扩容戳，低16位是线程数(初始为2)
            else if (U.compareAndSwapInt(this, SIZECTL, sc,
                                         (rs << RESIZE_STAMP_SHIFT) + 2))
                transfer(tab, null); // 发起扩容，传入null表示需要创建新表
            s = sumCount(); // 重新计算总数
        }
    }
}
```

### 5.2 LongAdder 思想详解

ConcurrentHashMap 的计数借鉴了 `LongAdder` 的分段计数思想：

```
                baseCount (无竞争时直接CAS更新)
                    │
         ┌──────────┼──────────┐
         │          │          │
    CounterCell[0] CounterCell[1] ... CounterCell[n]
     (线程A的)     (线程B的)       (线程X的)
```

**工作流程：**

1. **无竞争**：直接 CAS 更新 `baseCount`
2. **有竞争**：每个线程根据自己的 probe 值（类似线程哈希）映射到一个 `CounterCell`，只 CAS 更新自己的 cell
3. **总数**：`sum = baseCount + Σ counterCells[i].value`

**优势**：不同线程更新不同的 cell，避免了所有线程竞争同一个变量的瓶颈。

### 5.3 扩容戳 resizeStamp

```java
static final int resizeStamp(int n) {
    return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
}
```

假设 `n = 16`：
- `Integer.numberOfLeadingZeros(16)` = 27（16 = 0001 0000，前面27个0）
- `1 << 15` = 32768 = `1000 0000 0000 0000`
- `resizeStamp(16)` = 27 | 32768 = 32795

扩容戳的含义：
- **高16位**中的低16位记录了 `numberOfLeadingZeros(n)`，即容量 n 的前导零个数
- **最高位**（第16位）为1，作为标识
- 在 `sizeCtl` 中：`sizeCtl = (resizeStamp << 16) | (扩容线程数 + 1)`

### 5.4 sizeCtl 扩容期间的编码

```
sizeCtl（扩容时）:
┌────────────────────┬─────────────────────┐
│   高16位：扩容戳    │  低16位：线程数+1     │
│   resizeStamp(n)   │  parallelism+1      │
└────────────────────┴─────────────────────┘
```

- `sizeCtl < 0` 且不是 -1，说明正在扩容
- 低16位减1 = 当前参与扩容的线程数
- 第一个发起扩容的线程将 sizeCtl 设为 `(rs << 16) + 2`，所以线程数为 1

---

## 6. transfer() 完整源码逐行深度分析

### 6.1 完整源码

```java
private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
    int n = tab.length, stride;
    // =============================================
    // 【1】计算每个线程负责的步长（桶数量）
    // =============================================
    // 单CPU: stride = n（一个线程负责全部）
    // 多CPU: stride = (n >>> 3) / NCPU，最小为MIN_TRANSFER_STRIDE(16)
    if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
        stride = MIN_TRANSFER_STRIDE; // 每个线程最少处理16个桶

    // =============================================
    // 【2】如果nextTab为null，当前线程是扩容发起者，需要创建新表
    // =============================================
    if (nextTab == null) {            // 发起扩容
        try {
            @SuppressWarnings("unchecked")
            // 新表容量 = 旧表容量 * 2
            Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
            nextTab = nt;
        } catch (Throwable ex) {      // OOM
            sizeCtl = Integer.MAX_VALUE;
            return;
        }
        nextTable = nextTab;
        transferIndex = n; // 迁移索引从旧表末尾开始
    }
    int nextn = nextTab.length;
    // 创建转发节点，hash=MOVED(-1)，nextTable指向新表
    ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab);
    // advance表示当前桶是否处理完，可以前进到下一个桶
    boolean advance = true;
    // finishing表示整个扩容是否完成
    boolean finishing = false;

    // =============================================
    // 【3】主循环：从后往前迁移桶
    // =============================================
    for (int i = 0, bound = 0;;) {
        Node<K,V> f; int fh;

        // -------------------------------------------
        // 【3.1】分配迁移任务区间 [bound, i]
        // -------------------------------------------
        while (advance) {
            int nextIndex, nextBound;
            // 情况1：当前区间还没处理完
            if (--i >= bound || finishing)
                advance = false;
            // 情况2：transferIndex <= 0，所有桶已被分配
            else if ((nextIndex = transferIndex) <= 0) {
                i = -1; // 标记：没有更多任务
                advance = false;
            }
            // 情况3：CAS分配一个步长的区间
            else if (U.compareAndSwapInt
                     (this, TRANSFERINDEX, nextIndex,
                      nextBound = (nextIndex > stride ?
                                   nextIndex - stride : 0))) {
                bound = nextBound;       // 区间下界
                i = nextIndex - 1;       // 当前处理的桶索引
                advance = false;         // 分配完成，退出while
            }
        }

        // -------------------------------------------
        // 【3.2】i越界检查：判断扩容是否完成
        // -------------------------------------------
        if (i < 0 || i >= n || i + n >= nextn) {
            int sc;
            // 整个扩容已完成
            if (finishing) {
                nextTable = null;   // 释放新表引用
                table = nextTab;    // 切换到新表
                sizeCtl = (n << 1) - (n >>> 1); // 新阈值 = 2n * 0.75
                return;
            }
            // 当前线程完成自己的任务，CAS将sizeCtl-1
            if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                // 如果sc-2 != 扩容戳左移16位，说明不是最后一个线程
                if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                    return; // 非最后一个线程，直接退出
                // 是最后一个线程，设置finishing=true，再做一轮检查
                finishing = advance = true;
                i = n; // 从头再检查一遍所有桶
            }
        }
        // -------------------------------------------
        // 【3.3】桶为空：放置ForwardingNode
        // -------------------------------------------
        else if ((f = tabAt(tab, i)) == null)
            advance = casTabAt(tab, i, null, fwd);
        // -------------------------------------------
        // 【3.4】桶已被迁移（头节点已是ForwardingNode）
        // -------------------------------------------
        else if ((fh = f.hash) == MOVED)
            advance = true; // 已经迁移过，跳过
        // -------------------------------------------
        // 【3.5】桶未迁移：锁住头节点，迁移链表/红黑树
        // -------------------------------------------
        else {
            synchronized (f) {
                // 双重检查：确保f仍然是桶头
                if (tabAt(tab, i) == f) {
                    Node<K,V> ln, hn;
                    // ===== 情况A：链表迁移 =====
                    if (fh >= 0) {
                        // fh & n 的结果决定节点在新表中的位置：
                        //   0 → 低位桶（原位置i）
                        //   n → 高位桶（原位置i+n）
                        // 因为n是2的幂，fh&n实际上只看fh的第log2(n)位
                        int runBit = fh & n;
                        Node<K,V> lastRun = f;
                        // 【lastRun优化】找到链表末尾连续相同高位/低位的子链
                        // 这样lastRun之后的节点不需要逐个移动
                        for (Node<K,V> p = f.next; p != null; p = p.next) {
                            int b = p.hash & n;
                            if (b != runBit) {
                                runBit = b;
                                lastRun = p;
                            }
                        }
                        // runBit=0，lastRun子链属于低位
                        if (runBit == 0) {
                            ln = lastRun;
                            hn = null;
                        }
                        // runBit=n，lastRun子链属于高位
                        else {
                            hn = lastRun;
                            ln = null;
                        }
                        // 从链表头遍历到lastRun，构建高低位链表
                        // 注意：这里用头插法构建，所以顺序与原链表相反
                        // 但lastRun之后的子链保持原顺序（直接引用）
                        for (Node<K,V> p = f; p != lastRun; p = p.next) {
                            int ph = p.hash; K pk = p.key; V pv = p.val;
                            if ((ph & n) == 0)
                                ln = new Node<K,V>(ph, pk, pv, ln);
                            else
                                hn = new Node<K,V>(ph, pk, pv, hn);
                        }
                        // 低位链放在新表的i位置
                        setTabAt(nextTab, i, ln);
                        // 高位链放在新表的i+n位置
                        setTabAt(nextTab, i + n, hn);
                        // 旧表位置放置ForwardingNode
                        setTabAt(tab, i, fwd);
                        advance = true;
                    }
                    // ===== 情况B：红黑树迁移 =====
                    else if (f instanceof TreeBin) {
                        TreeBin<K,V> t = (TreeBin<K,V>)f;
                        TreeNode<K,V> lo = null, loTail = null;
                        TreeNode<K,V> hi = null, hiTail = null;
                        int lc = 0, hc = 0;
                        // 遍历红黑树（通过first链表遍历）
                        for (Node<K,V> e = t.first; e != null; e = e.next) {
                            int h = e.hash;
                            TreeNode<K,V> p = new TreeNode<K,V>
                                (h, e.key, e.val, null, null);
                            if ((h & n) == 0) {
                                if ((p.prev = loTail) == null)
                                    lo = p;
                                else
                                    loTail.next = p;
                                loTail = p;
                                ++lc;
                            }
                            else {
                                if ((p.prev = hiTail) == null)
                                    hi = p;
                                else
                                    hiTail.next = p;
                                hiTail = p;
                                ++hc;
                            }
                        }
                        // 低位数 <= UNTREEIFY_THRESHOLD(6)，退化成链表
                        ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                            (hc != 0) ? new TreeBin<K,V>(lo) : t;
                        // 高位数 <= UNTREEIFY_THRESHOLD(6)，退化成链表
                        hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                            (lc != 0) ? new TreeBin<K,V>(hi) : t;
                        setTabAt(nextTab, i, ln);
                        setTabAt(nextTab, i + n, hn);
                        setTabAt(tab, i, fwd);
                        advance = true;
                    }
                }
            }
        }
    }
}
```

### 6.2 核心机制详解

#### 6.2.1 stride 步长分配

```
假设旧表 n=64，4个CPU：
stride = (64 >>> 3) / 4 = 8/4 = 2 → 但最小为16
stride = 16

transferIndex 初始 = 64

线程1: CAS(transferIndex, 64, 48) → 处理桶 [48, 63]
线程2: CAS(transferIndex, 48, 32) → 处理桶 [32, 47]
线程3: CAS(transferIndex, 32, 16) → 处理桶 [16, 31]
线程4: CAS(transferIndex, 16, 0)  → 处理桶 [0, 15]
```

- 每个线程通过 CAS 竞争 `transferIndex`，获取一个 `[bound, i]` 区间
- 从后往前分配，避免与 `put` 操作冲突（put 从前往后定位）
- 任务分配是**工作窃取**模式——先完成的线程可以获取更多任务

#### 6.2.2 高低链分离原理

为什么 `hash & n` 能决定新表位置？

```
旧表容量 n = 16 (二进制: 10000)
新表容量 = 32 (二进制: 100000)

假设某个节点的 hash 第5位（从1开始计数，即n对应的位）：
- 第5位为0: hash & 16 = 0 → 新位置 = i（和旧表一样）
- 第5位为1: hash & 16 = 16 → 新位置 = i + 16

示例：
hash1 = ...0 0101 (5),  hash1 & 16 = 0 → 低位桶（位置5）
hash2 = ...1 0101 (21), hash2 & 16 = 16 → 高位桶（位置5+16=21）
```

**核心原理**：扩容后容量翻倍，多了一位参与取模运算。新增的那一位恰好对应 `n` 的那一位，所以用 `hash & n` 就能判断该节点在新表中是低位还是高位。

#### 6.2.3 lastRun 优化

```java
// 原始链表：A → B → C → D → E → F → G
// hash & n:  0    n    0    n    n    n    n

// lastRun = E（从E开始后续都是高位n）
// 只需要为 A, B, C, D 新建节点
// E → F → G 整个子链直接引用，无需新建

// 低位链: D → B → A → null (头插法，顺序反转)
// 高位链: E → F → G (直接引用lastRun子链)
```

**优势**：减少了新建节点的数量，但注意：
- lastRun 之前的节点用头插法，顺序与原来相反
- lastRun 之后的子链保持原顺序
- 这不影响正确性，因为查找是遍历整个链表

#### 6.2.4 红黑树迁移与退化

红黑树迁移时，通过 `TreeBin.first` 链表遍历（红黑树内部维护了一个双向链表），将节点分为高低两组：

- 如果某组的节点数 ≤ `UNTREEIFY_THRESHOLD(6)`，退化为链表
- 如果另一组还有节点，则重新构建红黑树
- 如果另一组为空，直接复用原来的 TreeBin

#### 6.2.5 扩容完成的判断

```java
if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
        return; // 不是最后一个线程
    finishing = advance = true;
    i = n; // 最后一个线程再做一轮全表检查
}
```

- 每个线程完成自己的任务后，CAS 将 `sizeCtl - 1`
- `sc - 2` 等于 `resizeStamp(n) << 16` 时，说明这是最后一个线程（因为初始值为 `rs << 16 + 2`，每次减1，减到 `rs << 16 + 1` 时只剩一个线程）
- 最后一个线程需要 `i = n` 再走一轮，检查是否所有桶都已迁移完成

---

## 7. helpTransfer() 方法分析

### 7.1 完整源码

```java
final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
    Node<K,V>[] nextTab; int sc;
    // 【1】前置检查：tab不为null，f是ForwardingNode，新表存在
    if (tab != null && (f instanceof ForwardingNode) &&
        (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) {
        // 【2】计算扩容戳
        int rs = resizeStamp(tab.length);
        // 【3】循环：只要还在扩容，就尝试帮忙
        while (nextTab == nextTable && table == tab &&
               (sc = sizeCtl) < 0) {
            // 【4】以下情况不参与扩容（与addCount中判断相同）：
            //   - 扩容戳不匹配
            //   - 扩容已结束（sc == rs + 1）
            //   - 扩容线程数已达上限
            //   - 新表已被替换
            //   - 所有任务已分配完
            if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                sc == rs + MAX_RESIZERS || transferIndex <= 0)
                break;
            // 【5】CAS将sizeCtl+1，加入扩容
            if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {
                transfer(tab, nextTab); // 参与扩容
                break;
            }
        }
        return nextTab;
    }
    return table;
}
```

### 7.2 关键点

1. **发现扩容就帮忙**：`put` 线程发现桶头是 `ForwardingNode` 时，不是等待，而是加入扩容
2. **多重检查**：确保扩容仍在进行中才帮忙，避免重复加入
3. **CAS +1**：将 `sizeCtl` 加1表示多了一个扩容线程
4. **最多 `MAX_RESIZERS` 个线程**：`MAX_RESIZERS = (1 << 16) - 1 = 65535`

### 7.3 什么时候触发帮助扩容？

- `putVal` 中发现桶头 `hash == MOVED`
- `addCount` 中发现元素数量超过阈值
- 两种情况都会调用 `helpTransfer` 或直接调用 `transfer`

---

## 8. get() 方法源码分析

### 8.1 完整源码

```java
public V get(Object key) {
    Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
    // 【1】计算hash
    int h = spread(key.hashCode());
    // 【2】表不为null，桶不为空
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) {
        // 【3】检查桶头节点
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                return e.val; // 找到，直接返回
        }
        // 【4】hash < 0：特殊节点
        else if (eh < 0)
            // ForwardingNode: 去nextTable找
            // TreeBin: 用find方法在红黑树中查找
            return (p = e.find(h, key)) != null ? p.val : null;
        // 【5】遍历链表
        while ((e = e.next) != null) {
            if (e.hash == h &&
                ((ek = e.key) == key || (ek != null && key.equals(ek))))
                return e.val;
        }
    }
    return null;
}
```

### 8.2 为什么 get 不需要加锁？

`get` 操作全程无锁，靠 **volatile** 保证可见性：

1. **`table` 是 volatile 的**：保证读到最新的数组引用
2. **`tabAt` 使用 volatile 语义读取**：保证读到最新的桶头节点
3. **`Node.val` 是 volatile 的**：保证读到最新的值
4. **`Node.next` 是 volatile 的**：保证读到最新的链表结构

关键机制：

```
写线程(put)                     读线程(get)
┌──────────────────┐          ┌──────────────────┐
│ synchronized(f)  │          │ tabAt(tab, i)    │
│   修改val/next   │  ──→     │ 读val/next      │
│ 退出synchronized │  happens │ volatile保证可见  │
│  写volatile语义  │  -before │ 读volatile语义   │
└──────────────────┘          └──────────────────┘
```

**happens-before 保证**：
- `synchronized` 释放锁 happens-before `volatile` 读
- 所以写线程在 `synchronized` 块中的修改，对读线程的 `volatile` 读可见

### 8.3 遇到 ForwardingNode 的处理

```java
// ForwardingNode.find()
Node<K,V> find(int h, Object k) {
    // 到nextTable中去查找
    outer: for (Node<K,V>[] tab = nextTable;;) {
        Node<K,V> e; int n;
        if (k == null || tab == null || (n = tab.length) == 0 ||
            (e = tabAt(tab, (n - 1) & h)) == null)
            return null;
        for (;;) {
            int eh; K ek;
            if ((eh = e.hash) == h &&
                ((ek = e.key) == k || (ek != null && k.equals(ek))))
                return e;
            if (eh < 0) {
                if (e instanceof ForwardingNode) {
                    tab = ((ForwardingNode<K,V>)e).nextTable;
                    continue outer; // 继续到下一个表找
                }
                else
                    return e.find(h, k); // TreeBin
            }
            if ((e = e.next) == null)
                return null;
        }
    }
}
```

- 扩容期间 `get` 不会被阻塞
- 如果目标桶已迁移，`ForwardingNode` 会转发到新表继续查找
- 这就是"**扩容不阻塞读**"的核心实现

### 8.4 遇到 TreeBin 的处理

```java
// TreeBin.find()
final Node<K,V> find(int h, Object k) {
    // ... 省略部分代码
    for (Node<K,V> e = first; e != null; ) {
        int s; K ek;
        // 如果有写锁或等待写锁，用链表遍历（保守策略）
        if (((s = lockState) & (WAITER|WRITER)) != 0) {
            if (e.hash == h &&
                ((ek = e.key) == k || (ek != null && k.equals(ek))))
                return e;
            e = e.next;
        }
        // 否则尝试加读锁，用红黑树查找（更快）
        else if (U.compareAndSwapInt(this, LOCKSTATE, s, s + READER)) {
            TreeNode<K,V> r, p;
            try {
                p = ((r = root) != null ? r.findTreeNode(h, k, null) : null);
            } finally {
                Thread w;
                // 如果当前是最后一个读线程，且有写线程在等待，唤醒它
                if (U.getAndAddInt(this, LOCKSTATE, -READER) == READER &&
                    (w = waiter) != null)
                    LockSupport.unpark(w);
            }
            return p;
        }
    }
    return null;
}
```

- **有写锁时**：退化为链表遍历（因为红黑树正在调整，结构不稳定）
- **无写锁时**：CAS 加读锁，用红黑树查找，更高效

---

## 9. size() / mappingCount() 计数方式

### 9.1 sumCount()

```java
final long sumCount() {
    CounterCell[] as = counterCells; CounterCell a;
    long sum = baseCount;
    if (as != null) {
        for (int i = 0; i < as.length; ++i) {
            if ((a = as[i]) != null)
                sum += a.value;
        }
    }
    return sum;
}
```

- 累加 `baseCount` 和所有 `CounterCell` 的值
- **注意**：这个操作不是原子性的，遍历期间可能有并发修改
- 所以结果是一个**近似值**

### 9.2 size()

```java
public int size() {
    long n = sumCount();
    return ((n < 0L) ? 0 :
            (n > (long)Integer.MAX_VALUE) ? Integer.MAX_VALUE :
            (int)n);
}
```

- 返回 `int` 类型，最大 `Integer.MAX_VALUE`
- 溢出时截断

### 9.3 mappingCount()

```java
public long mappingCount() {
    long n = sumCount();
    return (n < 0L) ? 0L : n;
}
```

- 返回 `long` 类型，更精确
- 官方推荐使用此方法代替 `size()`

### 9.4 为什么是弱一致性？

```
时间线：
t1: 线程A put(k1,v1) → baseCount +1
t2: 线程B 调用size() → 读取baseCount=1
t3: 线程A put(k2,v2) → CounterCell[3] +1
t4: 线程B 继续遍历CounterCell → 读取CounterCell[3]=1
t5: 线程B 返回 sum = 2 ✓

但如果在t2和t4之间有其他线程删除了元素：
t2.5: 线程C remove(k1) → baseCount -1
t4: 线程B 读CounterCell[3]=1
t5: sum = baseCount(t2时读的1) + CounterCell[3] = 2 ≠ 实际的1
```

所以 `size()` 和 `mappingCount()` 返回的是**近似值**，这在并发容器中是可接受的。

---

## 10. treeifyBin() 树化条件

### 10.1 完整源码

```java
private final void treeifyBin(Node<K,V>[] tab, int index) {
    Node<K,V> b; int n, sc;
    if (tab != null) {
        // 【1】如果数组长度 < MIN_TREEIFY_CAPACITY(64)，优先扩容而非树化
        if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
            tryPresize(n << 1); // 扩容到2倍
        // 【2】桶不为空且是链表头节点
        else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {
            synchronized (b) { // 锁住桶头
                if (tabAt(tab, index) == b) { // 双重检查
                    TreeNode<K,V> hd = null, tl = null;
                    // 遍历链表，将Node转换为TreeNode
                    for (Node<K,V> e = b; e != null; e = e.next) {
                        TreeNode<K,V> p =
                            new TreeNode<K,V>(e.hash, e.key, e.val,
                                              null, null);
                        if ((p.prev = tl) == null)
                            hd = p; // 第一个节点作为头
                        else
                            tl.next = p;
                        tl = p;
                    }
                    // 用TreeBin包装，TreeBin内部会构建红黑树
                    setTabAt(tab, index, new TreeBin<K,V>(hd));
                }
            }
        }
    }
}
```

### 10.2 树化两个条件

链表转红黑树需要**同时满足两个条件**：

1. **链表长度 ≥ 8**（`TREEIFY_THRESHOLD`）—— 在 `putVal` 中通过 `binCount` 判断
2. **数组长度 ≥ 64**（`MIN_TREEIFY_CAPACITY`）—— 在 `treeifyBin` 中判断

如果链表长度 ≥ 8 但数组长度 < 64，**优先扩容**而非树化，因为：
- 扩容能将链表一分为二（高低链分离），有效缩短链表
- 扩容的成本低于维护红黑树
- 红黑树节点占用更多内存（TreeNode 比 Node 多4个引用）

### 10.3 为什么阈值是 8？—— 泊松分布

HashMap 的注释中给出了理论依据：

```
理想情况下，hash完全随机时，每个桶中元素个数服从泊松分布：

λ = 0.5（负载因子0.75 × 单桶期望0.5的修正值）

P(0) = 0.60653066
P(1) = 0.30326533
P(2) = 0.07581633
P(3) = 0.01263606
P(4) = 0.00157952
P(5) = 0.00015795
P(6) = 0.00001316
P(7) = 0.00000094
P(8) = 0.00000006  ← 链表长度达到8的概率仅为千万分之6
```

- 链表长度达到 8 的概率极低（约 0.00000006）
- 如果真的达到了，说明 hash 函数质量差或遭到攻击
- 此时转为红黑树（O(log n) 查找）作为保底策略

### 10.4 TreeBin 的读写锁机制

```java
static final class TreeBin<K,V> extends Node<K,V> {
    volatile TreeNode<K,V> root;
    volatile TreeNode<K,V> first;
    volatile Thread waiter;
    volatile int lockState;
    // lockState的位含义：
    static final int WRITER = 1;    // 001 - 持有写锁
    static final int WAITER = 2;    // 010 - 等待写锁
    static final int READER = 4;    // 100 - 持有读锁（每个读线程+4）
}
```

**写锁**（`putTreeVal` / `removeTreeNode`）：
```java
private final void lockRoot() {
    // 尝试CAS获取写锁
    if (!U.compareAndSwapInt(this, LOCKSTATE, 0, WRITER))
        contendedLock(); // 获取失败，进入竞争锁逻辑
}
```

**读锁**（`find`）：
```java
// 在TreeBin.find()中
if (U.compareAndSwapInt(this, LOCKSTATE, s, s + READER)) {
    // 获取读锁成功，用红黑树查找
}
```

**锁规则**：
- 读读不互斥（多个读线程可以同时持有读锁，lockState += READER）
- 读写互斥（有写锁时读退化为链表遍历，有读锁时写需等待）
- 写写互斥（只有一个线程能持有写锁）

---

## 11. 关键设计总结

### 11.1 锁粒度细化

| 操作 | Java 7 | Java 8 |
|------|--------|--------|
| 空桶插入 | 锁Segment | CAS（无锁） |
| 非空桶插入 | 锁Segment | synchronized锁桶头 |
| 读取 | 锁Segment | 无锁（volatile读） |
| 扩容 | 单线程 | 多线程协同 |

Java 8 的锁粒度从 Segment（多个桶）细化到单个桶，并发度从固定16提升到动态等于数组长度。

### 11.2 读不加锁

- `Node.val` 和 `Node.next` 都是 `volatile`
- `tabAt()` 使用 `Unsafe.getObjectVolatile` 保证可见性
- `synchronized` 写释放 happens-before `volatile` 读
- 红黑树读操作使用读锁（与写锁互斥，但读读不互斥）

### 11.3 扩容不阻塞读

- 扩容期间在已迁移的桶放置 `ForwardingNode`（hash=MOVED）
- 读线程遇到 `ForwardingNode` 自动转发到新表查找
- 写线程遇到 `ForwardingNode` 则帮助扩容

### 11.4 计数高性能

- 借鉴 `LongAdder` 分段计数：`baseCount` + `CounterCell[]`
- 无竞争时 CAS 更新 `baseCount`
- 有竞争时每个线程更新自己的 `CounterCell`
- 最终求和获得近似值（弱一致性）

### 11.5 多线程协同扩容

- 扩容不是单线程完成，而是多线程"分块迁移"
- 每个线程分配一个 `stride`（默认16）个桶
- 通过 `transferIndex` CAS 竞争分配任务区间
- `put` 线程发现扩容中的桶会调用 `helpTransfer` 帮忙
- 最后一个完成迁移的线程负责收尾（切换新表、设置新阈值）

### 11.6 设计哲学一览

```
┌─────────────────────────────────────────────────────────┐
│               ConcurrentHashMap 设计哲学                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. 减少锁竞争                                          │
│     - 空桶CAS → 非空桶synchronized → 细粒度锁           │
│                                                         │
│  2. 读写分离                                            │
│     - 读操作无锁（volatile）                             │
│     - 写操作按需加锁（synchronized桶头）                 │
│                                                         │
│  3. 扩容协同                                            │
│     - 多线程分块迁移，互相帮助                           │
│     - ForwardingNode 保证读不阻塞                        │
│                                                         │
│  4. 自适应数据结构                                       │
│     - 短链表 → 长链表 → 红黑树                          │
│     - 红黑树节点少时退化链表                              │
│                                                         │
│  5. 高性能计数                                          │
│     - LongAdder分散热点                                  │
│     - 弱一致性换性能                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 12. 常见面试问题

### Q1：ConcurrentHashMap 的 key 和 value 为什么不能为 null？

**源码证据：**

```java
if (key == null || value == null) throw new NullPointerException();
```

**原因：**

在并发场景下，`get(key)` 返回 `null` 有歧义：
- key 不存在 → 返回 null
- key 存在但 value 为 null → 也返回 null

在 HashMap（单线程）中，可以用 `containsKey()` 来区分这两种情况。但在 ConcurrentHashMap（多线程）中：

```java
// 线程A
if (!map.containsKey(key)) {   // t1: key不存在
    // t2: 线程B在这里put(key, null)
    V v = map.get(key);         // t3: 返回null
    // 无法区分：是key不存在，还是value就是null？
}
```

`containsKey` 和 `get` 之间不是原子操作，结果不可靠。因此直接禁止 null 值，从根源上消除歧义。

### Q2：ConcurrentHashMap 如何保证线程安全？

| 场景 | 保证方式 |
|------|---------|
| 空桶插入 | CAS（乐观锁，无阻塞） |
| 非空桶插入/更新 | synchronized 锁桶头（悲观锁） |
| 读取 | volatile 读（无锁） |
| 初始化 | CAS + sizeCtl 互斥 |
| 扩容 | CAS 分配任务 + synchronized 迁移桶 |
| 计数 | LongAdder 分段 CAS |

### Q3：扩容期间 put 操作怎么处理？

1. **桶未迁移**：正常 synchronized 锁桶头，插入数据到旧表
2. **桶已迁移**（头节点是 ForwardingNode）：调用 `helpTransfer` 帮助扩容，扩容完成后再在新表中插入
3. **桶正在迁移**（其他线程持有 synchronized）：自旋等待桶头节点释放锁，然后发现是 ForwardingNode，转到第2步

### Q4：ConcurrentHashMap 的 size() 是精确的吗？

**不是精确的**，是弱一致性的近似值。

`sumCount()` 遍历 `baseCount` 和 `CounterCell[]` 时没有加锁，遍历期间可能有并发修改，导致计数不精确。但在大多数场景下，这个近似值足够使用。

### Q5：为什么使用 synchronized 而不是 ReentrantLock？

1. **Java 8 以后 synchronized 有大幅优化**：偏向锁 → 轻量级锁 → 重量级锁的升级机制，在低竞争场景下性能接近 CAS
2. **内存开销更小**：每个 ReentrantLock 需要额外的 AQS 对象（约 24 字节），而 synchronized 使用对象头 Mark Word
3. **无需手动释放**：synchronized 自动释放锁，不会忘记 unlock
4. **JVM 层面优化**：锁升级、锁消除、锁粗化等都在 JVM 层面实现

### Q6：ConcurrentHashMap 的扩容是如何触发的？

1. `putVal` 插入新节点后调用 `addCount(1, binCount)`
2. `addCount` 中计算 `sumCount()`，如果 `s >= sizeCtl`（达到扩容阈值）
3. 第一个线程 CAS 将 `sizeCtl` 设为 `(resizeStamp(n) << 16) + 2`，发起扩容
4. 后续线程 CAS 将 `sizeCtl + 1`，加入扩容

此外，`treeifyBin` 中如果数组长度 < 64，也会触发扩容（`tryPresize`）。

### Q7：ForwardingNode 的作用是什么？

1. **占位标记**：表示该桶已迁移完成，旧表该位置不再使用
2. **读转发**：`get` 操作遇到 ForwardingNode 会自动到新表中查找
3. **写帮助**：`put` 操作遇到 ForwardingNode 会调用 `helpTransfer` 帮助扩容
4. **防止重复迁移**：迁移完的桶用 ForwardingNode 标记，其他线程不会再迁移

### Q8：ConcurrentHashMap 和 Hashtable 的区别？

| 维度 | Hashtable | ConcurrentHashMap |
|------|-----------|-------------------|
| 锁机制 | synchronized 方法级（锁整个表） | CAS + synchronized 桶级 |
| 并发度 | 1（同一时刻只有1个线程操作） | 等于桶数量 |
| null 键值 | 不允许 | 不允许 |
| 迭代器 | Enumeration（强一致性） | 弱一致性迭代器 |
| 性能 | 差（全表锁） | 好（细粒度锁） |
| 扩容 | 单线程 | 多线程协同 |

### Q9：多线程扩容时如何保证数据不丢失？

1. **synchronized 保证原子性**：迁移桶时锁住桶头，只有一个线程能迁移该桶
2. **CAS 分配任务**：`transferIndex` 通过 CAS 分配，保证每个桶只被一个线程迁移
3. **ForwardingNode 标记**：迁移完成后放置 ForwardingNode，防止重复迁移
4. **双重检查**：获取 synchronized 锁后再次检查桶头是否改变

### Q10：ConcurrentHashMap 的迭代器是弱一致性的，什么意思？

```java
ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
map.put("a", "1");
map.put("b", "2");

Iterator<String> it = map.keySet().iterator();
map.put("c", "3"); // 迭代器创建后插入的元素

while (it.hasNext()) {
    System.out.println(it.next()); // 可能输出 a, b，不会输出 c
}
```

- 弱一致性：迭代器遍历的是创建迭代器时或之后的某个时刻的数据快照
- 不保证能看到迭代器创建后的所有修改
- 但不会抛出 `ConcurrentModificationException`（不同于 HashMap 的 fail-fast）
- 这是一种**妥协**：用一致性换并发性能

### Q11：ConcurrentHashMap 如何计算扩容阈值？为什么不是直接用 capacity * loadFactor？

```java
// initTable中
sc = n - (n >>> 2);  // 等价于 n * 0.75

// transfer中
sizeCtl = (n << 1) - (n >>> 1); // 新表 = 2n，阈值 = 2n - 2n/4 = 2n * 0.75 = 1.5n
```

- 用位运算代替浮点乘法，效率更高
- 本质上就是 `capacity * 0.75`，只是用整数位运算实现

### Q12：为什么链表转红黑树的阈值是8，而红黑树退化链表的阈值是6？

设为不同值（8 和 6）是为了**避免频繁转换**：

- 如果都是 8：链表长度在 8 附近波动时，会反复在链表和红黑树之间切换
- 中间留一个缓冲区（6~8）：避免"抖动"现象
- 类似于 Redis 的 zset 在元素少时用 ziplist，多时用 skiplist，也是滞后转换

---

## 附录：核心方法调用关系图

```
put(k, v)
  └── putVal(k, v, false)
        ├── spread(key.hashCode())         // 哈希扰动
        ├── initTable()                    // 初始化表
        ├── tabAt()                        // volatile读桶头
        ├── casTabAt()                     // CAS插入空桶
        ├── helpTransfer()                 // 帮助扩容
        │     └── transfer()               // 参与扩容
        ├── synchronized(f)               // 锁桶头
        │     ├── 链表遍历/插入
        │     └── TreeBin.putTreeVal()     // 红黑树插入
        ├── treeifyBin()                   // 树化
        │     └── tryPresize()             // 扩容（数组<64时）
        │           └── transfer()
        └── addCount(1, binCount)          // 计数+扩容判断
              ├── fullAddCount()            // LongAdder累加
              └── transfer()               // 发起扩容

get(k)
  └── spread(key.hashCode())
        ├── tabAt()                        // volatile读
        ├── Node链表遍历
        ├── ForwardingNode.find()          // 转发到新表
        └── TreeBin.find()                 // 红黑树查找

size()
  └── sumCount()
        └── baseCount + Σ CounterCell[i]
```

---

## 附录：sizeCtl 状态转换图

```
                    ┌───────────┐
                    │  sizeCtl  │
                    └─────┬─────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
   sizeCtl > 0      sizeCtl = -1      sizeCtl < -1
   ┌──────────┐    ┌──────────┐    ┌──────────────────┐
   │ 初始:容量 │    │ 正在初始化 │    │ 正在扩容          │
   │ 之后:阈值 │    │          │    │ 高16位:扩容戳     │
   │ n*0.75   │    │          │    │ 低16位:线程数+1   │
   └──────────┘    └──────────┘    └──────────────────┘

状态转换:
  0(未初始化) → CAS→ -1(初始化中) → 初始化完成 → n*0.75(阈值)
  n*0.75(阈值) → 元素达到阈值 → CAS→ (rs<<16)+2(发起扩容)
  (rs<<16)+k(扩容中) → 每个线程完成 → CAS→ -1 → 最后一个线程 → 2n*0.75(新阈值)
```

---

## 附录：putVal 中四种分支的判断逻辑图

```
putVal 自旋
│
├── tab == null? ──YES──→ initTable() ──→ 继续自旋
│       │
│      NO
│       │
├── tab[i] == null? ──YES──→ casTabAt(i, new Node) ──→ 成功break / 失败继续
│       │
│      NO
│       │
├── f.hash == MOVED? ──YES──→ helpTransfer(tab, f) ──→ 继续自旋(新表)
│       │
│      NO
│       │
└── synchronized(f)
      ├── f.hash >= 0? ──YES──→ 遍历链表 → 更新/尾插
      │
      └── f instanceof TreeBin? ──YES──→ putTreeVal() → 更新/插入

→ binCount >= 8? ──YES──→ treeifyBin()
→ addCount(1, binCount)
```

---

## 附录：transfer 迁移单桶的流程

```
transfer - 迁移桶i
│
├── 桶为空? ──YES──→ casTabAt(i, fwd) ──→ advance=true
│
├── f.hash == MOVED? ──YES──→ 已迁移，advance=true
│
└── synchronized(f)
      ├── f.hash >= 0 (链表)
      │     ├── lastRun优化: 找末尾连续相同高位/低位的子链
      │     ├── 遍历到lastRun, 头插法构建低位链ln和高位链hn
      │     ├── nextTab[i] = ln (低位桶)
      │     ├── nextTab[i+n] = hn (高位桶)
      │     └── tab[i] = fwd (标记已迁移)
      │
      └── f instanceof TreeBin (红黑树)
            ├── 遍历first链表, 分离高低位TreeNode
            ├── 低位<=6? → untreeify(lo)退化链表 : new TreeBin(lo)
            ├── 高位<=6? → untreeify(hi)退化链表 : new TreeBin(hi)
            ├── nextTab[i] = ln
            ├── nextTab[i+n] = hn
            └── tab[i] = fwd
```

---

> **总结**：ConcurrentHashMap 是 Java 并发编程的巅峰之作，它通过 CAS + synchronized 的混合锁策略、volatile 保证的读无锁、多线程协同扩容、LongAdder 分段计数等精妙设计，在高并发场景下实现了近乎最优的性能。理解其源码不仅有助于面试，更能提升对并发编程的深层认知。
