# Java BlockingQueue 体系深度解析

> 本文基于 JDK 8/11 源码，从生产者-消费者问题出发，逐行剖析 `BlockingQueue` 体系下各核心实现类的底层原理。面向有一定 Java 基础但不熟悉并发原理的读者，力求通俗易懂、不留死角。

---

## 目录

- [一、BlockingQueue 接口概览](#一blockingqueue-接口概览)
- [二、ArrayBlockingQueue 源码深度解析](#二arrayblockingqueue-源码深度解析)
- [三、LinkedBlockingQueue 源码深度解析](#三linkedblockingqueue-源码深度解析)
- [四、SynchronousQueue 源码深度解析](#四synchronousqueue-源码深度解析)
- [五、DelayQueue 源码深度解析](#五delayqueue-源码深度解析)
- [六、PriorityBlockingQueue 简析](#六priorityblockingqueue-简析)
- [七、LinkedTransferQueue 简析](#七linkedtransferqueue-简析)
- [八、BlockingQueue 与线程池的关系](#八blockingqueue-与线程池的关系)
- [九、实战总结与选型指南](#九实战总结与选型指南)

---

## 一、BlockingQueue 接口概览

### 1.1 为什么需要阻塞队列——从生产者消费者问题说起

在生产者-消费者模型中，有一类线程负责"生产"数据放入缓冲区，另一类线程负责从缓冲区"消费"数据。如果缓冲区满了，生产者必须等待；如果缓冲区空了，消费者必须等待。这是并发编程中最经典的场景之一。

如果不用阻塞队列，我们手写这段逻辑大概是这样的：

```java
// 手写生产者-消费者（有Bug，仅作示意）
List<String> buffer = new ArrayList<>();
final int MAX = 10;

// 生产者
synchronized (buffer) {
    while (buffer.size() == MAX) {
        buffer.wait();  // 缓冲区满，等待消费者消费
    }
    buffer.add(data);
    buffer.notifyAll(); // 通知消费者
}

// 消费者
synchronized (buffer) {
    while (buffer.isEmpty()) {
        buffer.wait();  // 缓冲区空，等待生产者生产
    }
    String data = buffer.remove(0);
    buffer.notifyAll(); // 通知生产者
}
```

这段代码能工作，但存在大量隐患：

1. **必须自己管理锁和条件变量**：忘记 `synchronized`、用错 `wait/notify` 的条件、`notify` 和 `notifyAll` 用混，都会导致死锁或丢失唤醒。
2. **`notify` 会导致"丢失唤醒"**：如果恰好唤醒了同类线程（生产者唤醒了生产者），被唤醒的线程发现条件不满足又继续等待，而真正该被唤醒的线程却没有被唤醒。
3. **代码重复且容易出错**：每个使用生产者-消费者模式的地方都要重写一遍这段逻辑。

`BlockingQueue` 的出现就是为了把这些繁琐且容易出错的等待/唤醒逻辑封装起来。你只需要调用 `put()` 放数据、`take()` 取数据，队列满了自动阻塞生产者、队列空了自动阻塞消费者，内部的锁和条件变量全部由队列自己管理。

```java
// 使用 BlockingQueue，代码简洁且正确
BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

// 生产者
queue.put(data);  // 满了自动阻塞，不需要手动 wait/notify

// 消费者
String data = queue.take();  // 空了自动阻塞，不需要手动 wait/notify
```

### 1.2 BlockingQueue 接口的四组 API 语义对比

`BlockingQueue` 接口继承自 `Queue`，针对"入队"和"出队"各提供了四组不同行为的 API。这四组 API 的区别在于**当队列满/空时的处理方式不同**：

#### 入队操作（添加元素）

| 方法 | 队列未满时 | 队列已满时 |
|------|-----------|-----------|
| `add(e)` | 返回 true | 抛出 `IllegalStateException("Queue full")` |
| `offer(e)` | 返回 true | 返回 false |
| `put(e)` | 返回 void（阻塞直到有空间） | 阻塞当前线程，直到队列有空位 |
| `offer(e, timeout, unit)` | 返回 true | 阻塞指定时间，超时后返回 false |

#### 出队操作（移除元素）

| 方法 | 队列非空时 | 队列为空时 |
|------|-----------|-----------|
| `remove()` | 返回队首元素 | 抛出 `NoSuchElementException` |
| `poll()` | 返回队首元素 | 返回 null |
| `take()` | 返回队首元素（阻塞直到有元素） | 阻塞当前线程，直到队列有元素 |
| `poll(timeout, unit)` | 返回队首元素 | 阻塞指定时间，超时后返回 null |

此外还有两组辅助方法：

| 方法 | 说明 |
|------|------|
| `element()` | 查看队首元素但不移除，空队列抛异常 |
| `peek()` | 查看队首元素但不移除，空队列返回 null |

用一张图来总结这四组 API 的行为差异：

```
                    队列正常时          队列满/空时
                   ┌──────────┐    ┌──────────────┐
  抛异常 (Throws)  │ add/remove │   │ IllegalStateException / NoSuchElementException │
                   ├──────────┤    ├──────────────┤
  返回特殊值(Special)│ offer/poll │   │ false / null │
                   ├──────────┤    ├──────────────┤
  阻塞 (Blocks)    │ put/take  │   │ 永远阻塞直到成功 │
                   ├──────────┤    ├──────────────┤
  超时 (Timeout)   │ offer/toll │   │ 阻塞指定时间后返回 false/null │
                   └──────────┘    └──────────────┘
```

**如何选择？**

- 如果你希望队列满/空时直接报错（快速失败），用 `add/remove`。
- 如果你不希望阻塞、只是"试一试"，用 `offer/poll`。
- 如果你必须等到成功为止（典型的生产者-消费者），用 `put/take`。
- 如果你希望等待但不想永远等下去，用带超时的 `offer/poll`。

### 1.3 与普通 Queue 的本质区别

普通的 `Queue`（如 `LinkedList`、`ArrayDeque`）**不提供阻塞语义**。当队列为空时，`poll()` 立即返回 null；当队列满时（如果有界），`offer()` 立即返回 false。它们不会让线程进入等待状态。

`BlockingQueue` 的本质区别在于：它在 `Queue` 的基础上增加了**阻塞等待**的能力，底层通过 `ReentrantLock` + `Condition`（或者 CAS + 自旋）来实现线程安全且有阻塞/唤醒功能。这意味着：

1. **线程安全**：`BlockingQueue` 的所有操作都是线程安全的，多线程并发操作不需要外部加锁。
2. **阻塞语义**：`put` 和 `take` 操作在队列满/空时会阻塞当前线程，直到条件满足被唤醒。
3. **不能存 null**：所有 `BlockingQueue` 实现都不允许存入 null，因为 `poll()` 在空队列时返回 null，如果允许存 null 就会产生歧义。

---

## 二、ArrayBlockingQueue 源码深度解析

### 2.1 整体结构

`ArrayBlockingQueue` 是一个**由数组实现的有界阻塞队列**。它的核心设计可以用三个关键词概括：

- **数组**：底层用一个固定大小的 `Object[]` 数组存储元素。
- **单锁**：使用一把 `ReentrantLock` 控制所有读写操作（无论是入队还是出队，都要获取同一把锁）。
- **两个 Condition**：`notEmpty` 用于阻塞/唤醒消费者，`notFull` 用于阻塞/唤醒生产者。

```
┌─────────────────────────────────────────────────────┐
│                ArrayBlockingQueue                    │
│                                                      │
│   ReentrantLock lock  ←──── 一把锁管所有操作           │
│   ├── Condition notEmpty ←── 队列非空条件（唤醒消费者） │
│   └── Condition notFull  ←── 队列未满条件（唤醒生产者） │
│                                                      │
│   Object[] items       ←── 固定大小数组                │
│   int takeIndex        ←── 下一次取元素的位置           │
│   int putIndex         ←── 下一次放元素的位置           │
│   int count            ←── 当前元素个数                 │
└─────────────────────────────────────────────────────┘
```

### 2.2 核心字段

```java
public class ArrayBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /** 底层存储数组，Final修饰，创建后大小不可变 */
    final Object[] items;

    /** 下一次take/poll/remove的位置（消费指针） */
    int takeIndex;

    /** 下一次put/offer/add的位置（生产指针） */
    int putIndex;

    /** 队列中当前元素个数 */
    int count;

    /** 全局锁，所有入队和出队操作都通过这把锁来同步 */
    final ReentrantLock lock;

    /** 等待"队列非空"的条件，消费者在此等待 */
    private final Condition notEmpty;

    /** 等待"队列未满"的条件，生产者在此等待 */
    private final Condition notFull;
}
```

几个关键字段的含义：

- `items`：底层数组，在构造方法中通过 `new Object[capacity]` 创建，大小固定。注意它是 `Object[]` 而不是 `E[]`，这是由于泛型擦除的限制。
- `takeIndex`：指向下一个应该被取出的元素位置。每次取出一个元素后，`takeIndex` 向前移动一位（到末尾则回到0）。
- `putIndex`：指向下一个应该放入元素的位置。每次放入一个元素后，`putIndex` 向前移动一位（到末尾则回到0）。
- `count`：当前队列中有多少个元素。当 `count == items.length` 时队列满，当 `count == 0` 时队列空。
- `lock`：唯一的一把 `ReentrantLock`，公平或非公平取决于构造参数。
- `notEmpty`：由 `lock.newCondition()` 创建，消费者调用 `notEmpty.await()` 阻塞，生产者入队后调用 `notEmpty.signal()` 唤醒。
- `notFull`：由 `lock.newCondition()` 创建，生产者调用 `notFull.await()` 阻塞，消费者出队后调用 `notFull.signal()` 唤醒。

### 2.3 构造方法

```java
public ArrayBlockingQueue(int capacity) {
    this(capacity, false); // 默认非公平锁
}

public ArrayBlockingQueue(int capacity, boolean fair) {
    if (capacity <= 0)
        throw new IllegalArgumentException();
    this.items = new Object[capacity]; // 创建固定大小数组
    this.lock = new ReentrantLock(fair); // 创建锁，fair决定是否公平
    this.notEmpty = lock.newCondition(); // 创建"非空"条件
    this.notFull = lock.newCondition();  // 创建"未满"条件
}
```

`fair` 参数控制锁的公平性：公平锁按线程等待顺序获取锁（FIFO），非公平锁允许插队。公平锁能避免线程饥饿但吞吐量略低。

### 2.4 put() 方法完整源码分析

`put()` 是阻塞式入队方法，队列满了会一直阻塞直到有空位。

```java
public void put(E e) throws InterruptedException {
    checkNotNull(e);          // ① 检查元素非空，null直接抛NPE
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly(); // ② 获取锁，但响应中断（如果在等待锁时被中断会抛InterruptedException）
    try {
        while (count == items.length) // ③ 循环判断队列是否已满
            notFull.await();   //    如果满了，在notFull条件上等待（释放锁，进入等待队列）
                               //    被唤醒后会重新竞争锁，拿到锁后再次检查条件（防止虚假唤醒）
        enqueue(e);            // ④ 条件满足（队列没满），执行入队操作
    } finally {
        lock.unlock();         // ⑤ 释放锁
    }
}
```

逐步解释这段代码的执行流程：

**第①步**：`checkNotNull(e)` 是一个简单的方法，检查传入的元素是否为 null。如果是 null，直接抛出 `NullPointerException`。BlockingQueue 不允许存 null。

```java
private static void checkNotNull(Object v) {
    if (v == null)
        throw new NullPointerException();
}
```

**第②步**：`lock.lockInterruptibly()` 获取锁。和 `lock.lock()` 的区别在于，`lockInterruptibly()` 在等待获取锁的过程中可以被中断。如果线程在等锁时被其他线程调用了 `interrupt()`，会抛出 `InterruptedException`，不会一直傻等。

**第③步**：这里用的是 `while` 而不是 `if`，这是并发编程的经典范式。原因在于"虚假唤醒"（spurious wakeup）——线程有可能在没有收到 `signal()` 的情况下从 `await()` 中返回。用 `while` 可以确保被唤醒后重新检查条件，如果条件仍然不满足就继续等待。即使不考虑虚假唤醒，用 `while` 也是必要的：当多个生产者都在 `notFull.await()` 等待时，一个消费者消费了一个元素并调用 `notFull.signal()`，只唤醒一个生产者，但如果在唤醒的生产者拿到锁之前，另一个生产者抢先入队了元素导致队列又满了，被唤醒的生产者拿到锁后必须重新检查条件。

**第④步**：`enqueue(e)` 执行实际的入队操作，下一节详细分析。

**第⑤步**：`finally` 块中释放锁，确保即使 `enqueue` 抛异常也能释放锁。

### 2.5 enqueue() —— 环形数组入队

```java
private void enqueue(E x) {
    final Object[] items = this.items;
    items[putIndex] = x;           // ① 将元素放入putIndex位置
    if (++putIndex == items.length) // ② putIndex加1，如果到达数组末尾
        putIndex = 0;              //    则回到0，形成环形
    count++;                       // ③ 元素个数+1
    notEmpty.signal();             // ④ 唤醒一个在notEmpty上等待的消费者
}
```

环形数组的工作原理：

假设数组长度为5（`items[0]` 到 `items[4]`）：

```
初始状态：count=0, putIndex=0, takeIndex=0
[ _ | _ | _ | _ | _ ]

put(A): items[0]=A, putIndex=1, count=1
[ A | _ | _ | _ | _ ]

put(B): items[1]=B, putIndex=2, count=2
[ A | B | _ | _ | _ ]

... 继续放入C, D, E ...

put(E): items[4]=E, putIndex变为5 == length, 所以putIndex=0, count=5
[ A | B | C | D | E ]   ← 队列满了

take(): 取出items[0]=A, takeIndex=1, count=4
[ _ | B | C | D | E ]

put(F): items[0]=F, putIndex=1, count=5  ← putIndex回到了0，复用了已被消费的位置
[ F | B | C | D | E ]   ← 环形复用！
```

这就是"环形数组"的核心：`putIndex` 和 `takeIndex` 到达数组末尾后都回到0，实现了数组的循环复用，避免了数组搬移的开销。

**第④步** `notEmpty.signal()`：入队成功后，队列中一定有元素了，所以唤醒一个等待在 `notEmpty` 上的消费者线程（如果有等待的话）。注意 `signal()` 只唤醒一个，而不是 `signalAll()`，这是为了减少不必要的竞争。

### 2.6 take() 方法完整源码分析

`take()` 是阻塞式出队方法，队列空了会一直阻塞直到有元素。

```java
public E take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();  // ① 获取锁，响应中断
    try {
        while (count == 0)     // ② 循环判断队列是否为空
            notEmpty.await();  //    如果空了，在notEmpty条件上等待（释放锁）
                            //    被唤醒后重新竞争锁，再次检查条件
        return dequeue();      // ③ 条件满足（队列非空），执行出队
    } finally {
        lock.unlock();         // ④ 释放锁
    }
}
```

逻辑和 `put()` 完全对称：

1. 获取锁（响应中断）。
2. 如果队列为空，在 `notEmpty` 条件上等待。被唤醒后用 `while` 重新检查。
3. 队列非空，调用 `dequeue()` 出队。
4. 释放锁。

### 2.7 dequeue() —— 环形数组出队

```java
private E dequeue() {
    final Object[] items = this.items;
    @SuppressWarnings("unchecked")
    E x = (E) items[takeIndex];   // ① 取出takeIndex位置的元素
    items[takeIndex] = null;      // ② 将该位置置null，帮助GC
    if (++takeIndex == items.length) // ③ takeIndex加1，到末尾则回到0
        takeIndex = 0;
    count--;                       // ④ 元素个数-1
    notFull.signal();              // ⑤ 唤醒一个在notFull上等待的生产者
    return x;                      // ⑥ 返回取出的元素
}
```

和 `enqueue()` 完全对称：取出 `takeIndex` 位置的元素，置 null 帮助 GC，`takeIndex` 循环前进，`count` 递减，最后唤醒一个等待的生产者。

**第②步** `items[takeIndex] = null` 非常重要：如果不置 null，被取走的对象仍然被数组引用着，垃圾回收器无法回收它，这在队列长时间运行时会导致内存泄漏。

### 2.8 offer() 和 poll() 方法（非阻塞版本）

```java
public boolean offer(E e) {
    checkNotNull(e);
    final ReentrantLock lock = this.lock;
    lock.lock();              // 不响应中断的加锁
    try {
        if (count == items.length) // 队列满直接返回false，不等待
            return false;
        else {
            enqueue(e);
            return true;
        }
    } finally {
        lock.unlock();
    }
}

public E poll() {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        return (count == 0) ? null : dequeue(); // 队列空直接返回null
    } finally {
        lock.unlock();
    }
}
```

对比 `put/take`，`offer/poll` 的区别就是：**满了/空了不等待，直接返回**。

### 2.9 带超时的 offer() 方法

```java
public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
    checkNotNull(e);
    long nanos = unit.toNanos(timeout);  // 将超时时间转换为纳秒
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
        while (count == items.length) {
            if (nanos <= 0)         // 如果剩余等待时间已经用完
                return false;       // 直接返回false，放弃入队
            nanos = notFull.awaitNanos(nanos); // 带超时地等待，返回剩余等待时间
        }
        enqueue(e);
        return true;
    } finally {
        lock.unlock();
    }
}
```

`awaitNanos(nanos)` 的返回值是剩余的等待时间（纳秒）。如果在超时前被唤醒，返回值大于0；如果超时了，返回值小于等于0。通过循环检查返回值，可以做到精确的超时控制。

### 2.10 为什么用单锁而不是双锁？

`ArrayBlockingQueue` 使用一把 `ReentrantLock` 同时控制入队和出队操作。这意味着**入队和出队不能同时进行**，它们是互斥的。

为什么这么设计？因为 `ArrayBlockingQueue` 底层是数组，入队和出队操作都会修改 `putIndex`、`takeIndex` 和 `count` 这三个共享变量。如果用两把锁分别控制入队和出队，当入队线程修改 `putIndex` 和 `count` 的同时，出队线程也在修改 `takeIndex` 和 `count`，`count` 这个变量就需要用原子类或者额外的锁来保护，逻辑会变得非常复杂，而且容易出错。

单锁的代价是**性能**：在高并发场景下，所有线程都要竞争同一把锁，吞吐量会受到限制。但对于数组这种紧凑的内存结构，单锁的实现足够简单且正确性容易保证。

与之对比，`LinkedBlockingQueue` 使用了双锁分离设计，入队和出队可以并行，吞吐量更高。我们下一章详述。

---

## 三、LinkedBlockingQueue 源码深度解析

### 3.1 整体结构

`LinkedBlockingQueue` 是一个**由链表实现的可选有界阻塞队列**。它的核心设计是**锁分离（Lock Separation）**：入队和出队各用一把锁，互不干扰。

```
┌──────────────────────────────────────────────────────────┐
│                   LinkedBlockingQueue                      │
│                                                            │
│   ReentrantLock takeLock ←──── 出队专用锁                   │
│   ├── Condition notEmpty  ←── 队列非空条件（消费者等待）      │
│                                                            │
│   ReentrantLock putLock  ←──── 入队专用锁                   │
│   └── Condition notFull   ←── 队列未满条件（生产者等待）      │
│                                                            │
│   AtomicInteger count    ←── 原子计数器，两把锁共享          │
│                                                            │
│   Node head              ←── 哑节点（不存数据）              │
│   Node last              ←── 链表尾节点                     │
│   int capacity           ←── 队列容量（默认Integer.MAX_VALUE）│
└──────────────────────────────────────────────────────────┘
```

链表节点定义：

```java
static class Node<E> {
    E item;         // 节点存储的数据
    Node<E> next;   // 指向下一个节点

    Node(E x) { item = x; }
}
```

### 3.2 为什么可以用双锁？和 ArrayBlockingQueue 的设计差异

`ArrayBlockingQueue` 用单锁，是因为入队和出队共享 `putIndex`、`takeIndex`、`count` 三个变量，必须互斥访问。

`LinkedBlockingQueue` 能用双锁，关键在于**链表的结构天然支持头尾分离操作**：

- **入队操作**只操作 `last` 指针（在链表尾部追加节点），不碰 `head`。
- **出队操作**只操作 `head` 指针（摘除头节点的下一个节点），不碰 `last`。

入队和出队操作各自只修改自己的指针，**不会修改对方的指针**。唯一的共享变量是 `count`（当前元素个数），这个用 `AtomicInteger` 来保证原子性，不需要加锁。

这样设计的好处是：**一个线程在入队的同时，另一个线程可以同时出队**，两把锁互不阻塞，大大提高了并发吞吐量。

但这也有代价：实现更复杂，需要小心处理"跨锁唤醒"的场景（后面会讲 `signalNotEmpty` 和 `signalNotFull`）。

### 3.3 核心字段

```java
public class LinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /** 链表节点 */
    static class Node<E> {
        E item;
        Node<E> next;
        Node(E x) { item = x; }
    }

    /** 容量上限，默认为 Integer.MAX_VALUE（实际上是无界） */
    private final int capacity;

    /** 当前元素个数，使用AtomicInteger保证原子性 */
    private final AtomicInteger count = new AtomicInteger();

    /** 链表头节点，item始终为null（哑节点） */
    transient Node<E> head;

    /** 链表尾节点 */
    private transient Node<E> last;

    /** 出队锁 */
    private final ReentrantLock takeLock = new ReentrantLock();

    /** 等待"队列非空"的条件 */
    private final Condition notEmpty = takeLock.newCondition();

    /** 入队锁 */
    private final ReentrantLock putLock = new ReentrantLock();

    /** 等待"队列未满"的条件 */
    private final Condition notFull = putLock.newCondition();
}
```

注意几个关键点：

1. `head` 是一个**哑节点**（dummy node），它的 `item` 始终为 null。出队时实际上是取出 `head.next` 的元素，然后将 `head` 指向 `head.next`。使用哑节点的好处是简化边界处理（空链表和非空链表的逻辑统一）。
2. `count` 用 `AtomicInteger` 而不是普通 `int`，因为 `count` 被入队和出队两把锁共享，普通 `int` 在两把锁之间无法保证可见性和原子性。
3. `notEmpty` 绑定在 `takeLock` 上，`notFull` 绑定在 `putLock` 上。这意味着消费者在 `notEmpty` 上等待时持有的是 `takeLock`，生产者在 `notFull` 上等待时持有的是 `putLock`。

### 3.4 构造方法

```java
public LinkedBlockingQueue() {
    this(Integer.MAX_VALUE); // 默认容量为Integer.MAX_VALUE，实际上是"无界"的
}

public LinkedBlockingQueue(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException();
    this.capacity = capacity;
    last = head = new Node<E>(null); // 创建哑节点，head和last都指向它
}
```

无参构造方法将容量设为 `Integer.MAX_VALUE`，这意味着在大多数场景下队列永远不会满（除非内存耗尽）。这也是为什么 `Executors.newFixedThreadPool()` 使用 `LinkedBlockingQueue` 时，核心线程数和最大线程数相同——因为队列永远不会满，不会触发创建非核心线程的逻辑。

### 3.5 put() 方法源码分析

```java
public void put(E e) throws InterruptedException {
    if (e == null) throw new NullPointerException(); // ① 不允许null
    int c = -1;                    // 用于记录入队前的count值（用于后续判断是否需要唤醒生产者）
    Node<E> node = new Node<E>(e); // ② 预先创建新节点（不需要加锁）
    final ReentrantLock putLock = this.putLock;
    final AtomicInteger count = this.count;
    putLock.lockInterruptibly();   // ③ 获取入队锁
    try {
        while (count.get() == capacity) { // ④ 如果队列已满
            notFull.await();       //    在notFull条件上等待
        }
        enqueue(node);             // ⑤ 入队（链表尾部追加）
        c = count.getAndIncrement(); // ⑥ count原子+1，返回的是增加前的值
        if (c + 1 < capacity)      // ⑦ 如果入队后队列还没满
            notFull.signal();      //    唤醒另一个等待的生产者（可能有多个生产者在等待）
    } finally {
        putLock.unlock();          // ⑧ 释放入队锁
    }
    if (c == 0)                    // ⑨ 如果入队前队列是空的（c是增加前的值，c==0说明入队前count为0）
        signalNotEmpty();          //    唤醒等待的消费者
}
```

这段代码有几个非常精妙的设计，逐一解释：

**第②步**：预先在锁外创建 `Node` 对象。这样在获取锁后不需要再执行 `new Node(e)`，减少了锁的持有时间。

**第④步**：用 `count.get()` 读取当前元素个数。由于 `count` 是 `AtomicInteger`，读取操作是原子的且具有内存可见性（`volatile` 语义）。这里用 `while` 而不是 `if` 的原因和 `ArrayBlockingQueue` 一样：防止虚假唤醒和条件竞争。

**第⑤步** `enqueue(node)`：

```java
private void enqueue(Node<E> node) {
    last = last.next = node;
    // 等价于：
    // last.next = node;  // 当前尾节点的next指向新节点
    // last = node;       // last指针移动到新节点
}
```

链表入队非常简单：把新节点接到尾节点后面，然后 `last` 指针后移。

**第⑥步**：`count.getAndIncrement()` 原子地将 count 加1，返回值是加1之前的旧值。比如入队前 count=3，返回3，count变为4。`c` 保存的就是旧值。

**第⑦步**：`if (c + 1 < capacity)`。`c` 是入队前的 count，`c + 1` 就是入队后的 count。如果入队后队列还没满，就唤醒另一个等待的生产者。这是一种**批量唤醒**的优化思路：一个生产者入队后如果发现队列还有空间，顺手唤醒下一个生产者继续入队，避免每个生产者都要等消费者来唤醒。

**第⑨步**：这是最精妙的部分。`c == 0` 意味着入队前队列是空的（入队后 count=1）。此时可能有消费者在 `notEmpty` 上等待。但这里不能直接调用 `notEmpty.signal()`，因为 `notEmpty` 绑定在 `takeLock` 上，当前线程持有的是 `putLock`，不能操作别人的 Condition。

为什么要判断 `c == 0` 而不是 `c <= 0`？因为 `c` 是 `getAndIncrement` 的返回值，最小就是0（队列空时入队）。如果 `c > 0`，说明入队前队列已经有元素了，消费者不可能在等待（因为队列非空），所以不需要唤醒。只有 `c == 0` 时，才需要唤醒消费者。

### 3.6 signalNotEmpty() —— 跨锁唤醒

```java
private void signalNotEmpty() {
    final ReentrantLock takeLock = this.takeLock;
    takeLock.lock();         // ① 获取出队锁（消费者的锁）
    try {
        notEmpty.signal();   // ② 在notEmpty条件上唤醒一个消费者
    } finally {
        takeLock.unlock();   // ③ 释放入队锁
    }
}
```

**为什么要获取对方的锁？**

因为 `Condition` 对象绑定在特定的 `Lock` 上。`notEmpty` 是通过 `takeLock.newCondition()` 创建的，要调用 `notEmpty.signal()` 必须先持有 `takeLock`。这是 `Condition` 的使用规则——必须在持有锁的情况下才能调用 `await()` 和 `signal()`。

所以当生产者（持有 `putLock`）想要唤醒消费者时，必须先获取 `takeLock`，调用 `signal()` 后再释放。这就是"跨锁唤醒"。

同理，消费者出队后如果发现队列从满变为非满，需要唤醒生产者，也要通过 `signalNotFull()` 获取 `putLock`：

```java
private void signalNotFull() {
    final ReentrantLock putLock = this.putLock;
    putLock.lock();
    try {
        notFull.signal();
    } finally {
        putLock.unlock();
    }
}
```

### 3.7 take() 方法源码分析

```java
public E take() throws InterruptedException {
    E x;
    int c = -1;                    // 记录出队前的count值
    final AtomicInteger count = this.count;
    final ReentrantLock takeLock = this.takeLock;
    takeLock.lockInterruptibly();  // ① 获取出队锁
    try {
        while (count.get() == 0) { // ② 如果队列为空
            notEmpty.await();      //    在notEmpty条件上等待
        }
        x = dequeue();             // ③ 出队（链表头部摘除）
        c = count.getAndDecrement(); // ④ count原子-1，返回减少前的值
        if (c > 1)                 // ⑤ 如果出队后队列还有元素（c是减少前的值，c>1说明出队后count>=1）
            notEmpty.signal();     //    唤醒另一个等待的消费者
    } finally {
        takeLock.unlock();         // ⑥ 释放入队锁
    }
    if (c == capacity)             // ⑦ 如果出队前队列是满的
        signalNotFull();           //    唤醒等待的生产者
    return x;
}
```

逻辑和 `put()` 完全对称，逐一对应：

- **第①步**：获取 `takeLock`（出队锁）。
- **第②步**：队列为空则等待。
- **第③步** `dequeue()`：

```java
private E dequeue() {
    Node<E> h = head;         // h是当前哑节点
    Node<E> first = h.next;   // first是真正要取出的节点
    h.next = h;               // 帮助GC：断开哑节点对first的引用
    head = first;             // head指针后移，first成为新的哑节点
    E x = first.item;         // 取出数据
    first.item = null;        // 新哑节点的item置null
    return x;
}
```

这里有一个"移头"操作：旧哑节点 `head` 被丢弃，`head.next`（即真正存数据的节点）成为新的 `head`（哑节点），它的 `item` 被取出后置 null。这种设计避免了链表操作中对"头节点"和"头节点的下一个节点"的特殊处理。

- **第④步**：`count.getAndDecrement()` 原子减1，返回旧值。
- **第⑤步**：`c > 1` 意味着出队后队列还有元素（出队后 count = c - 1 >= 1），唤醒另一个等待的消费者。
- **第⑦步**：`c == capacity` 意味着出队前队列是满的，可能有生产者在等待，需要唤醒。由于要操作 `notFull`（绑定在 `putLock` 上），所以调用 `signalNotFull()` 跨锁唤醒。

### 3.8 AtomicInteger count 的作用：为什么不用普通 int？

`count` 被入队线程（持有 `putLock`）和出队线程（持有 `takeLock`）共享读写。如果用普通 `int`：

1. **可见性问题**：入队线程修改了 `count`，出队线程可能看不到最新值（每个线程有自己的CPU缓存）。
2. **原子性问题**：`count++` 和 `count--` 不是原子操作（读-改-写三步），在两把不同的锁下并发修改会产生数据不一致。

用 `AtomicInteger` 解决了这两个问题：

- `AtomicInteger` 内部用 `volatile` 保证了可见性。
- `getAndIncrement()` / `getAndDecrement()` 用 CAS 操作保证了原子性。

这样，入队线程和出队线程各自持有自己的锁，通过 `AtomicInteger` 安全地读写 `count`，实现了真正的锁分离。

### 3.9 与 ArrayBlockingQueue 的性能对比和选型建议

| 对比项 | ArrayBlockingQueue | LinkedBlockingQueue |
|--------|-------------------|-------------------|
| 底层结构 | 数组 | 链表 |
| 锁设计 | 单锁（一把锁管所有操作） | 双锁（入队锁 + 出队锁） |
| 并发度 | 入队和出队互斥 | 入队和出队可并行 |
| 容量 | 必须有界（构造时指定） | 可选有界（默认 Integer.MAX_VALUE） |
| 内存开销 | 数组连续内存，开销小 | 每个节点多一个 Node 对象和 next 指针，开销大 |
| GC影响 | 无额外对象创建 | 每次入队创建 Node 对象，出队后等待 GC |
| 吞吐量 | 较低（单锁瓶颈） | 较高（双锁并行） |
| 适用场景 | 有界、内存敏感、公平性要求 | 高吞吐、队列较大 |

选型建议：

- 如果队列容量已知且不大，对内存敏感，选 `ArrayBlockingQueue`。
- 如果追求高吞吐量，或者队列容量较大/无界，选 `LinkedBlockingQueue`。
- 生产环境中推荐使用有界队列（无论哪种实现），防止 OOM。

---

## 四、SynchronousQueue 源码深度解析

### 4.1 核心设计思想

`SynchronousQueue` 是一种特殊的阻塞队列：**它内部不存储任何元素**。每一个 `put` 操作必须等待一个 `take` 操作，反之亦然。可以把它想象成一个"直接传递"的管道——生产者把数据直接"递"到消费者手中，中间不停留。

```
生产者 ──put(data)──→  [不存储]  ──take()──→ 消费者
                         ↑
                   必须等对方就绪
```

这和之前讲的队列有本质区别：`ArrayBlockingQueue` 和 `LinkedBlockingQueue` 是"缓冲区"，生产者先把数据放进去，消费者再从中取。`SynchronousQueue` 没有"缓冲区"这个概念，它更像是一个"会合点"（rendezvous）。

```java
SynchronousQueue<String> queue = new SynchronousQueue<>();

// 线程A：put会阻塞，直到线程B来take
new Thread(() -> {
    try {
        queue.put("hello"); // 阻塞，直到有消费者取走
        System.out.println("put成功");
    } catch (InterruptedException e) {}
}).start();

// 线程B：take会阻塞，直到线程A来put
new Thread(() -> {
    try {
        String data = queue.take(); // 阻塞，直到有生产者放入
        System.out.println("take到: " + data);
    } catch (InterruptedException e) {}
}).start();
```

### 4.2 两种模式：公平与非公平

`SynchronousQueue` 支持两种模式，由构造参数决定：

```java
public SynchronousQueue() {
    this(false); // 默认非公平
}

public SynchronousQueue(boolean fair) {
    transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
}
```

- **公平模式（TransferQueue）**：使用 FIFO 队列管理等待的线程，先到的先匹配。公平但吞吐量略低。
- **非公平模式（TransferStack）**：使用 LIFO 栈管理等待的线程，后到的先匹配。非公平但吞吐量更高（因为新来的请求优先匹配栈顶的等待者，缓存友好）。

两者的核心都是 `transfer(E e, boolean timed, long nanos)` 方法。

### 4.3 TransferStack 的核心数据结构

```java
static final class TransferStack<E> extends Transferer<E> {

    /** 节点类型：消费者正在请求 */
    static final int REQUEST    = 0;
    /** 节点类型：生产者正在等待匹配的数据 */
    static final int DATA       = 1;
    /** 节点类型：正在匹配中 */
    static final int FULFILLING = 2;

    static final class SNode {
        volatile SNode next;     // 栈中下一个节点
        volatile SNode match;    // 匹配到的节点
        volatile Thread waiter;  // 等待的线程
        Object item;             // 数据（生产者有数据，消费者为null）
        int mode;                // 节点类型：REQUEST / DATA / FULFILLING
    }

    volatile SNode head; // 栈顶
}
```

`mode` 字段表示节点的类型：

- `REQUEST`（0）：消费者节点，等待获取数据。
- `DATA`（1）：生产者节点，等待交付数据。
- `FULFILLING`（2）：正在匹配中的节点。匹配节点的 mode 为 `mode | FULFILLING`（即 2 或 3）。

两个互补类型的节点（`REQUEST` 和 `DATA`）可以匹配。

### 4.4 transfer() 方法的核心逻辑

`transfer()` 是 `SynchronousQueue` 的核心方法，`put`、`take`、`offer`、`poll` 底层都调用它。

```java
E transfer(E e, boolean timed, long nanos) {
    SNode s = null; // 要压入栈的节点
    int mode = (e == null) ? REQUEST : DATA; // e为null是消费者，否则是生产者

    for (;;) {
        SNode h = head;
        // 情况1：栈为空，或者栈顶节点和自己类型相同（都是消费者或都是生产者）
        if (h == null || h.mode == mode) {
            // 1a：如果设置了超时且超时时间已到
            if (timed && nanos <= 0) {
                if (h != null && h.isCancelled())
                    casHead(h, h.next); // 清除已取消的节点
                else
                    return null; // 直接返回null（超时失败）
            }
            // 1b：创建节点并压入栈
            else if (casHead(h, s = snode(s, e, h, mode))) {
                // 节点入栈成功，等待被匹配
                SNode m = awaitFulfill(s, timed, nanos);
                if (m == s) { // 被取消
                    clean(s);
                    return null;
                }
                if (m != null) { // 被匹配成功
                    // 帮助匹配方出栈
                    if (m.tryMatch == h || m.tryMatch == s) {
                        casHead(s, s.next);
                    }
                    return (mode == REQUEST) ? (E)m.item : e;
                }
            }
        }
        // 情况2：栈顶节点和自己类型不同（互补），需要匹配
        else if (!isFulfilling(h.mode)) { // 栈顶节点还没在匹配中
            // 尝试匹配栈顶节点
            SNode m = snode(s, e, h, mode | FULFILLING);
            if (casHead(h, m)) { // 将匹配节点压入栈顶
                // 等待被匹配方确认
                SNode d = awaitFulfill(m, timed, nanos);
                if (d == m) { // 被取消
                    clean(m);
                    return null;
                }
                // 匹配成功，帮助出栈
                if (d != null && d.next == m) {
                    casHead(m, s.next);
                }
                return (mode == REQUEST) ? (E)d.item : e;
            }
        }
        // 情况3：栈顶节点正在匹配中，帮助它完成匹配
        else { // 栈顶节点已经处于FULFILLING状态
            SNode m = h.next; // 正在被匹配的节点
            if (m == null || m.tryMatch(h) == null) {
                casHead(h, h.next); // 匹配失败，弹出栈顶
            } else {
                casHead(h, m.next); // 匹配成功，弹出两个节点
            }
        }
    }
}
```

这个方法的逻辑可以概括为三种情况：

**情况1：栈空或栈顶是同类型节点**

当前线程（生产者或消费者）发现没有互补的等待者，就创建一个新节点压入栈顶，然后调用 `awaitFulfill()` 阻塞等待。直到有互补类型的线程来匹配它。

```
栈空时，生产者put("A")：
  栈顶 ← [DATA: "A", 等待中]

此时另一个消费者take()来了，走到情况2。
```

**情况2：栈顶是互补类型节点**

当前线程发现栈顶是互补类型的节点（比如自己是消费者，栈顶是生产者），就创建一个 `FULFILLING` 节点压入栈顶，表示"正在匹配"，然后尝试与栈顶的互补节点配对。

```
栈顶已有 [DATA: "A"]，消费者take()来了：
  栈顶 ← [FULFILLING: 消费者] → 匹配 [DATA: "A"]

匹配成功后，两个节点都出栈，栈恢复为空。
消费者拿到"A"，生产者被唤醒从put()返回。
```

**情况3：栈顶已经在匹配中**

如果栈顶节点已经在 FULFILLING 状态（说明有其他线程正在匹配），当前线程帮助完成这个匹配操作（帮助出栈），然后继续循环尝试自己的匹配。

这是一种**帮助机制**：当看到有人在匹配时，不要干等，先帮他完成，这样自己匹配时栈的状态更干净。

### 4.5 awaitFulfill() —— 等待匹配

```java
SNode awaitFulfill(SNode s, boolean timed, long nanos) {
    final long deadline = timed ? System.nanoTime() + nanos : 0L;
    Thread w = Thread.currentThread();
    // 计算自旋次数（多核CPU自旋多一些）
    int spins = (shouldSpin(s) ? (timed ? maxTimedSpins : maxUntimedSpins) : 0);
    for (;;) {
        if (w.isInterrupted())
            s.tryCancel(); // 被中断则取消
        SNode m = s.match;  // 检查是否被匹配
        if (m != null)
            return m;       // 已匹配，返回匹配节点
        if (timed) {
            nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                s.tryCancel(); // 超时则取消
                continue;
            }
        }
        if (spins > 0)
            spins--;        // 先自旋等待，避免立即park
        else if (s.waiter == null)
            s.waiter = w;   // 设置等待线程
        else if (!timed)
            LockSupport.park(this); // 无限阻塞
        else if (nanos > spinForTimeoutThreshold)
            LockSupport.parkNanos(this, nanos); // 超时阻塞
    }
}
```

这里有一个**自旋优化**：在阻塞之前先自旋等待一段时间。因为如果互补的线程很快就来了，自旋比 park/unpark 的开销小得多。自旋次数根据 CPU 核心数决定。

### 4.6 TransferQueue（公平模式）简述

`TransferQueue` 的逻辑和 `TransferStack` 类似，区别在于用 FIFO 队列代替 LIFO 栈。队头的线程优先被匹配，保证了公平性。核心方法仍然是 `transfer()`，逻辑结构相似，只是数据结构从栈变成了队列。

### 4.7 典型使用场景：Executors.newCachedThreadPool

```java
public static ExecutorService newCachedThreadPool() {
    return new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                                  60L, TimeUnit.SECONDS,
                                  new SynchronousQueue<Runnable>());
}
```

`newCachedThreadPool` 使用 `SynchronousQueue` 作为工作队列。因为 `SynchronousQueue` 不存储元素，每当提交一个新任务时：

1. 如果有空闲线程，任务直接被空闲线程取走执行。
2. 如果没有空闲线程，线程池会创建新线程来处理任务（因为 `SynchronousQueue` 的 `offer` 在没有消费者时会失败，触发线程池创建新线程）。
3. 空闲线程在60秒后会被回收。

这种设计适合**短生命周期、高突发**的任务：任务来得快去得也快，不需要排队，有任务就立即交给线程处理。

---

## 五、DelayQueue 源码深度解析

### 5.1 整体结构

`DelayQueue` 是一个**延迟队列**：元素只有在到期后才能被取出。它底层使用 `PriorityQueue`（优先级队列，基于二叉堆）来按到期时间排序，最早到期的元素在堆顶。

```
┌──────────────────────────────────────────────────┐
│                    DelayQueue                      │
│                                                    │
│   PriorityQueue<E> q  ←── 优先级队列（二叉堆）       │
│                            按到期时间排序，最早到期在堆顶│
│                                                    │
│   ReentrantLock lock   ←── 全局锁                   │
│   Condition available ←── 等待条件                  │
│                                                    │
│   Thread leader       ←── Leader线程优化             │
└──────────────────────────────────────────────────┘
```

核心字段：

```java
public class DelayQueue<E extends Delayed> extends AbstractQueue<E>
        implements BlockingQueue<E> {

    private final transient ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<E> q = new PriorityQueue<E>();
    private final Condition available = lock.newCondition();
    private Thread leader = null; // Leader线程
}
```

注意类型参数 `<E extends Delayed>`：放入 `DelayQueue` 的元素必须实现 `Delayed` 接口。

### 5.2 Delayed 接口

```java
public interface Delayed extends Comparable<Delayed> {
    /**
     * 返回剩余延迟时间（纳秒）。
     * 返回值 <= 0 表示已到期，可以被取出。
     */
    long getDelay(TimeUnit unit);
}

public interface Comparable<T> {
    int compareTo(T o); // 用于排序
}
```

一个典型的 `Delayed` 实现示例：

```java
class DelayedTask implements Delayed {
    String name;
    long executeTime; // 执行时间（纳秒）

    DelayedTask(String name, long delayMs) {
        this.name = name;
        this.executeTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
    }

    @Override
    public long getDelay(TimeUnit unit) {
        // 返回距离到期的剩余时间
        long diff = executeTime - System.nanoTime();
        return unit.convert(diff, TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        // 按到期时间排序，早到期的排前面
        return Long.compare(this.executeTime, ((DelayedTask) o).executeTime);
    }
}
```

`PriorityQueue` 在插入元素时使用 `compareTo` 来确定元素在堆中的位置。到期时间越早的元素越靠近堆顶。`take()` 时检查堆顶元素的 `getDelay()`，如果返回值大于0说明还没到期，需要等待。

### 5.3 take() 方法源码分析

```java
public E take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();      // ① 获取锁
    try {
        for (;;) {
            E first = q.peek();    // ② 查看堆顶元素（不移除）
            if (first == null)
                available.await(); // ②a 队列为空，无限等待
            else {
                long delay = first.getDelay(NANOSECONDS); // ③ 获取堆顶元素的剩余延迟
                if (delay <= 0)
                    return q.poll(); // ④ 已到期，直接出队返回
                // ⑤ 未到期，需要等待
                first = null; // 释放引用，避免内存泄漏
                if (leader != null)
                    available.await(); // ⑤a 已有Leader线程在等待，当前线程作为Follower无限等待
                else {
                    // ⑤b 没有Leader线程，当前线程成为Leader
                    Thread thisThread = Thread.currentThread();
                    leader = thisThread;
                    try {
                        available.awaitNanos(delay); // ⑤c Leader线程等待恰好delay纳秒
                    } finally {
                        if (leader == thisThread)
                            leader = null; // ⑥ Leader等待结束，清除Leader标记
                    }
                }
            }
        }
    } finally {
        if (leader == null && q.peek() != null)
            available.signal(); // ⑦ 如果没有Leader且队列非空，唤醒一个等待线程
        lock.unlock();          // ⑧ 释放锁
    }
}
```

这段代码的核心在于 **Leader-Follower 模式优化**，下面详细解释。

### 5.4 Leader-Follower 模式优化

**问题背景**：假设队列中有多个元素，堆顶元素还有5秒到期。多个消费者线程调用 `take()`，都发现堆顶元素没到期。如果不做优化，所有线程都会调用 `available.awaitNanos(delay)` 等待5秒。5秒后所有线程被唤醒，竞争锁，只有一个线程能拿到堆顶元素，其他线程发现堆顶元素被取走了，新的堆顶元素可能还没到期，又要继续等待。这造成了大量无意义的线程唤醒和锁竞争。

**Leader-Follower 优化**：

1. 第一个发现堆顶元素未到期的线程成为 **Leader**，它负责等待堆顶元素到期（`awaitNanos(delay)`）。
2. 其他线程成为 **Follower**，它们调用 `available.await()` 无限等待（不设超时）。
3. Leader 等待到期后，取出堆顶元素，在 `finally` 中清除 `leader` 标记，并调用 `available.signal()` 唤醒一个 Follower。
4. 被唤醒的 Follower 成为新的 Leader，等待下一个元素到期。

这样，**在任何时刻只有一个线程（Leader）在定时等待**，其他线程都在无限等待。只有 Leader 到期后才会唤醒一个 Follower，大大减少了无意义的唤醒。

**逐步走一遍流程**：

假设队列中有两个元素：A（3秒后到期）、B（5秒后到期）。

```
时刻0：线程T1调用take()
  → 堆顶是A，delay=3s > 0
  → leader为null，T1成为Leader
  → T1执行 available.awaitNanos(3s)，释放锁并等待3秒

时刻1：线程T2调用take()
  → 堆顶还是A，delay=2s > 0
  → leader=T1（不为null），T2作为Follower
  → T2执行 available.await()，无限等待

时刻2：线程T3调用take()
  → 同T2，T3也执行 available.await()

时刻3：T1的awaitNanos到期
  → T1被唤醒，重新获取锁
  → 循环回到for(;;)，堆顶还是A，delay<=0
  → T1执行q.poll()取出A，返回
  → finally中：leader==T1，所以leader=null
  → q.peek()=B不为null，leader==null，所以available.signal()
  → T2被唤醒

时刻3+：T2被唤醒
  → T2获取锁，循环回到for(;;)
  → 堆顶是B，delay=2s > 0
  → leader为null（T1已清除），T2成为新Leader
  → T2执行available.awaitNanos(2s)
  → ...以此类推
```

### 5.5 put() / offer() 方法

```java
public boolean offer(E e) {
    final ReentrantLock lock = this.lock;
    lock.lock();
    try {
        q.offer(e);               // ① 将元素插入优先级队列（堆）
        if (q.peek() == e) {      // ② 如果新元素成了堆顶（说明它到期最早）
            leader = null;        //    清除Leader（因为新的堆顶可能比之前Leader等待的更早到期）
            available.signal();   //    唤醒一个等待的消费者
        }
        return true;
    } finally {
        lock.unlock();
    }
}
```

第②步很关键：如果新插入的元素成了堆顶，说明它的到期时间比之前所有元素都早。之前 Leader 线程在等待旧堆顶到期，但新堆顶可能更早到期，所以需要清除 `leader` 并唤醒一个等待线程来重新检查堆顶。

### 5.6 典型使用场景

1. **ScheduledThreadPoolExecutor**：`ScheduledThreadPoolExecutor` 内部使用 `DelayedWorkQueue`（`DelayQueue` 的变体），用于定时任务调度。提交一个延迟5秒执行的任务，就是往延迟队列中放入一个5秒后到期的元素。

2. **缓存过期清理**：将缓存条目包装成 `Delayed` 对象放入 `DelayQueue`，到期后自动被取出清理。

3. **订单超时取消**：下单时将订单放入延迟队列，30分钟后如果订单仍未支付，`take()` 会取出该订单执行取消逻辑。

4. **心跳检测/会话超时**：将连接的最后活跃时间放入延迟队列，到期检查连接是否仍然活跃。

---

## 六、PriorityBlockingQueue 简析

### 6.1 与 DelayQueue 的关系

`DelayQueue` 内部使用 `PriorityQueue` 来排序，按到期时间从小到大排列。`PriorityBlockingQueue` 可以看作是 `PriorityQueue` 的线程安全版本——它同样使用二叉堆排序，但排序的依据不是到期时间，而是元素自身的 `Comparator` 或 `Comparable` 自然顺序。

可以说，`DelayQueue` 是 `PriorityBlockingQueue` 的一个特化版本：把"延迟到期"作为一种特殊的优先级。

### 6.2 核心结构

```
┌──────────────────────────────────────────────┐
│              PriorityBlockingQueue             │
│                                                │
│   ReentrantLock lock  ←── 全局锁（和Array一样）  │
│   Condition notEmpty ←── 非空条件              │
│                                                │
│   Object[] queue      ←── 二叉堆数组            │
│   int size            ←── 元素个数              │
│   Comparator comparator ←── 比较器（可选）       │
│                                                │
│   特点：无界队列（自动扩容）                      │
└──────────────────────────────────────────────┘
```

### 6.3 关键特性

- **无界队列**：`PriorityBlockingQueue` 没有容量上限（初始容量默认11，满后自动扩容）。由于是无界的，`put()` 永远不会阻塞（永远能放进去），`offer()` 永远返回 true。阻塞只发生在 `take()` 上——队列为空时阻塞。
- **二叉堆排序**：底层是一个数组表示的二叉小顶堆（或大顶堆，取决于比较器）。堆顶始终是最小（或最大）的元素。每次 `take()` 取出堆顶元素，然后将末尾元素放到堆顶并下沉（siftDown）调整堆。
- **单锁设计**：和 `ArrayBlockingQueue` 一样用一把 `ReentrantLock`，所有操作互斥。由于是无界队列，不需要 `notFull` 条件（永远不会满）。
- **扩容**：当数组满了需要扩容时，会先释放锁（`lock.unlock()`），分配新数组（避免扩容期间阻塞其他读操作），然后再获取锁进行数据搬移。这是一个巧妙的优化。

### 6.4 take() 方法

```java
public E take() throws InterruptedException {
    final ReentrantLock lock = this.lock;
    lock.lockInterruptibly();
    try {
        try {
            while (queue.length == 0)  // 注意：这里实际检查的是size
                notEmpty.await();       // 队列为空，等待
        } catch (InterruptedException ie) {
            notEmpty.signal(); // 被中断时唤醒其他等待者
            throw ie;
        }
        E x = (E) queue[0];             // 取出堆顶
        if (--size == 0) {              // 取出后队列空了
            queue[0] = null;
        } else {
            E moved = (E) queue[size];  // 末尾元素
            queue[size] = null;
            siftDown(0, moved);         // 末尾元素放到堆顶并下沉调整
        }
        return x;
    } finally {
        lock.unlock();
    }
}
```

核心逻辑：取堆顶 → 末尾元素放堆顶 → `siftDown` 下沉调整。`siftDown` 是经典的二叉堆操作：将堆顶元素与其子节点比较，如果比子节点大（小顶堆），就和较小的子节点交换，重复直到满足堆性质。

---

## 七、LinkedTransferQueue 简析

### 7.1 transfer() 方法的语义

`LinkedTransferQueue` 是 Java 7 引入的，它结合了 `SynchronousQueue` 和 `LinkedBlockingQueue` 的特点。核心方法是 `transfer(E e)`：

```java
/**
 * 将元素传输给消费者。如果没有消费者在等待，则阻塞直到有消费者取走。
 * 和put()的区别：put()放入队列就返回（不管有没有消费者），
 * transfer()必须等到消费者直接取走才返回。
 */
public void transfer(E e) throws InterruptedException;

/**
 * 尝试传输。如果有消费者在等待，立即传输并返回true；否则返回false（不阻塞）。
 */
public boolean tryTransfer(E e);

/**
 * 超时尝试传输。在指定时间内等待消费者，超时返回false。
 */
public boolean tryTransfer(E e, long timeout, TimeUnit unit);
```

### 7.2 与 SynchronousQueue 的区别

| 对比项 | SynchronousQueue | LinkedTransferQueue |
|--------|-----------------|-------------------|
| 数据存储 | 不存储任何元素 | 可以存储元素（作为普通队列使用） |
| 数据结构 | TransferStack/TransferQueue | 链表 + CAS |
| 公平性 | 可选公平/非公平 | FIFO（公平） |
| transfer语义 | put和take本身就是transfer | transfer是独立方法，put/take可选 |
| 容量 | 0 | 无界（可缓冲） |
| 性能 | 高（无存储开销） | 高（CAS无锁实现） |

简单来说：

- `SynchronousQueue` 是纯粹的"手递手"——不存储任何元素，生产者必须等消费者。
- `LinkedTransferQueue` 既可以"手递手"（用 `transfer()`），也可以"缓冲"（用 `put()`/`take()`），更加灵活。
- `LinkedTransferQueue` 性能通常优于 `SynchronousQueue` 和 `LinkedBlockingQueue`，因为它使用了基于 CAS 的无锁算法（大量使用 `Unsafe.compareAndSwapObject`），避免了锁的开销。

### 7.3 底层实现简述

`LinkedTransferQueue` 的核心是一个松弛型双队列（dual queue），节点分为两种状态：

- **DATA 节点**：持有数据，等待消费者。
- **REQUEST 节点**：不持有数据，等待生产者。

当生产者调用 `transfer(e)` 时：
1. 先检查链表头部有没有 REQUEST 节点（等待的消费者），有则直接匹配。
2. 没有，创建一个 DATA 节点加入链表尾部，然后自旋等待消费者来匹配。
3. 如果是 `put(e)`（非 transfer），创建 DATA 节点后不等待，直接返回。

消费者 `take()` 的逻辑对称：先检查有没有 DATA 节点匹配，没有就创建 REQUEST 节点等待。

整个匹配过程通过 CAS 操作完成，没有使用锁，在高并发下性能非常好。

---

## 八、BlockingQueue 与线程池的关系

### 8.1 ThreadPoolExecutor 的 workQueue 参数

`ThreadPoolExecutor` 的构造方法：

```java
public ThreadPoolExecutor(
    int corePoolSize,        // 核心线程数
    int maximumPoolSize,     // 最大线程数
    long keepAliveTime,      // 空闲线程存活时间
    TimeUnit unit,           // 时间单位
    BlockingQueue<Runnable> workQueue,  // ← 工作队列！
    ThreadFactory threadFactory,        // 线程工厂
    RejectedExecutionHandler handler    // 拒绝策略
) { ... }
```

`workQueue` 是线程池的核心组件之一。线程池提交任务后的执行流程如下：

```
提交任务
  │
  ▼
当前线程数 < corePoolSize？─── 是 ──→ 创建核心线程执行任务
  │
  否
  ▼
workQueue.offer(task)成功？─── 是 ──→ 任务入队等待执行
  │
  否
  ▼
当前线程数 < maximumPoolSize？── 是 ──→ 创建非核心线程执行任务
  │
  否
  ▼
执行拒绝策略（RejectedExecutionHandler）
```

**关键点**：`workQueue` 的类型直接决定了线程池的行为，特别是"队列能否offer成功"这一步。

### 8.2 不同队列选型对线程池行为的影响

#### 8.2.1 LinkedBlockingQueue → FixedThreadPool / SingleThreadPool

```java
public static ExecutorService newFixedThreadPool(int nThreads) {
    return new ThreadPoolExecutor(nThreads, nThreads,
                                  0L, TimeUnit.SECONDS,
                                  new LinkedBlockingQueue<Runnable>());
}
```

`LinkedBlockingQueue` 默认是无界的（容量 `Integer.MAX_VALUE`）。这意味着 `workQueue.offer(task)` 永远返回 true，任务永远能入队。

**后果**：

1. 队列永远不会满，所以 `workQueue.offer()` 永远成功。
2. 既然 `offer` 永远成功，就不会走到"当前线程数 < maximumPoolSize"的分支。
3. 所以 **`maximumPoolSize` 完全无效**，线程数永远不会超过 `corePoolSize`。
4. 任务会不断堆积在队列中，如果任务处理速度跟不上提交速度，队列会无限增长，最终导致 **OOM**。

这就是为什么阿里规约禁止使用 `Executors.newFixedThreadPool()`，而要求手动创建 `ThreadPoolExecutor` 并使用有界队列。

#### 8.2.2 SynchronousQueue → CachedThreadPool

```java
public static ExecutorService newCachedThreadPool() {
    return new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                                  60L, TimeUnit.SECONDS,
                                  new SynchronousQueue<Runnable>());
}
```

`SynchronousQueue` 不存储元素，`offer(task)` 只在有空闲线程（正在 `take()` 的线程）时才成功。

**后果**：

1. 提交任务时，如果没有空闲线程，`offer` 失败。
2. `offer` 失败后，走到"当前线程数 < maximumPoolSize"分支，创建新线程。
3. `maximumPoolSize = Integer.MAX_VALUE`，所以几乎总能创建新线程。
4. 空闲线程60秒后被回收。

这种模式适合突发性大量短任务，但如果任务执行时间过长，可能创建大量线程导致系统资源耗尽。

#### 8.2.3 ArrayBlockingQueue → 自定义线程池（推荐）

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,                          // corePoolSize=4
    8,                          // maximumPoolSize=8
    30, TimeUnit.SECONDS,       // 空闲30秒回收
    new ArrayBlockingQueue<>(100), // 有界队列，容量100
    new ThreadFactoryBuilder().setNameFormat("biz-pool-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者自己执行
);
```

`ArrayBlockingQueue` 是有界的，当队列满了 `offer` 才会失败。这样线程池的行为符合预期：

1. 先创建核心线程（4个）。
2. 核心线程满了，任务入队（最多100个）。
3. 队列也满了，创建非核心线程（最多到8个）。
4. 线程数到达8个且队列也满，执行拒绝策略。

**这是生产环境推荐的配置方式**：有界队列 + 合理的最大线程数 + 明确的拒绝策略。

### 8.3 队列选型对拒绝策略的影响

只有当队列满了且线程数达到 `maximumPoolSize` 时才会触发拒绝策略。因此：

- **无界队列（如默认 LinkedBlockingQueue）**：永远不会触发拒绝策略（但可能 OOM）。
- **SynchronousQueue**：没有空闲线程时立即触发创建新线程或拒绝策略。
- **有界队列（如 ArrayBlockingQueue）**：队列满了才触发，可控性最好。

---

## 九、实战总结与选型指南

### 9.1 各队列对比表

| 队列类型 | 底层结构 | 容量 | 锁设计 | 排序 | 公平性 | 典型使用场景 |
|---------|---------|------|--------|------|--------|------------|
| ArrayBlockingQueue | 数组 | 有界 | 单锁（ReentrantLock） | FIFO | 可选 | 有界生产者-消费者、线程池工作队列 |
| LinkedBlockingQueue | 链表 | 可选有界 | 双锁（putLock + takeLock） | FIFO | 非公平 | 高吞吐生产者-消费者、FixedThreadPool |
| SynchronousQueue | TransferStack/Queue | 0 | CAS + LockSupport | 无 | 可选 | CachedThreadPool、直接传递 |
| DelayQueue | PriorityQueue（堆） | 无界 | 单锁 + Leader优化 | 按到期时间 | 非公平 | 延迟任务、定时调度、缓存过期 |
| PriorityBlockingQueue | 二叉堆数组 | 无界 | 单锁 | 按优先级 | 非公平 | 优先级任务处理 |
| LinkedTransferQueue | 链表 | 无界 | CAS无锁 | FIFO | 公平 | 高性能传递/缓冲 |

### 9.2 性能对比要点

1. **吞吐量**：`LinkedTransferQueue` > `LinkedBlockingQueue` > `ArrayBlockingQueue` > `SynchronousQueue`（视场景）。
2. **内存开销**：`ArrayBlockingQueue`（数组，紧凑） < `LinkedBlockingQueue`/`LinkedTransferQueue`（链表，每个节点额外对象开销）。
3. **公平性**：只有 `ArrayBlockingQueue` 和 `SynchronousQueue` 支持公平模式。其他都是非公平。
4. **有无阻塞入队**：`SynchronousQueue` 无容量，入队必须等消费者；其他队列（有界时）满了才阻塞。

### 9.3 常见面试问题总结

**Q1：ArrayBlockingQueue 和 LinkedBlockingQueue 的区别？**

A：主要区别有三点。第一，底层结构不同，Array 是数组，Linked 是链表。第二，锁设计不同，Array 用一把锁管所有操作，Linked 用两把锁分别管入队和出队，所以 Linked 的并发吞吐量更高。第三，容量不同，Array 必须有界，Linked 默认无界（Integer.MAX_VALUE）。选型上，追求高吞吐选 Linked，内存敏感选 Array。

**Q2：为什么 ArrayBlockingQueue 不用双锁？**

A：因为 Array 的入队和出队操作共享 `putIndex`、`takeIndex`、`count` 三个变量，双锁保护这些共享变量逻辑非常复杂。而 LinkedBlockingQueue 的入队只操作 `last` 指针，出队只操作 `head` 指针，天然分离，共享的 `count` 用 `AtomicInteger` 就够了。

**Q3：put 时的 while 循环为什么不能用 if？**

A：两个原因。第一是虚假唤醒，线程可能在没有收到 signal 的情况下从 await 返回。第二是多线程竞争，被唤醒后可能有其他线程抢先修改了队列状态（比如另一个生产者先入队了），所以必须重新检查条件。用 while 可以确保条件满足后才继续执行。

**Q4：SynchronousQueue 和 LinkedTransferQueue 的区别？**

A：SynchronousQueue 不存储任何元素，put 必须等 take。LinkedTransferQueue 既可以像 SynchronousQueue 一样"手递手"（用 transfer 方法），也可以像普通队列一样缓冲（用 put/take 方法）。另外，LinkedTransferQueue 使用 CAS 无锁实现，性能通常更好。

**Q5：DelayQueue 的 Leader-Follower 模式是什么？**

A：当多个消费者等待堆顶元素到期时，第一个到达的线程成为 Leader，负责定时等待堆顶到期。其他线程作为 Follower 无限等待。Leader 到期后取出元素，唤醒一个 Follower 成为新 Leader。这样避免了所有线程同时定时等待、同时被唤醒竞争锁的无谓开销。

**Q6：为什么 Executors.newFixedThreadPool 有 OOM 风险？**

A：因为 FixedThreadPool 使用无界的 LinkedBlockingQueue，任务可以无限堆积。当任务提交速度远超处理速度时，队列中的任务对象越来越多，最终导致内存溢出。解决方案是手动创建 ThreadPoolExecutor，使用有界队列（如 ArrayBlockingQueue）。

**Q7：BlockingQueue 不允许存 null 的原因？**

A：因为 `poll()` 在队列为空时返回 null。如果允许存 null，就无法区分 `poll()` 返回的 null 是"队列空了"还是"取出的元素就是 null"。

**Q8：DelayQueue 的元素如何排序？**

A：DelayQueue 内部使用 PriorityQueue（二叉堆），元素必须实现 `Delayed` 接口的 `compareTo` 方法。通常按到期时间排序，最早到期的在堆顶。取出时检查 `getDelay()` 返回值，小于等于0表示已到期可以取出。

**Q9：线程池中 workQueue 选用有界队列还是无界队列？**

A：生产环境必须用有界队列。无界队列（如默认的 LinkedBlockingQueue）会导致 maximumPoolSize 无效，任务无限堆积最终 OOM。有界队列配合合理的 maximumPoolSize 和拒绝策略（如 CallerRunsPolicy），可以做到背压（backpressure），保护系统不被压垮。

**Q10：ArrayBlockingQueue 的环形数组是怎么实现的？**

A：通过 `putIndex` 和 `takeIndex` 两个指针在数组中循环移动。每次入队后 `putIndex++`，到达数组末尾时回到0（`if (++putIndex == items.length) putIndex = 0`）。出队的 `takeIndex` 同理。这样数组的每个位置都可以被反复使用，不需要搬移元素。

---

> **参考文献**
>
> - JDK 8 / JDK 11 源码 `java.util.concurrent` 包
> - Java 并发编程实战（Java Concurrency in Practice）
> - Doug Lea, "The java.util.concurrent SynchronousQueue: A Formal Performance Analysis"
> - Doug Lea, "A Jolk of LinkedTransferQueue"

---

*本文档持续更新，如有疑问或建议欢迎交流。*
