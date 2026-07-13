# CopyOnWriteArrayList 深度解析

> 基于 JDK 21 源码，逐行注释，通俗易懂地讲透 CopyOnWriteArrayList 的每一个细节。

---

## 目录

1. [为什么需要 CopyOnWriteArrayList](#1-为什么需要-copyonwritearraylist)
2. [核心设计思想](#2-核心设计思想)
3. [核心字段源码](#3-核心字段源码)
4. [add() 方法源码逐行分析](#4-add-方法源码逐行分析)
5. [set() 方法源码分析](#5-set-方法源码分析)
6. [remove() 方法源码分析](#6-remove-方法源码分析)
7. [get() 方法源码分析](#7-get-方法源码分析)
8. [迭代器 COWIterator 源码分析](#8-迭代器-cowiterator-源码分析)
9. [CopyOnWriteArraySet 简析](#9-copyonwritearrayset-简析)
10. [性能分析与适用场景](#10-性能分析与适用场景)
11. [与其他并发 List 对比表](#11-与其他并发-list-对比表)
12. [常见面试问题](#12-常见面试问题)

---

## 1. 为什么需要 CopyOnWriteArrayList

### 1.1 ArrayList 的线程不安全

`ArrayList` 是我们日常开发中使用频率最高的集合之一，但它是**非线程安全**的。来看一个经典的并发修改问题：

```java
// 多线程同时往 ArrayList 中添加元素
List<String> list = new ArrayList<>();

// 线程1
new Thread(() -> {
    for (int i = 0; i < 1000; i++) {
        list.add("thread1-" + i);
    }
}).start();

// 线程2
new Thread(() -> {
    for (int i = 0; i < 1000; i++) {
        list.add("thread2-" + i);
    }
}).start();
```

运行上述代码，可能出现以下问题：

| 问题类型 | 原因 |
|---------|------|
| **数据丢失** | 两个线程同时执行 `elementData[size++] = e`，后写的覆盖先写的，且 size 只加了1 |
| **数组越界** | 两个线程同时读到 `size == 9`（容量为10），都认为还能放，其中一个就会越界 |
| **null 元素** | 线程A写了引用但还没执行 `size++`，线程B读到了未初始化的数组位置 |

深入 `ArrayList.add()` 源码，问题出在以下两步**不是原子操作**：

```java
// ArrayList.add() 的核心逻辑（简化）
elementData[size] = e;  // 第1步：在 size 位置放入元素
size++;                  // 第2步：size 加1
// 两个步骤之间可以被其他线程打断！
```

更致命的是 `ArrayList` 的扩容操作也不是线程安全的：

```java
// ArrayList.grow() 扩容逻辑（简化）
private Object[] grow() {
    int newCapacity = oldCapacity + (oldCapacity >> 1); // 1.5倍扩容
    return elementData = Arrays.copyOf(elementData, newCapacity);
    // 如果线程A正在扩容（复制到新数组），线程B还在往旧数组写，数据就丢了
}
```

### 1.2 Collections.synchronizedList 的缺陷

为了解决线程安全问题，JDK 提供了一个「装饰器」方案：

```java
List<String> syncList = Collections.synchronizedList(new ArrayList<>());
```

来看 `SynchronizedList` 的源码实现：

```java
// Collections.SynchronizedList 源码（JDK 21）
static class SynchronizedList<E> extends SynchronizedCollection<E>
        implements List<E> {

    final List<E> list; // 被装饰的真正 List

    SynchronizedList(List<E> list) {
        super(list);       // 把 mutex 设为 this（SynchronizedList对象自身）
        this.list = list;
    }

    SynchronizedList(List<E> list, Object mutex) {
        super(list, mutex); // 使用外部传入的锁对象
        this.list = list;
    }

    // ====== 所有方法都加了 synchronized ======

    public E get(int index) {
        synchronized (mutex) { return list.get(index); }
    }

    public E set(int index, E element) {
        synchronized (mutex) { return list.set(index, element); }
    }

    public void add(int index, E element) {
        synchronized (mutex) { list.add(index, element); }
    }

    public E remove(int index) {
        synchronized (mutex) { return list.remove(index); }
    }

    public int indexOf(Object o) {
        synchronized (mutex) { return list.indexOf(o); }
    }

    public int lastIndexOf(Object o) {
        synchronized (mutex) { return list.lastIndexOf(o); }
    }

    // ====== 关键缺陷：迭代器没有加锁！ ======
    public ListIterator<E> listIterator() {
        return list.listIterator(); // 返回的迭代器根本没有加锁！
    }

    public ListIterator<E> listIterator(int index) {
        return list.listIterator(index); // 同样没有加锁！
    }
}
```

**SynchronizedList 的三大缺陷：**

**缺陷一：粗粒度锁，读读互斥**

所有操作（包括 `get`、`indexOf` 等读操作）都争用同一把锁。10个线程同时读，也得排队串行执行，这完全没必要！

```
线程1: get(0)  ──┐
线程2: get(1)  ──┤  全部串行！
线程3: get(2)  ──┤  读和读也互斥！
线程4: add("x") ──┤  读写互斥
线程5: get(3)  ──┘
```

**缺陷二：迭代器不安全，抛 ConcurrentModificationException**

`SynchronizedList` 返回的迭代器**没有加锁**！如果遍历期间其他线程修改了列表，就会触发 fail-fast 机制，抛出 `ConcurrentModificationException`：

```java
List<String> list = Collections.synchronizedList(new ArrayList<>(Arrays.asList("a", "b", "c")));

// 线程1：遍历
for (String s : list) {
    System.out.println(s); // 可能抛 ConcurrentModificationException
}

// 线程2：同时修改
list.add("d"); // 修改了 modCount，导致线程1的迭代器检测到不一致
```

官方文档都明确提醒你了：

> It is imperative that the user manually synchronize on the returned list when iterating over it.

你必须这样写：

```java
List<String> list = Collections.synchronizedList(new ArrayList<>());
// 必须手动加锁遍历！
synchronized (list) {
    for (String s : list) {
        System.out.println(s);
    }
}
```

**缺陷三：复合操作不安全**

```java
// 虽然 get 和 add 各自线程安全，但组合起来就不安全了！
if (!syncList.contains("key")) {  // 步骤1：检查
    syncList.add("key");           // 步骤2：添加
}
// 在步骤1和步骤2之间，其他线程可能已经添加了 "key"
// 导致重复添加！
```

### 1.3 CopyOnWrite 思想的引入

面对上述问题，Doug Lea 大神给出了一个优雅的解决方案——**CopyOnWriteArrayList**。

核心思想非常简单：

> **写操作时，不直接修改原数组，而是复制一份新数组，在新数组上做修改，最后将引用指向新数组。读操作则直接读原数组，不需要加锁。**

这个思想来源于操作系统中的 COW（Copy-On-Write）机制：
- Linux 的 fork() 系统调用：父子进程共享物理内存页，只有当某一方修改时才复制页框
- Redis 的 RDB 持久化：fork 子进程访问的是那一刻的内存快照

```java
// 使用方式
List<String> cowList = new CopyOnWriteArrayList<>();
cowList.add("hello");  // 写：复制+修改
cowList.get(0);        // 读：无锁直接访问
```

---

## 2. 核心设计思想

### 2.1 写时复制（Copy-On-Write）

写时复制是整个类的灵魂。用图来理解：

```
写操作 add("D") 的过程：

步骤1: 原始状态
  array ──→ [A, B, C]    (长度3)

步骤2: 复制新数组
  array ──→ [A, B, C]    (旧数组，读线程还在访问)
  newArray: [A, B, C, null]  (新数组，长度4)

步骤3: 在新数组上写入
  array ──→ [A, B, C]    (旧数组不变)
  newArray: [A, B, C, D]  (新数组写入完成)

步骤4: 替换引用
  array ──→ [A, B, C, D]  (指向新数组)
  旧数组 [A, B, C]        (没有引用了，等待GC)
```

关键点：**在整个写操作过程中，读线程始终访问的是旧数组，不会被阻塞，也不会看到中间状态。**

### 2.2 读写分离

```
                    ┌──────────────────┐
   读线程1 ─────────┤                  │
   读线程2 ─────────┤   共享的 array   │──── 读取的是同一个数组
   读线程3 ─────────┤                  │     不需要加锁
                    └──────────────────┘
                           ↑
                    setArray() 原子切换
                           ↑
                    ┌──────────────────┐
   写线程 ──────────┤   新 array       │──── 加锁，独占修改
                    └──────────────────┘
```

读写分离的本质：
- **读操作**：访问 `array` 引用指向的数组，不加锁，多线程并发读完全自由
- **写操作**：加锁后，复制新数组，在新数组上修改，然后通过 `setArray()` 原子切换引用

这种设计使得读操作的性能达到最优——没有锁竞争、没有 CAS 重试，就是一次简单的数组访问。

### 2.3 最终一致性

CopyOnWriteArrayList 不保证**强一致性**，而是保证**最终一致性**。

```java
// 线程A：读
// 线程B：add("new")

// 时间线：
// t1: 线程B加锁，开始复制新数组
// t2: 线程A读array，读到的是旧数组（还没有"new"）  ← 弱一致性
// t3: 线程B在新数组上add("new")
// t4: 线程B执行setArray(newArray)
// t5: 线程A再读array，读到新数组（有"new"了）      ← 最终一致性
```

所谓「最终一致性」：
- 写操作完成**之后**的读操作，一定能看到最新的值
- 写操作进行**之中**的读操作，可能看到旧值
- 这和数据库的 `READ COMMITTED` 隔离级别类似

这种语义在很多场景下是完全可以接受的，甚至比强一致性更优（因为不需要读操作加锁等待）。

### 2.4 三大设计原则总结

| 设计原则 | 含义 | 带来的好处 | 代价 |
|---------|------|-----------|------|
| 写时复制 | 写操作不修改原数组，复制一份再改 | 读写不冲突，读无锁 | 写操作内存开销大 |
| 读写分离 | 读走旧数组，写走新数组 | 读性能极致 | 数据暂时不一致 |
| 最终一致性 | 不保证实时一致，保证最终一致 | 读不阻塞 | 迭代器是快照 |

---

## 3. 核心字段源码

### 3.1 JDK 21 完整类签名与字段

```java
// ====== JDK 21 CopyOnWriteArrayList 源码 ======

public class CopyOnWriteArrayList<E>
    implements List<E>, RandomAccess, Cloneable, java.io.Serializable
{
    private static final long serialVersionUID = 8673264195747942595L;
```

先看实现的接口：
- `List<E>`：标准 List 接口
- `RandomAccess`：标记接口，表示支持高效随机访问（底层数组，O(1)）
- `Cloneable`：支持克隆
- `Serializable`：支持序列化

### 3.2 锁对象（JDK 21 使用 synchronized）

```java
    /**
     * 保护所有修改操作的锁。JDK 21 中使用 synchronized(this) 替代了
     * 早期版本中的 final ReentrantLock lock = new ReentrantLock()。
     *
     * 历史变迁：
     * - JDK 5 ~ JDK 19: 使用 "final ReentrantLock lock = new ReentrantLock()"
     * - JDK 20+: 改为 synchronized(this)，因为：
     *   (1) 不需要 ReentrantLock 的 Condition 功能
     *   (2) 不需要 tryLock() 等高级功能
     *   (3) synchronized 在新版本 JVM 上性能已经很好（偏向锁→轻量级锁→重量级锁的升级）
     *   (4) 减少一个对象的开销
     */
    // JDK 5~19 的写法：
    // final transient ReentrantLock lock = new ReentrantLock();

    // JDK 20+ 的写法：直接使用 synchronized(this)
```

**为什么从 ReentrantLock 改为 synchronized？**

这是一个很重要的设计变化。Doug Lea 在 JDK-825 conditioner 中做了这个改动，原因如下：

| 对比项 | ReentrantLock | synchronized |
|--------|--------------|--------------|
| 公平性 | 支持公平锁 | 仅非公平 |
| Condition | 支持多个条件变量 | 仅一个 wait set |
| tryLock | 支持 | 不支持 |
| 可中断 | lockInterruptibly() | 不支持 |
| JVM 优化 | 无 | 偏向锁→轻量级锁→重量级锁自适应 |
| 对象开销 | 额外一个锁对象 | 无额外对象 |

CopyOnWriteArrayList 只需要最简单的互斥功能，不需要公平锁、不需要 Condition、不需要 tryLock，所以 synchronized 完全够用，而且在现代 JVM 上性能也很好。

### 3.3 volatile 数组

```java
    /**
     * The array, accessed only via getArray/setArray.
     * 底层存储数组，通过 getArray/setArray 访问。
     *
     * 关键修饰符：volatile
     * - 保证一个线程修改了 array 引用后，其他线程立即可见
     * - 配合写时复制：写线程 setArray(新数组) 后，读线程能立刻看到新数组
     *
     * 注意：volatile 修饰的是 array 引用本身，不是数组元素！
     * - array = newArray  ← 对引用的修改，volatile 保证可见性
     * - array[i] = xxx    ← 对数组元素的修改，volatile 不保证！
     *                        （但 COW 永远不会修改旧数组元素，只替换引用）
     */
    private transient volatile Object[] array;

    /** 获取 array 引用 */
    final Object[] getArray() {
        return array;
    }

    /** 设置 array 引用 —— 这是整个类的关键原子操作！ */
    final void setArray(Object[] a) {
        array = a;
    }
```

**volatile 在这里的精确语义：**

```
写线程:                              读线程:
1. 复制新数组 newArray                |
2. 在 newArray 上修改                 |  读到 array → 旧数组
3. setArray(newArray)                 |   ← volatile 写
4. ---- 内存屏障 ----                 |
                                      |  读到 array → 新数组
                                      |   ← volatile 读保证看到最新引用
```

**为什么 getArray/setArray 不直接访问字段？**

封装访问有以下几个好处：
1. **代码清晰**：所有对 array 的访问都通过方法，方便搜索和审计
2. **便于子类扩展**：子类可以覆盖 getArray/setArray 实现自定义逻辑
3. **便于添加断言**：可以在方法中添加 invariant 检查
4. **序列化控制**：array 是 transient，通过方法访问可以统一处理

### 3.4 字段总结

```java
// CopyOnWriteArrayList 的全部字段（JDK 21）

private static final long serialVersionUID = 8673264195747942595L; // 序列化版本号
private transient volatile Object[] array;                       // 核心存储

// 就这两个！极其简洁的设计
// JDK 20+ 连 ReentrantLock lock 字段都省了，改用 synchronized(this)
```

---

## 4. add() 方法源码逐行分析

### 4.1 add(E e) —— 尾部添加

这是最核心的方法，必须逐行吃透：

```java
    /**
     * Appends the specified element to the end of this list.
     * 将指定元素追加到列表末尾。
     *
     * @param e element to be appended to this list
     * @return {@code true} (as specified by {@link Collection#add})
     */
    public boolean add(E e) {
        // JDK 20+ 使用 synchronized(this) 替代 lock.lock() / lock.unlock()
        // synchronized 自动保证：即使发生异常，也会释放锁
        synchronized (this) {
            // ====== 第1步：获取当前数组 ======
            // 此时 array 可能被其他写线程替换，但我们已经加锁，
            // 其他写线程会被阻塞在 synchronized (this) 外面
            Object[] es = getArray();         // es = 旧数组引用
            int len = es.length;             // len = 旧数组长度

            // ====== 第2步：复制新数组 ======
            // Arrays.copyOf 会创建一个长度为 len+1 的新数组，
            // 并将旧数组的所有元素复制到新数组中
            // 新数组的最后一个位置是 null（默认值）
            es = Arrays.copyOf(es, len + 1);  // 新数组：长度+1

            // ====== 第3步：在新数组上写入元素 ======
            // 在新数组的最后一个位置放入新元素
            es[len] = e;                      // len 恰好是新数组的最后一个下标

            // ====== 第4步：原子替换引用 ======
            // setArray 底层就是 array = a;
            // 因为 array 是 volatile 的，所以这一步会：
            //   (1) 写入内存屏障之前的所有修改都对其他线程可见
            //   (2) 所有读线程在 setArray 之后都能看到新数组
            setArray(es);                     // 指向新数组

            // ====== 第5步：自动释放锁 ======
            // synchronized 代码块正常退出时自动释放锁
            return true;
        }
    }
```

**执行时序图：**

```
时间轴 ──────────────────────────────────────────────────────────→

写线程: ┃加锁┃getArray()┃copyOf(新数组)┃es[len]=e┃setArray(新)┃释放锁┃
         ↑                                              ↑
         │                                              │
读线程1: ──────get() 读旧数组──────────────────────────────get() 读新数组──→
         │         (看不到新元素)                         │(看到新元素)
         │                                              │
读线程2: ──────────────────────get() 读旧数组───────────────────────────→
                                   (也看不到新元素，因为在setArray之前)
```

### 4.2 add(int index, E element) —— 指定位置添加

```java
    /**
     * Inserts the specified element at the specified position in this list.
     * Shifts the element currently at that position (if any) and any
     * subsequent elements to the right (adds one to their indices).
     *
     * 在指定位置插入元素，后面的元素右移。
     */
    public void add(int index, E element) {
        synchronized (this) {
            // ====== 第1步：获取当前数组及其长度 ======
            Object[] es = getArray();
            int len = es.length;

            // ====== 第2步：边界检查 ======
            // index 可以等于 len，表示在末尾追加
            // index 不能小于0，也不能大于 len
            if (index > len || index < 0)
                throw new IndexOutOfBoundsException(outOfBounds(index, len));

            // ====== 第3步：创建新数组并复制元素 ======
            Object[] newElements;
            int numMoved = len - index; // 需要右移的元素个数

            if (numMoved == 0) {
                // 插入位置恰好是末尾，等同于 add(E e)
                newElements = Arrays.copyOf(es, len + 1);
            } else {
                // 插入位置在中间，需要分段复制
                newElements = new Object[len + 1]; // 创建新数组

                // 复制 index 之前的部分（不移动）
                System.arraycopy(es, 0, newElements, 0, index);

                // 复制 index 及之后的部分（整体右移1位）
                System.arraycopy(es, index, newElements, index + 1, numMoved);
            }

            // ====== 第4步：在 index 位置写入新元素 ======
            newElements[index] = element;

            // ====== 第5步：原子替换引用 ======
            setArray(newElements);
        }
        // 退出 synchronized 自动释放锁
    }
```

**中间插入的数组复制过程图解：**

```
原数组 es = [A, B, C, D, E]，要在 index=2 处插入 X

numMoved = 5 - 2 = 3 (C, D, E 需要右移)

新数组 newElements = new Object[6]

第一步：System.arraycopy(es, 0, newElements, 0, 2)
  复制 index 之前的部分
  newElements = [A, B, null, null, null, null]

第二步：System.arraycopy(es, 2, newElements, 3, 3)
  复制 index 及之后的部分（整体右移1位）
  newElements = [A, B, null, C, D, E]

第三步：newElements[2] = X
  在 index 位置写入新元素
  newElements = [A, B, X, C, D, E]

第四步：setArray(newElements)
  原子切换引用
```

### 4.3 addIfAbsent(E e) —— 不存在时添加

```java
    /**
     * Appends the element, if not present.
     * 如果元素不存在则追加（不会重复添加）。
     *
     * 这个方法体现了 "检查+添加" 的原子性保证。
     */
    public boolean addIfAbsent(E e) {
        Object[] snapshot = getArray(); // 先不加锁取一个快照
        // 在快照中查找，如果已存在直接返回 false
        return indexOfRange(e, snapshot, 0, snapshot.length) >= 0
            ? false
            : addIfAbsent(e, snapshot); // 不存在，进入加锁流程
    }

    /**
     * 加锁版本的不存在则添加。
     * 注意：即使在快照阶段判断不存在，加锁后还要再检查一次！
     * 因为在 getArray() 和加锁之间，其他线程可能已经添加了相同元素。
     */
    private boolean addIfAbsent(E e, Object[] snapshot) {
        synchronized (this) {
            // ====== 再次检查：获取最新数组 ======
            Object[] current = getArray();

            // ====== 一致性检查：如果在获取快照后数组被修改了 ======
            if (snapshot != current) {
                // 数组被其他线程改过了，需要在最新数组中重新检查
                // 优化：如果长度变了，只需检查新增部分
                int common = Math.min(snapshot.length, current.length);
                for (int i = 0; i < common; i++) {
                    if (current[i] != snapshot[i]  // 引用不等表示该位置被改过
                        && eq(e, current[i]))      // 新值恰好和要添加的元素相等
                        return false;              // 已经被其他线程添加了
                }
                // 如果新数组更长，检查新增的部分
                if (current.length != snapshot.length) {
                    for (int i = common; i < current.length; i++) {
                        if (eq(e, current[i]))
                            return false;
                    }
                }
            }
            // ====== 确认不存在，执行添加 ======
            Object[] es = Arrays.copyOf(current, current.length + 1);
            es[current.length] = e;
            setArray(es);
            return true;
        }
    }
```

**addIfAbsent 的双重检查模式是亮点：**
1. 先不加锁读快照，在快照中查找——**乐观路径**（快速失败）
2. 如果快照中不存在，加锁后再检查一次最新数组——**悲观路径**（确保正确）

这种模式兼顾了性能和正确性：大多数情况下元素已存在，不需要加锁；少数情况下元素不存在，加锁保证原子性。

### 4.4 addAll 及 addAllAbsent

```java
    /**
     * Appends all of the elements in the specified collection to the end
     * of this list, in the order that they are returned by the specified
     * collection's iterator.
     */
    public boolean addAll(Collection<? extends E> c) {
        // 将集合转为数组
        Object[] cs = (c.getClass() == CopyOnWriteArrayList.class)
            ? ((CopyOnWriteArrayList<?>) c).getArray()  // 优化：同为COW直接取数组
            : c.toArray();                               // 否则调用toArray()

        // 空集合，直接返回
        if (cs.length == 0)
            return false;

        synchronized (this) {
            Object[] es = getArray();
            int len = es.length;

            // 特殊情况：当前列表为空且传入的就是一个 Object[]
            // 可以直接使用传入的数组，不需要再复制
            if (len == 0 && cs.getClass() == Object[].class) {
                setArray(cs);
            } else {
                // 一般情况：合并两个数组
                Object[] newElements = Arrays.copyOf(es, len + cs.length);
                System.arraycopy(cs, 0, newElements, len, cs.length);
                setArray(newElements);
            }
        }
        return true;
    }

    /**
     * Appends all of the elements in the specified collection that
     * are not already contained in this list, to the end of this list,
     * in the order that they are returned by the specified collection's iterator.
     *
     * 批量去重添加：只添加当前列表中不存在的元素。
     * 这是 CopyOnWriteArrayList 独有的方法，普通 List 没有。
     */
    public int addAllAbsent(Collection<? extends E> c) {
        Object[] cs = c.toArray();
        if (cs.length == 0)
            return 0;

        synchronized (this) {
            Object[] es = getArray();
            int len = es.length;
            int added = 0; // 实际新增的元素个数

            // 逐个检查 cs 中的元素是否已存在
            for (int i = 0; i < cs.length; i++) {
                // 在当前数组 es 中查找 cs[i]
                if (indexOfRange(cs[i], es, 0, len) < 0 &&
                    // 还要在已经添加的元素中查找（防止 cs 本身有重复）
                    (cs[i] == null
                        ? indexOfRange(null, cs, 0, added) < 0
                        : indexOfRange(cs[i], cs, 0, added) < 0)) {
                    cs[added++] = cs[i]; // 记录真正需要添加的元素
                }
            }

            if (added > 0) {
                Object[] newElements = Arrays.copyOf(es, len + added);
                System.arraycopy(cs, 0, newElements, len, added);
                setArray(newElements);
            }
            return added;
        }
    }
```

---

## 5. set() 方法源码分析

### 5.1 完整源码

```java
    /**
     * Replaces the element at the specified position in this list with the
     * specified element.
     *
     * 替换指定位置的元素。
     */
    public E set(int index, E element) {
        synchronized (this) {
            // ====== 第1步：获取当前数组 ======
            Object[] es = getArray();
            int len = es.length;

            // ====== 第2步：边界检查 ======
            // 和 add 不同，set 要求 index 必须在 [0, len) 范围内
            // 不能等于 len，因为 set 是替换已有元素，不是新增
            if (index >= len)
                throw new IndexOutOfBoundsException(outOfBounds(index, len));

            // ====== 第3步：获取旧值 ======
            Object oldValue = es[index];

            // ====== 第4步：判断是否需要替换 ======
            // 如果新旧值相同（用 equals 判断），也需要进入替换流程吗？
            if (oldValue != element) {
                // 值不同，必须替换
                es = Arrays.copyOf(es, len);
                es[index] = element;
            }
            // ====== 关键！即使值相同，也要执行 setArray！ ======
            // 注意：这里没有 else 分支直接 return！
            // 而是统一走到 setArray

            setArray(es); // 即使没有修改，也要设置！
            return (E) oldValue;
        }
    }
```

### 5.2 为什么相同值也要 setArray？

这是 CopyOnWriteArrayList 中一个**极其重要且容易被忽略的细节**！

源码中的关键逻辑：

```java
if (oldValue != element) {
    es = Arrays.copyOf(es, len);  // 值不同，复制新数组
    es[index] = element;          // 修改新数组
}
// 不管值相不相同，都执行 setArray！
setArray(es);
```

**为什么不直接 `return oldValue`？原因有三：**

**原因一：维持 volatile 的写语义（最核心原因）**

`set()` 方法在语义上是一个**写操作**。在 Java 内存模型（JMM）中，volatile 写操作会建立 happens-before 关系：

```
线程A: set(index, element)  ──happens-before──→  线程B: get(index)
```

如果值相同时不执行 `setArray()`，就**不会有 volatile 写**，也就不会建立 happens-before 关系。这意味着线程 A 在 `set()` 之前的所有写操作，不一定对线程 B 可见。

```java
// 假设 set 在值相同时不执行 setArray：
volatile int x = 0;
CopyOnWriteArrayList<Integer> list = new CopyOnWriteArrayList<>();
list.add(1);

// 线程A
x = 42;                    // 普通写
list.set(0, 1);            // set 相同值，如果不 setArray 就没有 volatile 写

// 线程B
int y = list.get(0);       // 如果没有 volatile 读，x=42 可能不可见！
int z = x;                 // z 可能是 0，而不是 42！
```

有了 `setArray()`，即使值相同，也能保证 happens-before 关系：

```java
// 线程A
x = 42;                    // 普通写
list.set(0, 1);            // setArray() 产生 volatile 写 → 内存屏障
                            // 之前的所有写对其他线程可见

// 线程B
int y = list.get(0);       // getArray() 产生 volatile 读 → 内存屏障
int z = x;                 // z 一定是 42！
```

**原因二：确保迭代器语义一致**

迭代器持有的是创建时刻的数组快照。如果 `set()` 不调用 `setArray()`，数组引用不变，后续创建的迭代器还是看到旧数组——这可能不是用户期望的。

**原因三：简化代码逻辑**

即使值相同，执行一次 `setArray(es)` 的开销也非常小——只是将一个 volatile 引用赋值为它当前的值。现代 JVM 对这种情况有优化。这个代价远比引入额外的条件分支和可能出现的内存可见性 bug 要小得多。

### 5.3 set 方法的内存语义总结

```
set() 方法的完整内存语义：

1. 进入 synchronized (this)    ← 获取锁，建立 happens-before
2. 读取 array                   ← volatile 读
3. 复制新数组（如果需要修改）     ← 普通读写
4. setArray(新/旧数组)          ← volatile 写，建立 happens-before
5. 退出 synchronized            ← 释放锁

第4步的 volatile 写保证了：
- 第2~3步的所有修改对其他线程可见
- 之前所有线程的修改也对其他线程可见（锁的传递性）
```

---

## 6. remove() 方法源码分析

### 6.1 remove(int index) —— 按索引删除

```java
    /**
     * Removes the element at the specified position in this list.
     * Shifts any subsequent elements to the left (subtracts one from their
     * indices). Returns the element that was removed from the list.
     *
     * 删除指定位置的元素，后面的元素左移。
     */
    public E remove(int index) {
        synchronized (this) {
            // ====== 第1步：获取当前数组 ======
            Object[] es = getArray();
            int len = es.length;

            // ====== 第2步：获取旧值并边界检查 ======
            E oldValue = (E) es[index]; // 如果 index 越界，这里会抛 ArrayIndexOutOfBoundsException
            // 注意：add 方法是手动检查边界，remove 依赖数组访问自动抛异常

            // ====== 第3步：计算需要移动的元素个数 ======
            int numMoved = len - index - 1; // index 之后的元素个数

            if (numMoved == 0) {
                // 删除的是最后一个元素，不需要移动
                // 直接复制一个长度减1的新数组
                setArray(Arrays.copyOf(es, len - 1));
            } else {
                // 删除的是中间元素，需要左移
                Object[] newElements = new Object[len - 1]; // 新数组长度 -1

                // 复制 index 之前的部分
                System.arraycopy(es, 0, newElements, 0, index);

                // 复制 index 之后的部分（整体左移1位）
                System.arraycopy(es, index + 1, newElements, index, numMoved);

                setArray(newElements);
            }

            return oldValue;
        }
    }
```

**中间删除的数组复制过程图解：**

```
原数组 es = [A, B, C, D, E]，要删除 index=2 处的 C

numMoved = 5 - 2 - 1 = 2 (D, E 需要左移)

新数组 newElements = new Object[4]

第一步：System.arraycopy(es, 0, newElements, 0, 2)
  复制 index 之前的部分
  newElements = [A, B, null, null]

第二步：System.arraycopy(es, 3, newElements, 2, 2)
  复制 index+1 及之后的部分（整体左移1位）
  newElements = [A, B, D, E]

第三步：setArray(newElements)
  原子切换引用

结果：C 被删除了
```

### 6.2 remove(Object o) —— 按元素删除

```java
    /**
     * Removes the first occurrence of the specified element from this list,
     * if it is present. If this list does not contain the element, it is
     * unchanged.
     *
     * 删除第一次出现的指定元素。
     */
    public boolean remove(Object o) {
        Object[] snapshot = getArray(); // 不加锁获取快照
        int index = indexOfRange(o, snapshot, 0, snapshot.length); // 在快照中查找

        // 快照中不存在，直接返回 false
        return index >= 0 && remove(o, snapshot, index);
    }

    /**
     * 加锁版本的删除。
     * 和 addIfAbsent 类似，加锁后需要重新检查。
     */
    private boolean remove(Object o, Object[] snapshot, int index) {
        synchronized (this) {
            // ====== 再次获取最新数组 ======
            Object[] current = getArray();

            // ====== 一致性检查 ======
            if (snapshot != current) {
                // 数组被修改过了，需要重新定位
                // 优化：在变化的部分中查找
                int prefix = Math.min(index, current.length);
                for (int i = 0; i < prefix; i++) {
                    // 在 index 之前的位置中，如果某个位置的元素变了，
                    // 且新值恰好等于要删除的元素
                    if (current[i] != snapshot[i]
                        && eq(o, current[i]))
                        index = i; // 更新 index
                        break;
                }
                }
                // 检查 index 之后是否还有匹配
                // （因为可能在获取快照后、加锁前，其他线程在 index 之前
                //   删除了一些元素，导致原来 index 处的元素左移了）
                int suffix = Math.min(current.length, snapshot.length);
                for (int i = prefix; i < suffix; i++) {
                    if (current[i] != snapshot[i] && eq(o, current[i])) {
                        index = i;
                        break;
                    }
                }
                // 如果 current 更长，检查新增部分
                if (current.length > snapshot.length) {
                    for (int i = suffix; i < current.length; i++) {
                        if (eq(o, current[i])) {
                            index = i;
                            break;
                        }
                    }
                }
                // 如果 current 更短，可能在快照之后被删掉了
                if (index >= current.length) {
                    return false; // 元素已经不存在了
                }
                // 用最新的 current 重新确认
                Object old = current[index];
                if (!eq(o, old)) {
                    return false; // index 处已经不是要删的元素了
                }
            }

            // ====== 确认存在，执行删除 ======
            Object[] newElements = new Object[current.length - 1];
            System.arraycopy(current, 0, newElements, 0, index);
            System.arraycopy(current, index + 1, newElements, index,
                             current.length - index - 1);
            setArray(newElements);
            return true;
        }
    }
```

### 6.3 removeRange —— 范围删除

```java
    /**
     * Removes from this list all of the elements whose index is between
     * fromIndex, inclusive, and toIndex, exclusive.
     */
    void removeRange(int fromIndex, int toIndex) {
        synchronized (this) {
            Object[] es = getArray();
            int len = es.length;

            if (fromIndex < 0 || toIndex > len || fromIndex > toIndex)
                throw new IndexOutOfBoundsException();

            int newlen = len - (toIndex - fromIndex); // 新长度
            int numMoved = len - toIndex;              // 需要左移的元素个数

            if (numMoved == 0) {
                // 删除的是末尾一段
                setArray(Arrays.copyOf(es, newlen));
            } else {
                Object[] newElements = new Object[newlen];
                System.arraycopy(es, 0, newElements, 0, fromIndex);
                System.arraycopy(es, toIndex, newElements, fromIndex, numMoved);
                setArray(newElements);
            }
        }
    }
```

### 6.4 批量删除 removeAll 和 retainAll

```java
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return bulkRemove(e -> !c.contains(e));
    }

    // JDK 21 引入的批量删除模板方法（使用函数式接口）
    private boolean bulkRemove(Predicate<? super E> filter) {
        synchronized (this) {
            Object[] es = getArray();
            int len = es.length;
            int[] survivor = new int[len]; // 记录每个位置是否保留
            int numSurvivors = 0;

            for (int i = 0; i < len; i++) {
                @SuppressWarnings("unchecked")
                E e = (E) es[i];
                if (!filter.test(e)) {
                    // 不满足删除条件，保留
                    survivor[numSurvivors++] = i;
                }
            }

            if (numSurvivors == len) {
                // 没有任何元素被删除
                setArray(es); // 仍然调用 setArray！和 set() 方法同理
                return false;
            }

            // 构建新数组，只保留幸存的元素
            Object[] newElements = new Object[numSurvivors];
            for (int i = 0; i < numSurvivors; i++) {
                newElements[i] = es[survivor[i]];
            }
            setArray(newElements);
            return true;
        }
    }
```

---

## 7. get() 方法源码分析

### 7.1 为什么 get() 不需要加锁？

```java
    /**
     * Returns the element at the specified position in this list.
     *
     * 获取指定位置的元素。注意：这个方法完全无锁！
     */
    public E get(int index) {
        return elementAt(getArray(), index);
    }

    // JDK 21 引入的静态工具方法
    // 这个方法在 java.util.Objects 中定义
    static <E> E elementAt(Object[] a, int index) {
        return (E) a[index];
    }
```

就这么简单！`get()` 方法的执行过程只有两步：
1. `getArray()`：读取 volatile 的 `array` 引用
2. `a[index]`：通过数组下标访问元素

**为什么这两个步骤是无锁安全的？**

**步骤1的安全性：volatile 读取保证可见性**

```java
private transient volatile Object[] array;
```

- `array` 是 volatile 的，所以 `getArray()` 一定能读到最新的引用
- 写线程通过 `setArray(newArray)` 修改引用后，读线程立刻能感知到
- volatile 读的语义：读到的值一定是某个线程完整写入的值，不会是半写状态

**步骤2的安全性：数组引用的不可变性**

这是更深层的原因。关键在于：**一旦一个数组对象被赋值给 `array`，它的内容就再也不会被修改！**

```java
// 写操作的流程（以 add 为例）：
Object[] es = getArray();          // 1. 获取旧数组
es = Arrays.copyOf(es, len + 1);   // 2. 创建新数组（es现在指向新数组）
es[len] = e;                       // 3. 修改新数组
setArray(es);                      // 4. 将 array 引用指向新数组

// 注意：旧数组从未被修改！
// 所以读线程读到的旧数组，其内容始终是完整的、一致的
```

整个过程可以用下图理解：

```
时刻1: array → [A, B, C]       旧数组（永远不会被修改）
       读线程1: getArray() → [A, B, C] → get(0) = A ✓

时刻2: 写线程开始 add("D")
       array → [A, B, C]       旧数组不变
       读线程2: getArray() → [A, B, C] → get(0) = A ✓

时刻3: 写线程复制新数组
       array → [A, B, C]       旧数组不变
       新数组:  [A, B, C, D]    还没被 array 引用
       读线程3: getArray() → [A, B, C] → get(0) = A ✓

时刻4: 写线程执行 setArray(新数组)
       array → [A, B, C, D]    新数组
       旧数组:  [A, B, C]       等待GC
       读线程4: getArray() → [A, B, C, D] → get(0) = A ✓

// 在任何时刻，读线程读到的数组都是完整的、一致的
```

### 7.2 get() 的弱一致性

`get()` 虽然无锁安全，但它是**弱一致性**的：

```java
// 场景：读线程在写线程 setArray 之前读取了旧数组引用
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A");

// 线程1: 读取 array 引用
Object[] arr = list.getArray(); // arr → [A]

// 线程2: 同时执行 add("B")
// ... 加锁 → 复制 → [A, B] → setArray → 解锁
// 此时 list.getArray() → [A, B]

// 但线程1手里还拿着旧引用 arr
arr[0]; // A ✓（旧数组的内容是完整的）
// 但看不到 "B"

// 这就是弱一致性：读到了写操作开始之前的快照
```

这不是 bug，而是设计权衡。在大多数读多写少的场景中，短暂的数据不一致是完全可接受的。

### 7.3 get() 与 ArrayList.get() 的对比

```java
// ArrayList.get()
public E get(int index) {
    Objects.checkIndex(index, size);  // 检查边界
    return elementData(index);        // 访问数组
    // 没有任何同步措施，多线程下可能读到正在被修改的数组
    // 可能读到中间状态！
}

// CopyOnWriteArrayList.get()
public E get(int index) {
    return elementAt(getArray(), index); // 读 volatile 引用 + 访问数组
    // 虽然没有锁，但读到的数组一定是某个一致性的快照
    // 因为写操作永远不修改旧数组
}
```

### 7.4 其他读操作

```java
    // 获取元素数量 —— 无锁
    public int size() {
        return getArray().length;
    }

    // 判空 —— 无锁
    public boolean isEmpty() {
        return getArray().length == 0;
    }

    // 包含判断 —— 无锁
    public boolean contains(Object o) {
        Object[] es = getArray();
        return indexOfRange(o, es, 0, es.length) >= 0;
    }

    // 查找索引 —— 无锁
    public int indexOf(Object o) {
        Object[] es = getArray();
        return indexOfRange(o, es, 0, es.length);
    }

    // 最后一次出现的索引 —— 无锁
    public int lastIndexOf(Object o) {
        Object[] es = getArray();
        return lastIndexOfRange(o, es, 0, es.length);
    }

    // 内部查找方法
    private static int indexOfRange(Object o, Object[] es, int from, int to) {
        if (o == null) {
            for (int i = from; i < to; i++)
                if (es[i] == null)
                    return i;
        } else {
            for (int i = from; i < to; i++)
                if (o.equals(es[i]))
                    return i;
        }
        return -1;
    }

    // 判断相等 —— 无锁
    // 注意：这个方法在遍历期间不加锁，但遍历的是快照，所以安全
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof List)) return false;

        // 获取快照
        Object[] es = getArray();
        // ... 遍历 es 进行比较
        // 即使比较过程中有写操作，es 指向的旧数组也不会变
    }

    // hashCode —— 无锁
    public int hashCode() {
        Object[] es = getArray();
        // ... 基于 es 计算哈希值
    }

    // toString —— 无锁
    public String toString() {
        Object[] es = getArray();
        // ... 基于 es 生成字符串
    }

    // toArray —— 无锁
    public Object[] toArray() {
        Object[] es = getArray();
        return Arrays.copyOf(es, es.length); // 复制一份返回，防止外部修改内部数组
    }

    // toArray(T[] a) —— 无锁
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        Object[] es = getArray();
        int len = es.length;
        if (a.length < len)
            return (T[]) Arrays.copyOf(es, len, a.getClass()); // 创建新数组
        else {
            System.arraycopy(es, 0, a, 0, len);
            if (a.length > len)
                a[len] = null; // 按照规范，紧跟在末尾的元素设为 null
            return a;
        }
    }
```

---

## 8. 迭代器 COWIterator 源码分析

### 8.1 迭代器的创建

```java
    /**
     * Returns an iterator over the elements in this list in proper sequence.
     *
     * The returned iterator provides a snapshot of the state of the list
     * at the time the iterator was constructed. No synchronization is
     * needed while traversing the iterator.
     *
     * 返回的迭代器是列表创建时刻的快照，遍历时不需要同步。
     */
    public Iterator<E> iterator() {
        return new COWIterator<E>(getArray(), 0);
    }

    /**
     * Returns a ListIterator over the elements in this list
     * (from the first to the last element).
     */
    public ListIterator<E> listIterator() {
        return new COWIterator<E>(getArray(), 0);
    }

    /**
     * Returns a ListIterator over the elements in this list
     * (from the specified position).
     */
    public ListIterator<E> listIterator(int index) {
        Object[] es = getArray();
        int len = es.length;
        if (index < 0 || index > len)
            throw new IndexOutOfBoundsException(outOfBounds(index, len));
        return new COWIterator<E>(es, index);
    }
```

注意：迭代器在创建时，将**当前数组的引用**传入 COWIterator。这个引用在迭代器生命周期内不会改变——这就是**快照语义**。

### 8.2 COWIterator 完整源码

```java
    /**
     * A snapshot-style iterator for CopyOnWriteArrayList.
     * CopyOnWriteArrayList 的快照风格迭代器。
     *
     * 这个迭代器在创建时就"冻结"了列表的状态，
     * 遍历期间不会反映后续的修改操作。
     *
     * 注意：这个迭代器是 "static" 的内部类，不持有外部类的引用！
     * 这是为了避免迭代器阻止外部类被 GC。
     */
    private static class COWIterator<E> implements ListIterator<E> {
        /**
         * Snapshot of the array.
         * 创建迭代器时的数组快照。
         * 这个引用在整个迭代过程中不会改变。
         */
        private final Object[] snapshot;

        /**
         * Index of element to be returned by subsequent call to next.
         * 游标，下一个要返回的元素的下标。
         */
        private int cursor;

        /**
         * 构造方法：传入快照数组和起始位置
         */
        COWIterator(Object[] es, int initialCursor) {
            snapshot = es;       // 保存快照
            cursor = initialCursor; // 设置起始游标
        }

        // ====== 判断是否还有下一个/上一个元素 ======

        public boolean hasNext() {
            return cursor < snapshot.length;
        }

        public boolean hasPrevious() {
            return cursor > 0;
        }

        // ====== 获取下一个/上一个元素 ======

        @SuppressWarnings("unchecked")
        public E next() {
            if (!hasNext())
                throw new NoSuchElementException();
            return (E) snapshot[cursor++]; // 返回当前元素，游标后移
        }

        @SuppressWarnings("unchecked")
        public E previous() {
            if (!hasPrevious())
                throw new NoSuchElementException();
            return (E) snapshot[--cursor]; // 游标前移，返回元素
        }

        // ====== 获取下一个/上一个元素的索引 ======

        public int nextIndex() {
            return cursor;
        }

        public int previousIndex() {
            return cursor - 1;
        }

        // ====== 以下方法全部抛 UnsupportedOperationException ======

        /**
         * Not supported. Always throws UnsupportedOperationException.
         *
         * 不支持！CopyOnWriteArrayList 的迭代器是只读的！
         *
         * 为什么不支持？
         * 1. 迭代器遍历的是快照，修改快照没有意义（不会反映到原列表）
         * 2. 如果要修改，应该直接调用列表的 add/set/remove 方法
         * 3. 避免语义混乱：在快照上修改会让人误解为修改了原列表
         */
        public void remove() {
            throw new UnsupportedOperationException();
        }

        public void set(E e) {
            throw new UnsupportedOperationException();
        }

        public void add(E e) {
            throw new UnsupportedOperationException();
        }

        // ====== JDK 21 新增：forEachRemaining ======

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            Object[] es = snapshot;
            int i = cursor;
            for (; i < es.length; i++) {
                @SuppressWarnings("unchecked")
                E e = (E) es[i];
                action.accept(e);
            }
            cursor = i;
        }
    }
```

### 8.3 快照语义详解

**什么是快照语义？**

迭代器在创建时「拍了一张照片」，记录了列表在那个时刻的所有元素。之后无论列表怎么修改，迭代器看到的始终是那张「照片」上的内容。

```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A");
list.add("B");
list.add("C");

// 创建迭代器（拍快照）
Iterator<String> it = list.iterator(); // 快照: [A, B, C]

// 此时修改列表
list.add("D");      // 列表变成 [A, B, C, D]
list.set(1, "BBB"); // 列表变成 [A, BBB, C, D]
list.remove(0);     // 列表变成 [BBB, C, D]

// 但迭代器看到的还是快照
while (it.hasNext()) {
    System.out.println(it.next()); // 输出: A B C（不是 BBB C D）
}
```

**快照语义的内存图解：**

```
创建迭代器之前：
  list.array → [A, B, C]

创建迭代器：
  COWIterator:
    snapshot → [A, B, C]   ← snapshot 和 list.array 指向同一个数组！
    cursor = 0

执行 list.add("D"):
  写线程: 加锁 → 复制新数组 [A, B, C, D] → setArray → 解锁
  list.array → [A, B, C, D]   ← list.array 指向新数组了
  旧数组 [A, B, C]             ← 但 snapshot 还指向旧数组！旧数组不会被GC
                                  因为迭代器还持有引用

迭代器遍历：
  snapshot[0] = A   ← 还是旧数组的值
  snapshot[1] = B
  snapshot[2] = C
```

**关键理解：创建迭代器时并没有复制数组！**

```java
COWIterator(Object[] es, int initialCursor) {
    snapshot = es;  // 只是引用赋值，没有复制数组！
}
```

`snapshot` 和创建时刻的 `array` 指向**同一个数组对象**。只有当写操作发生时，才会创建新数组，此时 `array` 指向新数组，而 `snapshot` 仍然指向旧数组。这就是 COW 的精妙之处——**延迟复制，按需复制**。

### 8.4 不抛 ConcurrentModificationException（CME）

这是和 `ArrayList` 迭代器最大的区别之一。

**ArrayList 的迭代器会抛 CME：**

```java
// ArrayList 的迭代器内部有一个 expectedModCount
// 每次操作前检查 modCount == expectedModCount
// 如果不等，说明列表被其他线程修改了，抛出 CME

final void checkForComodification() {
    if (modCount != expectedModCount)
        throw new ConcurrentModificationException();
}
```

**CopyOnWriteArrayList 的迭代器不需要 CME 检查：**

因为迭代器遍历的是快照，快照永远不会被修改，所以根本不存在「并发修改」的可能。不需要 `modCount`，不需要 `expectedModCount`，不需要检查——简单就是美。

```java
// COWIterator 的 next() 方法
public E next() {
    if (!hasNext())
        throw new NoSuchElementException();
    return (E) snapshot[cursor++];
    // 没有 checkForComodification()！
    // 因为 snapshot 是不可变的，永远一致
}
```

### 8.5 迭代器不支持修改操作

COWIterator 的 `remove()`、`set()`、`add()` 方法都抛出 `UnsupportedOperationException`。

这看起来很不方便，但这是合理的设计选择：

**为什么不支持？**

1. **语义不一致**：迭代器遍历的是快照，如果允许在快照上修改，修改不会反映到原列表，这会让人困惑
2. **线程安全问题**：如果要支持修改，就需要加锁，这违背了「迭代不加锁」的设计初衷
3. **替代方案简单**：需要修改时直接调用列表的 `add/set/remove` 方法即可

```java
// 正确的遍历+修改方式
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A");
list.add("B");
list.add("C");

// 遍历
for (String s : list) {
    System.out.println(s);
    // 如果需要修改，直接操作列表
    // 注意：修改后迭代器看不到变化！
    // list.add("D"); // 这不会影响当前迭代
}

// 删除某个元素，不要用迭代器的 remove()
for (String s : list) {
    if ("B".equals(s)) {
        list.remove(s); // 直接调用列表的 remove
        break;          // 删除后建议 break，因为迭代器的快照已过时
    }
}
```

### 8.6 迭代器的弱一致性总结

```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A"); list.add("B");

Iterator<String> it1 = list.iterator(); // 快照: [A, B]

list.add("C");                          // 列表: [A, B, C]

Iterator<String> it2 = list.iterator(); // 快照: [A, B, C]

list.remove("A");                       // 列表: [B, C]

Iterator<String> it3 = list.iterator(); // 快照: [B, C]

// it1 遍历: A, B        ← 创建时刻的快照
// it2 遍历: A, B, C    ← 创建时刻的快照
// it3 遍历: B, C        ← 创建时刻的快照
// 三个迭代器看到的视图各不相同，但每个都是自洽的
```

---

## 9. CopyOnWriteArraySet 简析

### 9.1 本质：CopyOnWriteArrayList 的包装

```java
public class CopyOnWriteArraySet<E> extends AbstractSet<E>
        implements java.io.Serializable {
    private static final long serialVersionUID = 5457747651344034263L;

    /**
     * The underlying CopyOnWriteArrayList.
     * 底层完全委托给 CopyOnWriteArrayList 实现！
     */
    private final CopyOnWriteArrayList<E> al;

    /**
     * Creates an empty set.
     */
    public CopyOnWriteArraySet() {
        al = new CopyOnWriteArrayList<E>();
    }
```

**核心特点：底层就是 CopyOnWriteArrayList，所有操作都委托给它。**

### 9.2 add() —— 去重添加

```java
    /**
     * Adds the specified element to this set if it is not already present.
     * More formally, adds the specified element e to this set if the set
     * contains no such element e2 such that Objects.equals(e, e2).
     *
     * 利用 CopyOnWriteArrayList.addIfAbsent() 实现去重。
     */
    public boolean add(E e) {
        return al.addIfAbsent(e); // 关键：调用 addIfAbsent 而不是 add
    }

    /**
     * Adds all of the elements in the specified collection to this set
     * if they're not already present.
     *
     * 利用 CopyOnWriteArrayList.addAllAbsent() 实现批量去重添加。
     */
    public boolean addAll(Collection<? extends E> c) {
        return al.addAllAbsent(c) > 0;
    }
```

### 9.3 其他操作 —— 全部委托

```java
    // 查询操作
    public int size()                    { return al.size(); }
    public boolean isEmpty()             { return al.isEmpty(); }
    public boolean contains(Object o)    { return al.contains(o); }
    public Object[] toArray()            { return al.toArray(); }
    public <T> T[] toArray(T[] a)        { return al.toArray(a); }
    public Iterator<E> iterator()        { return al.iterator(); }

    // 修改操作
    public boolean remove(Object o)      { return al.remove(o); }
    public void clear()                 { al.clear(); }

    // containsAll —— 无锁遍历
    public boolean containsAll(Collection<?> c) {
        Object[] es = al.getArray();
        for (Object e : c)
            if (indexOfRange(e, es, 0, es.length) < 0)
                return false;
        return true;
    }
```

### 9.4 性能陷阱：contains 是 O(n)

```java
// CopyOnWriteArraySet 的 contains 是 O(n) 的！
// 因为底层是数组，需要线性查找
set.contains("key"); // 最坏情况遍历整个数组

// 对比 HashSet 的 contains 是 O(1)
// HashSet 底层是 HashMap，哈希查找
```

**因此，CopyOnWriteArraySet 只适合元素数量很少的场景（通常几十个以内），比如：**
- 事件监听器列表
- 观察者列表
- 小型配置集合

### 9.5 与 HashSet 对比

| 特性 | CopyOnWriteArraySet | HashSet |
|------|-------------------|---------|
| 线程安全 | 是 | 否 |
| 底层结构 | CopyOnWriteArrayList | HashMap |
| add | O(n)（addIfAbsent 需要遍历检查） | O(1)（哈希查找） |
| contains | O(n)（线性查找） | O(1)（哈希查找） |
| 迭代安全性 | 安全（快照） | 不安全（CME） |
| 内存开销 | 写时复制数组 | HashMap 的桶数组+链表/红黑树 |
| null 支持 | 支持 | 支持 |
| 适用场景 | 小集合、读多写少 | 通用、高性能 |

---

## 10. 性能分析与适用场景

### 10.1 写操作的性能开销

CopyOnWriteArrayList 的写操作需要复制整个数组，时间复杂度和空间复杂度都是 **O(n)**。

```java
// 每次 add 都要复制整个数组
es = Arrays.copyOf(es, len + 1); // 复制 n 个元素 + 分配新数组

// 如果列表有 10000 个元素，add 一次就要复制 10000 个元素！
// 而且还要分配一个长度为 10001 的新数组
// 而普通 ArrayList 的 add 是 O(1) 均摊
```

**具体的内存开销计算：**

```
假设列表有 n 个元素：
- 每次 add 复制 n 个引用：每个引用 8 bytes（64位 JVM 压缩指针）
- 新数组分配：(n+1) × 8 bytes
- 总内存开销（短暂）：2n × 8 bytes ≈ 16n bytes
- GC 压力：旧数组变成垃圾，等待 GC 回收

举例：n = 10000
- 每次写操作的内存开销：约 160KB
- 如果 1 秒内写 100 次：16MB/s 的垃圾产生
- 这还不算 GC 的停顿开销！
```

**写操作的 GC 压力：**

```
频繁写操作导致的内存变化：

时间 t0: [A, B, C, D, E]              ← 初始数组
时间 t1: [A, B, C, D, E, F]           ← add("F")，旧数组变为垃圾
时间 t2: [A, B, C, D, E, F, G]        ← add("G")，又一个旧数组变垃圾
时间 t3: [A, B, C, D, E, F, G, H]     ← add("H")，再来一个垃圾
...

如果每秒写 1000 次，列表长度 1000：
- 每秒产生 1000 个长度约 1000 的数组对象
- 每秒产生约 8MB 垃圾
- Young GC 频率显著增加
- 可能导致 STW 停顿
```

### 10.2 读操作的性能

读操作是最优的——无锁、无 CAS、无 volatile 写屏障，只有一次 volatile 读 + 一次数组访问。

```java
// get() 的性能分析
public E get(int index) {
    return elementAt(getArray(), index);
    // getArray(): 一次 volatile 读（在 x86 上几乎无开销，x86 有强内存模型）
    // elementAt: 一次数组下标访问
    // 总计：约 2-5 ns（纳秒）
}

// 对比 SynchronizedList.get()
public E get(int index) {
    synchronized (mutex) { return list.get(index); }
    // synchronized 获取锁：约 20-100 ns（偏向锁）/ 100-500 ns（轻量级锁）
    // list.get(index)：数组访问
    // synchronized 释放锁
    // 总计：约 50-500 ns
}

// CopyOnWriteArrayList.get() 比 SynchronizedList.get() 快 10-100 倍！
```

### 10.3 适用场景

**场景一：事件监听器列表（最经典的场景）**

```java
// GUI 框架中的事件监听器
public class Button {
    // 监听器列表：几乎只读（注册/注销很少），频繁触发（点击事件很多）
    private final CopyOnWriteArrayList<ActionListener> listeners =
        new CopyOnWriteArrayList<>();

    public void addActionListener(ActionListener l) {
        listeners.add(l);    // 写操作：很少发生
    }

    public void removeActionListener(ActionListener l) {
        listeners.remove(l); // 写操作：很少发生
    }

    public void fireActionPerformed(ActionEvent e) {
        // 读操作：每次点击都触发，非常频繁
        for (ActionListener l : listeners) {
            l.actionPerformed(e);
        }
        // 不需要加锁！遍历的是快照，即使遍历过程中有监听器被移除也安全
    }
}
```

**场景二：配置缓存**

```java
// 系统配置管理
public class ConfigManager {
    // 配置信息：启动时加载，运行时偶尔修改，大量读取
    private final CopyOnWriteArrayList<String> allowedIps =
        new CopyOnWriteArrayList<>();

    // 管理后台修改配置（低频）
    public void addAllowedIp(String ip) {
        allowedIps.add(ip);
    }

    // 管理后台删除配置（低频）
    public void removeAllowedIp(String ip) {
        allowedIps.remove(ip);
    }

    // 每次请求都检查（高频）
    public boolean isIpAllowed(String ip) {
        return allowedIps.contains(ip);
    }
}
```

**场景三：黑白名单**

```java
// 黑名单过滤
public class BlacklistFilter {
    private final CopyOnWriteArrayList<String> blacklist =
        new CopyOnWriteArrayList<>();

    // 管理员更新黑名单（低频）
    public void addToBlacklist(String userId) {
        blacklist.addIfAbsent(userId);
    }

    public void removeFromBlacklist(String userId) {
        blacklist.remove(userId);
    }

    // 每个请求都要检查（极高频）
    public boolean isBlacklisted(String userId) {
        return blacklist.contains(userId);
    }
}
```

**场景四：观察者模式**

```java
// 观察者模式中的观察者列表
public class EventPublisher {
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers =
        new CopyOnWriteArrayList<>();

    // 订阅/取消订阅（低频）
    public void subscribe(Consumer<Event> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<Event> subscriber) {
        subscribers.remove(subscriber);
    }

    // 发布事件（高频）
    public void publish(Event event) {
        for (Consumer<Event> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }
}
```

### 10.4 不适用场景

**场景一：写多读少**

```java
// 反例：用 CopyOnWriteArrayList 做消息队列
CopyOnWriteArrayList<Message> queue = new CopyOnWriteArrayList<>();

// 每秒 10000 条消息入队
for (int i = 0; i < 10000; i++) {
    queue.add(new Message()); // 每次都要复制整个数组！
}
// 如果队列有 10000 条消息，add 一次就要复制 10000 个引用
// 性能极差，GC 压力极大
// 应该用 ConcurrentLinkedQueue
```

**场景二：大数据量**

```java
// 反例：用 CopyOnWriteArrayList 存储百万级数据
CopyOnWriteArrayList<String> bigList = new CopyOnWriteArrayList<>();
for (int i = 0; i < 1_000_000; i++) {
    bigList.add("item-" + i); // 每次复制百万个引用！
}
// 第 100 万次 add 时，需要复制 999999 个引用 + 分配 8000000 bytes 新数组
// 完全不可接受
```

**场景三：需要强一致性**

```java
// 反例：用 CopyOnWriteArrayList 做分布式锁的持有者列表
CopyOnWriteArrayList<String> lockHolders = new CopyOnWriteArrayList<>();

// 线程A检查没有人持有锁
if (lockHolders.isEmpty()) {       // 读：看到为空
    // ---- 这里可能被其他线程打断 ----
    // 线程B也检查没有人持有锁
    // 线程B也看到为空（因为读的是旧快照）
    // 线程A和线程B都认为自己获得了锁！
    lockHolders.add("threadA");     // 写
}

// 应该使用分布式锁框架，而不是 CopyOnWriteArrayList
```

### 10.5 性能优化建议

**优化一：批量写入**

```java
// 不好：多次单个添加
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
for (String item : items) {
    list.add(item); // 每次都复制数组！
}

// 好：一次性批量添加
list.addAll(items); // 只复制一次数组！
```

**优化二：使用 CopyOnWriteArraySet 去重**

```java
// 需要去重时，直接用 CopyOnWriteArraySet
CopyOnWriteArraySet<String> set = new CopyOnWriteArraySet<>();
set.add("key");         // 内部调用 addIfAbsent
set.add("key");         // 第二次不会真正添加，但仍然会加锁检查

// 如果元素已存在，addIfAbsent 的开销是：
// 1. 加锁
// 2. 遍历数组查找（O(n)）
// 3. 发现存在，解锁返回 false
// 注意：即使不修改，也获取了锁，这是不可避免的
```

**优化三：合理设置初始容量**

```java
// 虽然没有构造函数设置初始容量，但可以预填充
List<String> initial = Arrays.asList("a", "b", "c");
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>(initial);
// 这样后续的读操作就不需要经历多次数组复制扩容
```

---

## 11. 与其他并发 List 对比表

### 11.1 全面对比

| 特性 | ArrayList | Vector | Collections.synchronizedList | CopyOnWriteArrayList |
|------|-----------|--------|----------------------------|---------------------|
| **线程安全** | ❌ 否 | ✅ 是 | ✅ 是 | ✅ 是 |
| **锁机制** | 无 | synchronized | synchronized | synchronized (JDK20+) |
| **锁粒度** | 无 | 方法级 | 方法级 | 写方法级，读无锁 |
| **读性能** | 最快 | 最慢（锁竞争） | 较慢（锁竞争） | **最快（无锁）** |
| **写性能** | 最快 | 较慢 | 较慢 | **最慢（复制数组）** |
| **迭代安全性** | ❌ CME | ❌ CME | ❌ CME（需手动加锁） | ✅ 快照安全 |
| **迭代器支持修改** | ✅ 是 | ✅ 是 | ✅ 是（需手动加锁） | ❌ 否（抛 UOE） |
| **内存开销** | 最小 | 最小 | 最小 | **较大（写时复制）** |
| **一致性模型** | 强一致 | 强一致 | 强一致 | **最终一致** |
| **null 支持** | ✅ 是 | ✅ 是 | ✅ 是 | ✅ 是 |
| **RandomAccess** | ✅ 是 | ✅ 是 | 视底层List | ✅ 是 |

### 11.2 读性能对比（近似值，n=1000）

```
操作                | ArrayList | SynchronizedList | CopyOnWriteArrayList
--------------------|-----------|-------------------|---------------------
get(index)          |   2 ns    |     100 ns        |     3 ns
contains(o)         |  500 ns   |     600 ns        |    500 ns
iterator() 遍历      |  800 ns   |    1200 ns*       |    800 ns
iteration + 并发修改  |  CME!     |     CME!*         |   安全✓
* 需要手动加锁才能安全
```

### 11.3 写性能对比（近似值，n=1000）

```
操作                | ArrayList | SynchronizedList | CopyOnWriteArrayList
--------------------|-----------|-------------------|---------------------
add(e)              |   5 ns    |     120 ns        |   5000 ns
add(index, e)       |  200 ns   |     400 ns        |   5000 ns
remove(index)       |  200 ns   |     400 ns        |   5000 ns
set(index, e)       |   3 ns    |     110 ns        |   5000 ns
```

**结论：CopyOnWriteArrayList 的读性能接近 ArrayList（无锁优势），但写性能差距巨大（O(n) 复制开销）。**

### 11.4 选型决策树

```
需要线程安全的 List？
├── 否 → ArrayList
└── 是
    ├── 读多写少？
    │   ├── 是 → CopyOnWriteArrayList
    │   └── 否
    │       ├── 写多读少 → ConcurrentLinkedQueue（如果不是必须用List）
    │       └── 读写均衡 → Collections.synchronizedList
    └── 需要强一致性？
        ├── 是 → Collections.synchronizedList
        └── 否 → CopyOnWriteArrayList
```

---

## 12. 常见面试问题

### Q1：CopyOnWriteArrayList 是如何保证线程安全的？

**答：** CopyOnWriteArrayList 通过以下机制保证线程安全：

1. **写操作加锁**：使用 `synchronized(this)` 保证同一时刻只有一个线程能执行写操作
2. **写时复制**：写操作不修改原数组，而是复制一份新数组在新数组上修改
3. **volatile 引用**：`array` 字段用 `volatile` 修饰，保证 `setArray()` 后其他线程立即可见
4. **读操作无锁**：读操作直接访问 `array`，因为 `array` 指向的数组一旦发布就不会被修改

核心保证：读线程读到的数组一定是某个一致性的快照，不会是中间状态。

---

### Q2：CopyOnWriteArrayList 的迭代器为什么不会抛 ConcurrentModificationException？

**答：** 因为 CopyOnWriteArrayList 的迭代器（COWIterator）在创建时持有的是数组快照的引用，而写操作永远不会修改旧数组（只会创建新数组并替换引用）。所以迭代器遍历的数组内容在整个迭代过程中是固定不变的，不存在「并发修改」的可能，自然也不需要 `modCount` 检查和 CME。

---

### Q3：CopyOnWriteArrayList 的缺点是什么？

**答：**

1. **内存开销大**：每次写操作都要复制整个数组，如果数组很大，内存开销和 GC 压力都很大
2. **写性能差**：add/remove/set 的时间复杂度是 O(n)，而 ArrayList 是 O(1) 均摊
3. **数据弱一致性**：读操作可能读到旧数据，不适合需要强一致性的场景
4. **迭代器只读**：迭代器不支持 remove/set/add 操作
5. **不适合大数据量**：数组越大，写操作复制的时间越长

---

### Q4：为什么 CopyOnWriteArrayList 的 set 方法在值相同时也要调用 setArray？

**答：** 主要原因是维持 volatile 的写语义。`set()` 方法在语义上是一个写操作，如果值相同时不执行 `setArray()`，就不会产生 volatile 写，也就不会建立 happens-before 关系。这会导致在 `set()` 之前的所有写操作不一定对其他线程可见，破坏了内存可见性保证。额外的 `setArray()` 调用开销极小（一次引用赋值），但避免了潜在的内存可见性 bug。

---

### Q5：CopyOnWriteArrayList 适用于什么场景？

**答：** 适用于**读多写少**的场景，特别是：
- 事件监听器列表（注册少，触发多）
- 配置缓存（修改少，读取多）
- 黑白名单（更新少，检查多）
- 观察者列表（订阅少，通知多）

不适用于写多读少、大数据量、需要强一致性的场景。

---

### Q6：CopyOnWriteArrayList 和 Collections.synchronizedList 有什么区别？

**答：**

| | CopyOnWriteArrayList | synchronizedList |
|---|---|---|
| 读操作 | 无锁，极快 | 加锁，较慢 |
| 写操作 | 加锁+复制数组 | 加锁 |
| 迭代 | 快照，安全，不抛CME | 需手动加锁，否则CME |
| 一致性 | 最终一致 | 强一致 |
| 写性能 | 差（O(n)） | 好（O(1)均摊） |
| 内存 | 写时双倍 | 无额外开销 |

---

### Q7：CopyOnWriteArrayList 的 addIfAbsent 方法是如何保证原子性的？

**答：** `addIfAbsent` 采用了双重检查模式：
1. 先不加锁读取快照，在快照中查找元素——如果找到了直接返回 false
2. 如果快照中没找到，加锁后重新获取最新数组，再次检查
3. 如果最新数组中仍然没有，才执行添加

这种设计兼顾了性能和正确性：大多数情况下元素已存在，可以快速返回；少数情况下元素不存在，加锁保证「检查+添加」的原子性。

---

### Q8：CopyOnWriteArrayList 的数组为什么用 volatile 修饰？

**答：** `volatile` 修饰 `array` 引用有两个关键作用：
1. **可见性保证**：写线程执行 `setArray(newArray)` 后，读线程调用 `getArray()` 一定能看到最新的引用
2. **happens-before 保证**：volatile 写之前的所有修改对 volatile 读之后的操作可见

注意：`volatile` 修饰的是引用本身，不是数组元素。但因为 COW 永远不修改旧数组的元素（只替换引用），所以引用的 volatile 语义已经足够。

---

### Q9：JDK 20 为什么把 ReentrantLock 改为 synchronized？

**答：** 主要原因：
1. CopyOnWriteArrayList 只需要简单的互斥功能，不需要 ReentrantLock 的高级特性（公平锁、Condition、tryLock、可中断等）
2. 现代 JVM 对 synchronized 的优化已经很好（偏向锁→轻量级锁→重量级锁的自适应升级）
3. 去掉 ReentrantLock 字段减少了一个对象的开销
4. 代码更简洁

---

### Q10：CopyOnWriteArrayList 在遍历的同时可以修改吗？效果是什么？

**答：** 可以。遍历（迭代）和修改互不影响：
- 遍历线程读的是创建迭代器时的数组快照
- 修改线程操作的是新数组
- 遍历线程看到的始终是快照内容，不会看到修改
- 不会抛 ConcurrentModificationException
- 修改的结果只有在遍历线程**下次**获取迭代器时才能看到

```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A"); list.add("B"); list.add("C");

// 遍历线程
Iterator<String> it = list.iterator();
list.add("D");        // 修改线程添加元素
list.remove("A");     // 修改线程删除元素

// 遍历线程仍然看到 [A, B, C]
while (it.hasNext()) {
    System.out.println(it.next()); // A, B, C
}

// 但下次遍历就看到新数据了
for (String s : list) {
    System.out.println(s); // B, C, D
}
```

---

### Q11：CopyOnWriteArrayList 的 size() 方法需要加锁吗？

**答：** 不需要。`size()` 方法直接返回数组的长度：

```java
public int size() {
    return getArray().length;
}
```

因为在 CopyOnWriteArrayList 中，数组的长度就是元素的数量（没有像 ArrayList 那样预留空间），所以直接读取 `array.length` 就是准确的。

---

### Q12：CopyOnWriteArrayList 和 CopyOnWriteArraySet 的关系？

**答：** CopyOnWriteArraySet 内部完全依赖 CopyOnWriteArrayList 实现：
- 底层存储就是 CopyOnWriteArrayList
- `add()` 调用 `addIfAbsent()` 实现去重
- 其他方法全部委托给 CopyOnWriteArrayList
- CopyOnWriteArraySet 的 `contains()` 是 O(n) 的（线性查找），不适合大数据量场景

---

### Q13：CopyOnWriteArrayList 的写时复制会不会导致内存溢出？

**答：** 理论上可能。如果列表非常大（比如百万级元素），每次写操作都会短暂地同时存在两个大数组（旧数组 + 新数组），内存占用翻倍。如果写操作非常频繁，还会产生大量垃圾数组，加剧 GC 压力。

但在实际应用中，CopyOnWriteArrayList 通常只用于小列表（几十到几百个元素），所以这个问题很少出现。如果列表可能很大，应该考虑其他并发集合。

---

### Q14：如何理解 CopyOnWriteArrayList 的「最终一致性」？

**答：** 最终一致性意味着：
1. 写操作完成**之后**的读操作一定能看到最新值
2. 写操作进行**之中**的读操作可能看到旧值
3. 多次读操作之间，值可能从旧值「跳变」到新值，但不会出现中间状态

这和数据库中的 `READ COMMITTED` 隔离级别类似——不会脏读（读到写了一半的数据），但可能不可重复读（两次读之间数据变了）。

```java
// 强一致性（synchronizedList）：
// 任何时刻，所有线程看到的列表状态完全一致

// 最终一致性（CopyOnWriteArrayList）：
// 在写操作完成的那一刻，可能会有短暂的「旧值窗口」
// 但一旦写操作完成并执行了 setArray()，后续的读操作一定看到新值
```

---

### Q15：能否用 CopyOnWriteArrayList 替代 ArrayList？

**答：** 不建议在单线程场景下使用 CopyOnWriteArrayList 替代 ArrayList，原因：
1. 写操作性能远差于 ArrayList（O(n) vs O(1) 均摊）
2. 内存开销更大（写时复制）
3. 读操作的 volatile 读虽然开销很小，但比 ArrayList 的普通读还是稍慢
4. 迭代器不支持修改操作，API 功能不如 ArrayList 完整

CopyOnWriteArrayList 只是在**并发读多写少**场景下的最优选择。

---

## 总结

CopyOnWriteArrayList 是一个精妙的并发数据结构，其核心思想可以归纳为：

```
┌──────────────────────────────────────────────────────┐
│              CopyOnWriteArrayList 设计精髓            │
├──────────────────────────────────────────────────────┤
│                                                      │
│  写时复制 —— 读操作无锁的根本保证                      │
│  ↓                                                    │
│  读写分离 —— 读走旧数组，写走新数组，互不干扰           │
│  ↓                                                    │
│  volatile 引用 —— 写完成后的即时可见性保证              │
│  ↓                                                    │
│  最终一致性 —— 牺牲强一致，换取极致读性能               │
│  ↓                                                    │
│  快照迭代 —— 遍历不加锁，不抛CME                       │
│                                                      │
│  代价：写O(n) + 内存开销 + 弱一致性                    │
│  适用：读多写少 + 小数据量 + 可容忍弱一致               │
│                                                      │
└──────────────────────────────────────────────────────┘
```

**一句话总结：CopyOnWriteArrayList 用「写时复制」的代价换来了「读不加锁」的性能，是读多写少场景下的最优解。**

---

> 本文基于 JDK 21 源码分析，不同版本实现可能有差异，请以实际使用的 JDK 版本为准。
