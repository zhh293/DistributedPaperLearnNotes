# 无锁队列 ConcurrentLinkedQueue 深度解析

---

## 一、无锁编程的理论基础

### 1.1 什么是无锁（Lock-Free）

在并发编程中，我们常说的"锁"是一种**悲观策略**——假设一定会有冲突，所以先把门锁上，做完再开门。而"无锁"则是一种**乐观策略**——假设大概率不会冲突，先大胆操作，如果发现冲突了再重试。

但"无锁"（Lock-Free）并不是"没有任何同步机制"。它只是不使用互斥锁（Mutex/synchronized/ReentrantLock），而是用更底层的原子操作（主要是CAS）来保证数据一致性。

#### 三种非阻塞进度保证

在学术上，非阻塞并发算法有三种进度保证级别，从弱到强分别是：

**1. Obstruction-Free（无阻塞）**

最弱的保证。一个线程在**没有其他线程干扰**的情况下，能在有限步内完成操作。也就是说如果只有一个线程在跑，它一定能完成。但如果多个线程同时跑，可能互相"活锁"（livelock），谁都完不成。

**2. Lock-Free（无锁）**

在任意时刻，**至少有一个线程**能在有限步内完成操作。也就是说整个系统一定在前进，但不保证每个线程都能前进（某些线程可能一直被"抢"而失败重试）。ConcurrentLinkedQueue就是Lock-Free的。

**3. Wait-Free（无等待）**

最强的保证。**每个线程**都能在有限步内完成操作。不论其他线程怎么干扰，自己一定能完成。这种算法通常实现起来非常复杂且开销大，实际中很少使用。

ConcurrentLinkedQueue属于Lock-Free级别：系统整体一直在前进，但某个特定的线程可能因为CAS失败而重试多次。

### 1.2 CAS 操作回顾

CAS（Compare-And-Swap）是无锁编程的基石。它的语义可以用伪代码表示：

```
boolean CAS(内存地址V, 期望值A, 新值B) {
    if (V当前的值 == A) {
        V = B;
        return true;  // 更新成功
    } else {
        return false; // 更新失败，说明被其他线程改过了
    }
}
```

关键点：**上面这整个操作是原子的**，由CPU硬件保证不可被打断。

#### CPU层面的实现

在x86架构上，CAS对应的机器指令是 `CMPXCHG`（Compare and Exchange）。在多核环境下，还需要加上 `LOCK` 前缀来保证跨核的原子性：

```asm
LOCK CMPXCHG [destination], source
```

`LOCK` 前缀的效果是锁住内存总线（或者在现代CPU上锁住缓存行），确保在这条指令执行期间，没有其他核心能修改同一个内存地址。

#### ABA问题

CAS有一个经典问题叫ABA问题：

1. 线程1读到值A
2. 线程2把值从A改成B，再改回A
3. 线程1做CAS，发现值还是A，以为没人动过，CAS成功

在某些场景下（比如链表节点复用），ABA会导致错误。但在ConcurrentLinkedQueue中，**ABA问题不构成威胁**，原因是：

- 每次入队都是**新建一个Node对象**，节点不会被复用
- 即使两个节点的引用"看起来一样"，因为是不同的对象，引用值（内存地址）不同
- 节点出队后会被GC回收，不会再出现在链表中

所以ConcurrentLinkedQueue不需要像AtomicStampedReference那样用版本号来解决ABA。

### 1.3 无锁数据结构的设计哲学

无锁数据结构的设计有三大核心哲学：

**1. 乐观并发（Optimistic Concurrency）**

先操作，失败了再重试。代码模式通常是：
```java
for (;;) {  // 死循环
    // 读取当前状态
    // 计算新状态
    // CAS尝试更新
    if (CAS成功) break;
    // 失败了就继续循环重试
}
```

**2. 协作式设计（Helping/Cooperation）**

如果一个线程发现另一个线程的操作"做了一半"，不是等它做完，而是**帮它做完**。比如在ConcurrentLinkedQueue的offer()中，如果发现tail滞后了（有人入队了但没来得及更新tail），当前线程会帮忙把tail推进。

这种设计保证了Lock-Free的性质：即使某个线程挂了或者很慢，其他线程也能帮它完成操作，系统不会停滞。

**3. 松弛设计（Relaxation/Slack）**

允许某些辅助指针（如head、tail）不精确。比如tail不一定总是指向最后一个节点，可能滞后1-2个节点。这样做的好处是减少CAS竞争次数——不需要每次操作都去争抢同一个指针。代价是需要多走几步遍历来找到真正的位置。

---

## 二、Michael-Scott 无锁队列算法（理论篇）

### 2.1 算法背景

1996年，Maged M. Michael和Michael L. Scott在论文《Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue Algorithms》中提出了这个经典算法。

它之所以成为工业标准，有几个原因：

1. **简单**：核心思想清晰，代码量不大
2. **高效**：在中等竞争程度下性能优异
3. **正确**：经过严格的形式化验证
4. **通用**：适用于多生产者多消费者（MPMC）场景

Java的ConcurrentLinkedQueue、C++的tbb::concurrent_queue、Go的一些无锁队列实现，都参考了这个算法。

### 2.2 算法的数据结构

Michael-Scott队列使用**单向链表**实现：

```
head → [sentinel] → [node1] → [node2] → [node3] → null
                                                    ↑
                                                   tail
```

核心组成部分：

**Node 结构：**
```
Node {
    E item;      // 数据项
    Node next;   // 指向下一个节点
}
```

**哨兵节点（Sentinel/Dummy Node）：**
- 链表的第一个节点是哨兵节点，它的item永远是null
- 为什么需要哨兵？为了统一处理"队列只有一个元素"的边界情况
  - 如果没有哨兵，head和tail指向同一个有效节点时，入队操作修改tail.next和出队操作修改head会冲突
  - 有了哨兵，head指向哨兵（已消费的），tail指向有效节点，两者操作的不是同一个节点

**head指针：** 指向哨兵节点（最近一次出队操作后遗留的"旧节点"）

**tail指针：** 指向链表尾部或接近尾部的节点

### 2.3 入队（Enqueue）算法详解

用通俗的比喻来理解入队：

> 想象你在排队买奶茶，队伍没有围栏，大家靠自觉站在最后面。你到了以后，需要做两件事：
> 1. 站到最后一个人后面（把自己链接上去）
> 2. 让指引牌指向你（更新tail）
>
> 但如果同时来了好几个人，大家都想站到"最后面"，就需要CAS来决定谁先站上去。

伪代码逐步讲解：

```
Enqueue(x):
    node = new Node(x, null)        // 1. 创建新节点，next为null
    loop:
        tail = this.tail            // 2. 读取当前tail指针
        next = tail.next            // 3. 读取tail的next

        if tail == this.tail:       // 4. 确认tail没被改过（双重检查）
            if next == null:        // 5. tail确实是最后一个节点
                if CAS(tail.next, null, node):  // 6. 尝试把新节点挂上去
                    CAS(this.tail, tail, node)  // 7. 尝试推进tail（可能失败）
                    return                      // 入队成功
            else:                   // tail不是最后一个节点（滞后了）
                CAS(this.tail, tail, next)  // 8. 帮助推进tail
        // CAS失败或tail被改了，重新循环
```

**关键理解：**

第6步和第7步是两个独立的CAS，不是原子的。这意味着可能出现：

- 第6步成功（节点已经链接到队尾）
- 第7步还没执行，或者执行失败（tail没有更新）

这会导致tail滞后，但**不影响正确性**！因为：
- 队列的真正尾部由"最后一个next==null的节点"决定
- tail只是一个"快速定位的提示"
- 其他线程入队时，如果发现tail.next != null，会帮忙推进tail（第8步）

**为什么不能把第6步和第7步合成一步？**

因为CAS只能原子地更新一个内存位置。我们需要更新两个东西：tail.next（链接新节点）和this.tail（推进tail指针），这两个在不同的内存位置，一个CAS搞不定。

### 2.4 出队（Dequeue）算法详解

通俗比喻：

> 还是奶茶店，你是店员要叫号。你看指引牌（head）找到队伍最前面的人。但因为有哨兵（一个已经买完的人还站在最前面当参照物），你实际上要看head后面那个人。

伪代码：

```
Dequeue():
    loop:
        head = this.head            // 1. 读取当前head
        tail = this.tail            // 2. 读取当前tail
        first = head.next           // 3. head后面的第一个有效节点

        if head == this.head:       // 4. 确认head没被改过
            if head == tail:        // 5. head和tail相同
                if first == null:   // 6. 队列真的为空
                    return null
                CAS(this.tail, tail, first)  // 7. tail滞后了，帮忙推进
            else:
                value = first.item  // 8. 读取数据
                if CAS(this.head, head, first):  // 9. CAS推进head
                    return value    // 出队成功
        // 失败重试
```

**哨兵节点在出队中的关键作用：**

当队列只有一个元素时，链表结构是：
```
head → [sentinel] → [唯一元素] → null
                         ↑
                        tail
```

如果没有哨兵，head和tail都指向[唯一元素]。此时：
- 入队要修改tail.next
- 出队要移除head指向的节点

这两者冲突了。有了哨兵：
- head指向sentinel
- 出队操作是CAS推进head到下一个节点（head变成指向[唯一元素]，原来的sentinel被抛弃）
- 入队操作是CAS修改tail.next

两者操作的不是同一个节点，冲突大大减少。

### 2.5 正确性分析——线性化点

**线性化点（Linearization Point）** 是判断并发数据结构正确性的核心概念。它指的是：一个操作在哪个时刻"生效"了？

对于ConcurrentLinkedQueue：

- **入队的线性化点**：CAS成功将新节点链接到tail.next的那一刻（第6步CAS成功）。从这个时刻起，新元素在逻辑上已经加入了队列，任何后续的出队操作都可能看到它。

- **出队的线性化点**：CAS成功推进head的那一刻（第9步CAS成功）。从这个时刻起，该元素在逻辑上已经离开队列。

线性化点保证了FIFO语义：先入队的元素一定先出队。

---

## 三、ConcurrentLinkedQueue 源码深度解析（JDK 8 实现）

### 3.1 类结构与核心字段

```java
public class ConcurrentLinkedQueue<E> extends AbstractQueue<E>
        implements Queue<E>, java.io.Serializable {

    /**
     * head总是指向一个"已经消费过的"或"正在消费的"节点
     * 不变式：head.item可能为null（哨兵），head.next不为null（除非队列空）
     */
    private transient volatile Node<E> head;

    /**
     * tail指向最后一个节点或者倒数第二个节点（松弛设计）
     * 不变式：从tail出发，最多走几步就能到达真正的尾节点
     */
    private transient volatile Node<E> tail;
}
```

重点解读：

1. **transient**：序列化时不直接序列化这两个字段（有自定义的序列化逻辑）
2. **volatile**：保证多线程间的可见性。任何线程对head/tail的写入，其他线程立即可见
3. head和tail不是"精确的"——这是松弛设计的核心

### 3.2 Node 内部类源码分析

```java
private static class Node<E> {
    volatile E item;        // 数据项，volatile保证可见性
    volatile Node<E> next;  // 下一个节点的引用

    /**
     * 构造一个新节点
     */
    Node(E item) {
        // 使用UNSAFE直接写入，绕过volatile的写屏障
        // 因为构造方法中对象还没发布出去，不需要volatile语义
        UNSAFE.putObject(this, itemOffset, item);
    }

    /**
     * CAS更新item字段
     * 用于出队时将item从有效值置为null（逻辑删除）
     */
    boolean casItem(E cmp, E val) {
        return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
    }

    /**
     * 延迟设置next字段
     * 用于自链接（GC辅助），不需要volatile写的开销
     */
    void lazySetNext(Node<E> val) {
        UNSAFE.putOrderedObject(this, nextOffset, val);
    }

    /**
     * CAS更新next字段
     * 用于入队时将null替换为新节点
     */
    boolean casNext(Node<E> cmp, Node<E> val) {
        return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
    }

    // Unsafe相关的偏移量
    private static final sun.misc.Unsafe UNSAFE;
    private static final long itemOffset;
    private static final long nextOffset;

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = Node.class;
            itemOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("item"));
            nextOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("next"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
```

逐个方法解析：

**构造方法中为什么用 putObject 而不是直接赋值？**

直接赋值 `this.item = item` 对于volatile字段会插入StoreStore和StoreLoad屏障，开销较大。但在构造方法中，对象还没发布给其他线程，不需要这些屏障。用 `UNSAFE.putObject` 可以绕过volatile语义，直接写入普通内存，性能更好。

**casItem(E cmp, E val)**

CAS更新item字段。主要用途：
- 出队时，将item从有效值CAS为null（标记这个节点已被消费）
- remove()时，将item从有效值CAS为null（逻辑删除）

**lazySetNext(Node<E> val)**

`putOrderedObject` 只保证StoreStore屏障（前面的写操作不会被重排到这个写之后），但不保证StoreLoad屏障（这个写不保证对其他线程立即可见）。

用于自链接场景：`node.lazySetNext(node)`。自链接只是为了帮助GC，不影响算法正确性，所以用延迟写就够了，省一个StoreLoad屏障的开销。

**casNext(Node<E> cmp, Node<E> val)**

CAS更新next字段。主要用途：
- 入队时，将尾节点的next从null CAS为新节点

### 3.3 构造方法

```java
public ConcurrentLinkedQueue() {
    // 创建一个item为null的哨兵节点
    // head和tail都指向这个哨兵
    head = tail = new Node<E>(null);
}
```

初始状态的内存结构：

```
head ──→ [item=null, next=null] ←── tail
          (哨兵节点)
```

这个状态表示队列为空。判断队列空的条件不是 `head == tail`（因为松弛设计下head可能滞后），而是从head出发找不到item不为null的节点。

### 3.4 offer() 方法源码逐行深度分析（入队）

这是ConcurrentLinkedQueue最核心、最精妙的方法。让我们逐行拆解：

```java
public boolean offer(E e) {
    // 空值检查，ConcurrentLinkedQueue不允许null元素
    // 因为null被用作"已消费"的标记
    checkNotNull(e);

    // 创建新节点
    final Node<E> newNode = new Node<E>(e);

    // 从tail开始，t是tail的快照，p是当前探索位置
    for (Node<E> t = tail, p = t;;) {
        // q是p的下一个节点
        Node<E> q = p.next;

        if (q == null) {
            // ========================
            // 情况1：p是最后一个节点（p.next == null）
            // 尝试将新节点挂到p的后面
            // ========================
            if (p.casNext(null, newNode)) {
                // CAS成功！新节点已经链接到队尾了
                // 接下来判断是否需要更新tail
                if (p != t)
                    // p != t 说明我们从tail出发走了至少一步
                    // 也就是说tail已经滞后了（至少2个节点）
                    // 尝试更新tail指向新节点
                    // 注意：这个CAS可能失败（被其他线程更新了），但不影响正确性
                    casTail(t, newNode);
                return true;
            }
            // CAS失败，说明有其他线程抢先在p后面插入了节点
            // 继续循环，下次循环时q = p.next不再是null
        }
        else if (p == q) {
            // ========================
            // 情况2：p.next == p，即自链接
            // 说明p这个节点已经被出队并自链接了
            // 我们正在一个"已经脱离队列的"废弃节点上
            // 必须重新从head或tail开始
            // ========================
            p = (t != (t = tail)) ? t : head;
            // 解释这个表达式：
            // t != (t = tail)：先把tail的最新值赋给t，然后比较新旧t
            // 如果tail被更新过（说明其他线程做了入队操作）：
            //     使用新的tail作为起点（更接近队尾）
            // 如果tail没变：
            //     使用head作为起点（从头遍历，保证能找到活跃节点）
        }
        else {
            // ========================
            // 情况3：q != null 且 p != q
            // 说明p不是最后一个节点，需要继续向后走
            // ========================
            p = (p != t && t != (t = tail)) ? t : q;
            // 解释这个表达式：
            // 条件 (p != t && t != (t = tail))：
            //   p != t：我们已经从tail向后走了至少一步
            //   t != (t = tail)：在我们遍历的过程中，tail被其他线程更新了
            // 如果两者都满足：
            //     说明其他线程更新了tail，直接跳到新的tail（更接近尾部）
            // 否则：
            //     继续沿着链表往后走（p = q）
        }
    }
}
```

#### offer() 的执行流程图

让我们用几个场景来理解offer()的执行过程：

**场景1：简单入队（无竞争）**

初始状态：`head → [sentinel] → [A] → null`，tail指向[A]

线程1调用offer(B)：
1. t = tail = [A], p = [A]
2. q = p.next = null（进入情况1）
3. CAS [A].next: null → [B]，成功
4. p == t（都是[A]），不更新tail
5. 返回true

结果：`head → [sentinel] → [A] → [B] → null`，tail仍指向[A]（滞后了1个节点）

**场景2：tail滞后后的入队**

继续上面的状态，线程2调用offer(C)：
1. t = tail = [A], p = [A]
2. q = p.next = [B]（不是null，进入情况3）
3. p != t? 不是（p==t==[A]），所以p = q = [B]
4. 回到循环顶部，q = [B].next = null（进入情况1）
5. CAS [B].next: null → [C]，成功
6. p != t（p是[B]，t是[A]），尝试CAS更新tail指向[C]
7. 返回true

结果：`head → [sentinel] → [A] → [B] → [C] → null`，tail指向[C]

#### 松弛策略的精妙之处

注意上面的场景：
- 场景1入队后tail滞后1个节点，没有CAS更新tail
- 场景2入队后发现tail滞后了2个节点（p != t），才CAS更新tail

这就是**松弛策略**：tail不是每次入队都更新，而是**滞后1个节点时不管，滞后2个节点时才更新**。

为什么这样做？

- CAS更新tail是一个**高竞争操作**（所有入队线程都可能尝试）
- 如果每次入队都CAS tail，竞争激烈，大量CAS失败
- 松弛后，平均每两次入队才CAS一次tail，竞争减半
- 代价只是多走一步遍历（从tail找到真正的尾部），这个代价很小

### 3.5 poll() 方法源码逐行深度分析（出队）

```java
public E poll() {
    restartFromHead:
    for (;;) {
        // h是head的快照，p是当前探索位置
        for (Node<E> h = head, p = h, q;;) {

            // 读取当前节点的item
            E item = p.item;

            // 情况1：找到了一个有效节点（item不为null）
            if (item != null && p.casItem(item, null)) {
                // CAS成功，将item置为null（逻辑删除）
                // item被置null后，这个节点在逻辑上已经被消费了

                if (p != h) {
                    // p != h 说明我们从head走了至少一步
                    // head滞后了，尝试更新head
                    updateHead(h, ((q = p.next) != null) ? q : p);
                    // 如果p后面还有节点，head指向p的下一个
                    // 如果p后面没节点了，head指向p本身
                }
                return item; // 返回出队的数据
            }

            // 情况2：p.next为null，队列中没有更多有效节点了
            else if ((q = p.next) == null) {
                updateHead(h, p); // 更新head到p
                return null;      // 队列为空
            }

            // 情况3：遇到自链接节点
            else if (p == q) {
                // 从head重新开始
                continue restartFromHead;
            }

            // 情况4：当前节点item为null（已被消费），继续往后找
            else {
                p = q; // 移动到下一个节点
            }
        }
    }
}
```

**updateHead方法**：

```java
final void updateHead(Node<E> h, Node<E> p) {
    // 如果h和p不同，且CAS成功将head从h更新为p
    if (h != p && casHead(h, p)) {
        // 将旧的head节点自链接（h.next = h）
        h.lazySetNext(h);
    }
}
```

这里的自链接是poll()操作的精髓之一。让我们详细理解：

**场景：正常出队**

状态：`head → [sentinel(item=null)] → [A(item=a)] → [B(item=b)] → null`

线程1调用poll()：
1. h = head = [sentinel], p = [sentinel]
2. item = p.item = null（sentinel的item是null，跳过情况1）
3. q = p.next = [A]（不为null，跳过情况2）
4. p != q（跳过情况3）
5. 进入情况4：p = q = [A]
6. 回到循环，item = [A].item = a（不为null）
7. CAS [A].item: a → null，成功（逻辑删除）
8. p != h（p是[A]，h是[sentinel]），调用updateHead
9. updateHead(sentinel, [B])：CAS head从[sentinel]变为[B]，成功
10. sentinel.lazySetNext(sentinel)：哨兵自链接

结果：`head → [B(item=b)] → null`，原来的sentinel和A节点被孤立

**等等，[A]节点去哪了？**

注意步骤7中，[A]的item被置为null了。然后步骤9中head直接跳到了[B]。也就是说[A]变成了新的"哨兵"——它的item为null，且head跳过了它。但实际上，在updateHead中传入的是 `((q = p.next) != null) ? q : p`，也就是如果[A]后面有节点（[B]），head直接指向[B]。这样[A]也被跳过了，会被GC回收。

### 3.6 peek() 方法源码分析

```java
public E peek() {
    restartFromHead:
    for (;;) {
        for (Node<E> h = head, p = h, q;;) {
            E item = p.item;
            if (item != null || (q = p.next) == null) {
                // 找到有效节点或者队列为空
                updateHead(h, p); // 顺便更新head（如果滞后了）
                return item;      // 返回item（可能为null表示空）
            }
            else if (p == q) {
                continue restartFromHead; // 自链接，重新开始
            }
            else {
                p = q; // 继续往后找
            }
        }
    }
}
```

peek和poll的区别：
- poll会 `casItem(item, null)` 将item清除
- peek只读取item，不修改任何东西
- 但peek也会顺便 `updateHead`——这是一个"搭便车"的优化，帮助推进head

### 3.7 size() 方法分析

```java
public int size() {
    int count = 0;
    // 从第一个有效节点开始遍历
    for (Node<E> p = first(); p != null; p = succ(p))
        if (p.item != null)
            // 最大返回Integer.MAX_VALUE
            if (++count == Integer.MAX_VALUE)
                break;
    return count;
}
```

**为什么size()不精确？**

1. 遍历过程中没有加锁，其他线程可能同时在入队/出队
2. 你正在数第5个节点的时候，前面3个可能已经被出队了
3. 你还没数完的时候，后面可能又入队了新节点
4. 所以返回的只是一个"近似快照"

**性能问题：**
- 时间复杂度O(n)，需要遍历整个链表
- 在高并发场景下，链表可能很长，size()代价很高
- **最佳实践**：不要在性能敏感的代码中频繁调用size()
- 判断空用isEmpty()更好（只需看第一个节点）

### 3.8 contains() / remove() 方法分析

```java
public boolean contains(Object o) {
    if (o == null) return false;
    for (Node<E> p = first(); p != null; p = succ(p)) {
        E item = p.item;
        if (item != null && o.equals(item))
            return true;
    }
    return false;
}

public boolean remove(Object o) {
    if (o == null) return false;
    Node<E> pred = null, p;
    for (p = first(); p != null; p = succ(p)) {
        E item = p.item;
        if (item != null &&
            o.equals(item) &&
            p.casItem(item, null)) {  // CAS逻辑删除
            // 物理解链的处理...
            return true;
        }
    }
    return false;
}
```

remove()的策略：
1. **逻辑删除**：CAS将item置为null
2. **物理解链**：延迟处理，由后续的offer()/poll()操作在遍历时自然跳过

这种"逻辑删除+延迟物理解链"的模式在无锁数据结构中很常见。好处是remove()操作本身只需要一个CAS（对item），简单高效。

---

## 四、松弛设计（Slack）的深度理解

### 4.1 为什么需要松弛

想象一个极端场景：100个线程同时向队列入队。如果每次入队都要CAS更新tail：

```
线程1: CAS(tail, oldTail, myNode) → 成功
线程2: CAS(tail, oldTail, myNode) → 失败！重试...
线程3: CAS(tail, oldTail, myNode) → 失败！重试...
...
线程100: CAS(tail, oldTail, myNode) → 失败！重试...
```

99个线程的CAS都失败了！这就是**热点竞争**问题。

松弛的核心思想是：**减少对tail/head这个热点的CAS次数**。

具体做法：允许tail滞后1-2个节点。这样平均每2次入队才需要CAS一次tail，CAS竞争减少了一半。

### 4.2 松弛度的控制

在JDK实现中，松弛度约为1个节点（也就是tail最多滞后1个节点，滞后2个时才更新）。

控制逻辑在offer()中：

```java
if (p != t)  // p是真正的尾节点，t是tail的值
    casTail(t, newNode);  // 只有p != t时才尝试更新tail
```

p != t意味着我们从tail出发至少走了一步才找到真正的尾部。也就是说tail至少滞后了1个节点（现在加上新入队的节点就是2个）。此时才尝试CAS更新tail。

类似地，在poll()中：

```java
if (p != h)  // p是真正消费的节点，h是head的值
    updateHead(h, ...);  // 只有p != h时才尝试更新head
```

### 4.3 松弛为什么不影响正确性

这是理解ConcurrentLinkedQueue的关键洞察：

**tail不是"真相"（truth），只是"提示"（hint）。**

- **真相**：队列的真正尾部是"从head出发，沿着next链走到的最后一个next==null的节点"
- **提示**：tail只是一个快速定位的起点，可能指向真正尾部，也可能指向倒数第二个节点

同理：
- **真相**：队列的真正头部是"从head出发，第一个item不为null的节点"
- **提示**：head只是一个起点

任何操作都是先通过提示（tail/head）快速定位，再通过遍历找到"真相"。所以提示不精确只影响遍历步数（性能），不影响正确性。

### 4.4 松弛度的权衡

松弛度越大（允许滞后越多）：
- 优点：CAS tail/head的次数越少，竞争越小
- 缺点：每次操作需要遍历的步数越多

松弛度越小（接近总是精确）：
- 优点：定位快，不需要额外遍历
- 缺点：CAS竞争激烈

JDK选择了松弛度≈1，这是一个经验值，在大多数场景下表现良好。

---

## 五、自链接（Self-Link）与GC辅助

### 5.1 什么是自链接

当一个节点被出队后，它的next会被设置为指向自己：

```java
// updateHead方法中
h.lazySetNext(h);  // 让h.next指向h自己
```

在内存中就是：
```
[已出队节点] ──next──→ [自己]（循环引用）
```

### 5.2 为什么需要自链接

考虑这个场景：

```
原始状态：
head → [A] → [B] → [C] → null
```

[A]出队后，如果不做自链接：
```
       [A] → [B] → [C] → null
              ↑
             head（已推进到B）
```

问题来了：如果有一个局部变量还持有对[A]的引用（比如一个迭代器在遍历），那么从[A]出发，仍然可以通过next链到达[B]、[C]。这意味着：

1. [A]不能被GC（有引用指向它）
2. 通过[A]可以到达[B]和[C]，所以如果[B]也出队了，[B]也不能被GC（因为从[A]可达）

这就造成了**内存泄漏**——已经出队的节点因为链表的连接关系而无法被回收。

自链接的解决方案：
```
       [A] → [A]（自链接，断开了与B的连接）
              
head → [B] → [C] → null
```

现在，即使有引用指向[A]，从[A]出发也走不到[B]了。[A]变成了一个孤岛。一旦持有[A]的引用被释放，[A]就能被GC回收。

### 5.3 自链接的检测

在offer()和poll()的遍历过程中，如果遇到自链接节点（p == q，即p.next == p），说明我们正站在一个已经被出队的"废弃节点"上。

处理方式：**放弃当前遍历，从head或tail重新开始**。

```java
// offer()中
else if (p == q) {
    p = (t != (t = tail)) ? t : head;
}

// poll()中
else if (p == q) {
    continue restartFromHead;
}
```

### 5.4 lazySetNext vs casNext

自链接使用 `lazySetNext` 而不是 `casNext`，因为：

1. **lazySetNext（putOrderedObject）**：
   - 只插入StoreStore屏障
   - 保证这个写操作不会被CPU重排到之前的写操作前面
   - 但不保证其他线程立即看到这个写入（没有StoreLoad屏障）
   - 开销小

2. **casNext（compareAndSwapObject）**：
   - 完整的CAS操作，带内存屏障
   - 开销大

为什么lazySetNext足够？

- 自链接只是GC辅助，不影响算法正确性
- 即使其他线程暂时看不到自链接（看到的是旧的next值），也只是多走一步遍历
- 最终（eventually）所有线程都会看到自链接
- 当发现p == q时才需要处理，此时一定已经看到了自链接

### 5.5 自链接与迭代器

ConcurrentLinkedQueue的迭代器是**弱一致性**的（weakly consistent）。迭代器在遍历时如果遇到自链接节点，会从head重新开始寻找下一个有效节点：

```java
// Itr.advance()中的逻辑
private Node<E> succ(Node<E> p) {
    Node<E> next = p.next;
    if (p == next)
        // 遇到自链接，从head开始找
        next = head;
    return next;
}
```

---

## 六、ConcurrentLinkedDeque 简析（无锁双端队列）

### 6.1 与ConcurrentLinkedQueue的区别

ConcurrentLinkedDeque是双端队列（Deque），支持从两端入队和出队：

```java
public class ConcurrentLinkedDeque<E> extends AbstractCollection<E>
        implements Deque<E>, java.io.Serializable {

    private transient volatile Node<E> head;
    private transient volatile Node<E> tail;

    static final class Node<E> {
        volatile Node<E> prev;  // 前驱（比CLQ多了这个）
        volatile E item;
        volatile Node<E> next;  // 后继
    }
}
```

核心差异：

| 特性 | ConcurrentLinkedQueue | ConcurrentLinkedDeque |
|------|----------------------|----------------------|
| 链表类型 | 单向链表 | 双向链表 |
| 操作端 | 尾入头出 | 两端都可入出 |
| Node字段 | item + next | prev + item + next |
| 复杂度 | 较低 | 显著更高 |

### 6.2 核心设计挑战

单向链表的无锁操作只需要CAS一个指针（next），但双向链表需要同时维护prev和next两个方向。CAS只能原子更新一个内存位置，所以不可能一次CAS同时更新两个指针。

解决方案——**三阶段删除**：

1. **逻辑删除**：CAS将item置为null（和CLQ一样）
2. **解链（Unlinking）**：分步骤断开prev和next连接
3. **GC辅助**：让被删节点不再可达

具体来说，一个节点从活跃到完全移除会经历三种状态：
- **活跃（Active）**：item不为null，正常参与队列操作
- **逻辑删除（Logically Deleted）**：item为null，但prev和next仍然连接着
- **已解链（Unlinked）**：prev和next都断开（或指向特殊标记），彻底从链表移除

### 6.3 适用场景

1. **工作窃取（Work-Stealing）**：
   - 每个线程有自己的双端队列
   - 正常情况从队尾push/pop自己的任务
   - 空闲时从其他线程的队头steal任务
   - ForkJoinPool底层就是这个思想（虽然实现用的是数组而非链表）

2. **需要从两端操作的并发场景**：
   - 既需要FIFO又需要LIFO
   - 实现并发的滑动窗口

3. **实际中的建议**：
   - 如果只需要单端队列，用ConcurrentLinkedQueue（更简单高效）
   - 只有确实需要双端操作时才用ConcurrentLinkedDeque

---

## 七、无锁队列 vs 阻塞队列（BlockingQueue）对比

### 7.1 设计哲学对比

| 维度 | ConcurrentLinkedQueue | LinkedBlockingQueue |
|------|----------------------|---------------------|
| 同步机制 | CAS无锁 | ReentrantLock互斥锁 |
| 阻塞能力 | 不支持阻塞等待 | 支持put()/take()阻塞 |
| 容量 | 无界（直到OOM） | 可选有界（默认Integer.MAX_VALUE） |
| 生产者-消费者 | 不直接适用 | 天然适合 |
| 锁粒度 | 无锁 | 头锁 + 尾锁（两把锁分离） |
| 低竞争吞吐 | 非常高 | 稍低（锁的固有开销） |
| 高竞争吞吐 | 高（CAS重试） | 中等（锁等待队列） |
| 线程park | 不会park线程 | 会park等待的线程 |
| CPU使用 | CAS失败时短暂自旋 | 等待时让出CPU |
| 内存开销 | 每个节点只有item+next | 节点+锁+条件变量 |
| 公平性 | 不保证（CAS谁抢到谁得） | 可配置公平/非公平 |

### 7.2 什么时候用无锁队列

适合的场景：

1. **不需要阻塞等待**：消费者可以接受poll()返回null，自己决定怎么处理（自旋、做别的事、sleep等）

2. **高吞吐、低延迟**：比如日志框架的异步日志缓冲、消息中间件的本地缓冲

3. **多生产者多消费者的中间缓冲**：多个线程往里扔消息，多个线程从里面取消息

4. **线程池的任务传递**：如Netty的EventLoop内部用MpscQueue

5. **短暂的任务暂存**：消费速度总是大于等于生产速度的场景

### 7.3 什么时候用阻塞队列

适合的场景：

1. **需要背压（Back-Pressure）控制**：生产者速度可能远大于消费者，需要有界队列来限速。当队列满时，put()阻塞生产者，避免OOM。

2. **标准的生产者-消费者模式**：消费者空闲时应该阻塞等待（let out CPU），而不是忙等（busy-wait）。

3. **需要有界容量限制**：严格控制内存使用量。

4. **消费者较少或单一**：如果只有一个消费者，用阻塞队列更自然（take()拿不到就等）。

### 7.4 混合方案

实际项目中，经常会组合使用：

```java
// Netty的方案：MpscQueue（无锁） + EventLoop（单消费者自旋+park）
// 消费者自己控制：先自旋若干次，还没数据就park
for (;;) {
    Runnable task = queue.poll();
    if (task != null) {
        task.run();
    } else {
        // 短暂自旋
        for (int i = 0; i < SPIN_COUNT; i++) {
            task = queue.poll();
            if (task != null) break;
        }
        if (task == null) {
            // 自旋也没拿到，park让出CPU
            LockSupport.park();
        }
    }
}
```

---

## 八、无锁队列在框架中的应用

### 8.1 Netty的MpscQueue

Netty的EventLoop使用的是**多生产者单消费者（MPSC）**队列，来自JCTools库：

```java
// Netty中EventLoop的任务队列
Queue<Runnable> taskQueue = PlatformDependent.newMpscQueue();
```

**为什么用MPSC而不是ConcurrentLinkedQueue？**

1. **场景特点**：EventLoop是单线程消费（只有EventLoop线程从队列取任务），但多个业务线程会提交任务（多生产者）

2. **MPSC的优化**：
   - 消费端不需要CAS！因为只有一个线程消费，直接读写就行
   - 只有生产端需要CAS（多线程竞争入队）
   - 这比ConcurrentLinkedQueue（生产端和消费端都CAS）少了一半的原子操作

3. **JCTools提供的MPSC实现**：
   - `MpscLinkedQueue`：链表实现，无界
   - `MpscArrayQueue`：数组实现，有界，缓存友好
   - `MpscChunkedArrayQueue`：分块数组，可增长

### 8.2 Disruptor的RingBuffer

LMAX Disruptor提供了另一种完全不同的无锁队列思路：

```
传统链表队列：           Disruptor RingBuffer：
Node → Node → Node     [slot0][slot1][slot2][slot3][slot4]...
每次入队new Node        预分配数组，通过序号访问
有GC压力               零GC（数组槽位复用）
缓存不友好             缓存友好（连续内存）
```

核心思想：
- 预分配一个大数组作为环形缓冲区
- 用一个递增的序号（Sequence）表示当前写到哪里、读到哪里
- 生产者CAS递增生产序号来"占位"
- 消费者只需等待生产序号前进到自己的位置

对比：
| 特性 | ConcurrentLinkedQueue | Disruptor RingBuffer |
|------|----------------------|---------------------|
| 数据结构 | 链表 | 环形数组 |
| 有界/无界 | 无界 | 有界（数组大小固定） |
| GC | 每次入队new Node | 零GC（预分配） |
| 缓存友好 | 差（节点分散在堆中） | 好（连续内存） |
| 适用吞吐 | 中高 | 极高（百万/秒级） |

### 8.3 ForkJoinPool的WorkQueue

ForkJoinPool内部每个工作线程有一个WorkQueue（基于数组的双端队列）：

```java
// ForkJoinPool.WorkQueue (简化)
static final class WorkQueue {
    ForkJoinTask<?>[] array; // 任务数组
    int base;               // 队头索引（其他线程steal时操作）
    int top;                // 队尾索引（owner线程push/pop时操作）
}
```

工作窃取的无锁设计：
- **owner线程**：从top端push/pop，单线程操作，不需要CAS
- **其他线程steal**：从base端取任务，需要CAS（因为可能多个线程同时steal）
- base和top在不同端，owner和stealer操作的位置不同，大部分时候不冲突

### 8.4 Reactor/RxJava中的无锁队列

响应式编程框架中大量使用无锁队列：

1. **SpscLinkedArrayQueue**（Single-Producer Single-Consumer）
   - 单生产者单消费者，生产端和消费端都不需要CAS
   - 吞吐量最高的队列类型

2. **MpscLinkedQueue**
   - 用于多个订阅者向同一个调度器提交任务

3. **为什么响应式框架偏爱无锁队列？**
   - 响应式编程的背压机制要求低延迟的数据传递
   - 事件驱动模型中，通常有明确的生产者-消费者角色
   - 可以根据具体场景选择SPSC/MPSC/MPMC获得最佳性能

---

## 九、无锁队列的性能分析与调优

### 9.1 性能特征

**优势：**

1. **无锁竞争时几乎零开销**：没有线程需要park/unpark，没有内核态切换
2. **不会阻塞线程**：线程永远不会被挂起，只是CAS失败后重试
3. **不会死锁**：没有锁，就不可能死锁
4. **不会优先级反转**：低优先级线程持有锁导致高优先级线程等待的情况不会发生

**劣势：**

1. **高竞争时CAS空转**：多个线程反复CAS失败，白白消耗CPU
2. **缓存行颠簸**：多核争抢同一个缓存行（head/tail），导致频繁的缓存失效
3. **不可预测的延迟**：某个线程可能因为运气不好，CAS失败很多次
4. **调试困难**：无锁代码的bug比锁代码更难复现和定位

### 9.2 缓存行的影响

现代CPU的缓存行（Cache Line）通常是64字节。当两个变量在同一个缓存行中，一个核心修改了其中一个变量，另一个核心即使只访问另一个变量，也需要重新从内存加载整个缓存行。这就是**伪共享（False Sharing）**。

在ConcurrentLinkedQueue中：
- head被出队线程频繁修改
- tail被入队线程频繁修改
- 如果head和tail在同一个缓存行中，入队和出队会互相干扰

JDK 8中ConcurrentLinkedQueue的head和tail是在类中连续声明的：

```java
private transient volatile Node<E> head;
private transient volatile Node<E> tail;
```

由于Java对象头（Mark Word + Klass Pointer）通常16字节，加上head引用8字节，tail紧跟其后，它们很可能在同一个缓存行。

在JDK后续版本中（如JDK 9+），通过`@jdk.internal.vm.annotation.Contended`注解或手动padding来解决：

```java
// 概念上的padding方案
private transient volatile Node<E> head;
private long p1, p2, p3, p4, p5, p6, p7; // 56字节padding
private transient volatile Node<E> tail;
```

### 9.3 适用线程数分析

根据经验和benchmark结论：

**2-4个线程**：无锁队列优势明显
- CAS成功率高
- 比加锁方案少了锁获取/释放的开销

**8-16个线程**：无锁队列仍然占优
- CAS失败率开始上升
- 但整体吞吐仍优于锁方案

**32-64个线程**：竞争激烈
- CAS失败率显著上升
- 缓存行颠簸严重
- 无锁队列优势减小，某些场景下锁方案可能更好

**128+个线程**：超高竞争
- 需要考虑分段队列（如ConcurrentLinkedQueue + 分片）
- 或者combining技术（多个线程的操作合并由一个线程执行）
- 或者使用per-thread队列 + 工作窃取

### 9.4 JMH基准测试的建议

如果你想测试ConcurrentLinkedQueue的性能：

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Group)
public class QueueBenchmark {

    private ConcurrentLinkedQueue<Integer> clq = new ConcurrentLinkedQueue<>();
    private LinkedBlockingQueue<Integer> lbq = new LinkedBlockingQueue<>();

    @Benchmark
    @Group("clq")
    @GroupThreads(4)  // 4个生产者
    public void clqOffer() {
        clq.offer(1);
    }

    @Benchmark
    @Group("clq")
    @GroupThreads(4)  // 4个消费者
    public Integer clqPoll() {
        return clq.poll();
    }
}
```

---

## 十、无锁队列的常见陷阱与注意事项

### 10.1 size()不可靠

```java
// ❌ 错误用法：用size()判断空
if (queue.size() == 0) { ... }

// ✅ 正确用法：用isEmpty()
if (queue.isEmpty()) { ... }
```

为什么isEmpty()更好？
- isEmpty()只需检查第一个有效节点是否存在，O(1)时间
- size()需要遍历整个链表，O(n)时间
- 而且size()在遍历过程中链表可能变化，结果不精确

### 10.2 批量操作不原子

```java
// ❌ 这不是原子操作
queue.addAll(Arrays.asList(1, 2, 3));
// 其他线程可能在1入队之后、2入队之前进行poll

// 如果需要原子批量操作，需要额外同步
synchronized(lock) {
    queue.addAll(items);
}
```

containsAll()、removeAll()同理。如果需要原子性保证，ConcurrentLinkedQueue不是正确选择——考虑使用锁或者其他方案。

### 10.3 内存泄漏风险

**风险1：队列无界，生产者速度 > 消费者速度**

```java
// 生产者不断入队
while (true) {
    queue.offer(generateData());
}

// 消费者处理慢
while (true) {
    Data data = queue.poll();
    if (data != null) {
        slowProcess(data);  // 处理慢
    }
}
```

后果：队列无限增长 → OOM

解决：在业务层加限速/背压，或者用有界队列

**风险2：迭代器引用导致GC无法回收**

```java
// 创建迭代器后不及时释放
Iterator<Integer> it = queue.iterator();
// 长时间持有it，期间队列出队的节点可能因为迭代器的引用链而无法被GC
```

这在实际中很少发生（JDK的自链接设计已经缓解了大部分问题），但理论上需要注意。

### 10.4 不支持阻塞

```java
// poll()返回null不代表队列"永远"为空
// 可能下一微秒就有新数据入队
E item = queue.poll();
if (item == null) {
    // 怎么办？
    // 方案1: 忙等（CPU浪费）
    while ((item = queue.poll()) == null) { }

    // 方案2: 短暂自旋后park
    for (int i = 0; i < 1000; i++) {
        item = queue.poll();
        if (item != null) break;
    }
    if (item == null) {
        LockSupport.parkNanos(1000_000); // 1ms
    }

    // 方案3: 使用BlockingQueue代替
}
```

如果你的场景需要等待，更好的选择是`LinkedBlockingQueue`或`LinkedTransferQueue`。

---

## 十一、手写一个简化版无锁队列

理解了原理，我们来手写一个最简化版的Michael-Scott无锁队列：

```java
import java.util.concurrent.atomic.AtomicReference;

/**
 * 简化版Michael-Scott无锁队列
 * 没有松弛优化、没有自链接，但核心算法正确
 */
public class SimpleLockFreeQueue<E> {

    // 节点定义
    private static class Node<E> {
        final E item;  // 数据域（哨兵节点为null）
        final AtomicReference<Node<E>> next;  // 后继指针，原子引用

        Node(E item) {
            this.item = item;
            this.next = new AtomicReference<>(null);
        }
    }

    // 头指针（指向哨兵节点）
    private final AtomicReference<Node<E>> head;
    // 尾指针（指向或接近最后一个节点）
    private final AtomicReference<Node<E>> tail;

    public SimpleLockFreeQueue() {
        // 创建哨兵节点，head和tail都指向它
        Node<E> sentinel = new Node<>(null);
        head = new AtomicReference<>(sentinel);
        tail = new AtomicReference<>(sentinel);
    }

    /**
     * 入队操作
     * 对应Michael-Scott算法的Enqueue
     */
    public void offer(E item) {
        if (item == null) throw new NullPointerException();

        // 步骤1: 创建新节点
        Node<E> newNode = new Node<>(item);

        while (true) {  // 无限循环，直到成功
            // 步骤2: 读取当前尾指针
            Node<E> curTail = tail.get();
            // 步骤3: 读取尾节点的next
            Node<E> tailNext = curTail.next.get();

            // 步骤4: 确认tail没有被其他线程改变（双重检查）
            if (curTail == tail.get()) {

                if (tailNext == null) {
                    // 情况A: tail确实指向最后一个节点
                    // 尝试将新节点挂到tail的后面
                    if (curTail.next.compareAndSet(null, newNode)) {
                        // 链接成功！这是入队的线性化点
                        // 尝试推进tail（失败也没关系，其他线程会帮忙）
                        tail.compareAndSet(curTail, newNode);
                        return;  // 入队完成
                    }
                    // CAS失败：说明其他线程抢先链接了，重试
                } else {
                    // 情况B: tail滞后了（其他线程已经链接了新节点但还没更新tail）
                    // 帮助推进tail
                    tail.compareAndSet(curTail, tailNext);
                    // 然后重试
                }
            }
            // 如果curTail != tail.get()，说明tail被改了，直接重试
        }
    }

    /**
     * 出队操作
     * 对应Michael-Scott算法的Dequeue
     */
    public E poll() {
        while (true) {  // 无限循环，直到成功或确认为空
            // 步骤1: 读取head
            Node<E> curHead = head.get();
            // 步骤2: 读取tail
            Node<E> curTail = tail.get();
            // 步骤3: 读取head的下一个节点（真正的第一个有效节点）
            Node<E> headNext = curHead.next.get();

            // 步骤4: 确认head没变
            if (curHead == head.get()) {

                if (curHead == curTail) {
                    // head和tail指向同一个节点
                    if (headNext == null) {
                        // 队列为空
                        return null;
                    }
                    // tail滞后了，帮助推进
                    tail.compareAndSet(curTail, headNext);
                } else {
                    // 队列不为空，尝试取出headNext的数据
                    E item = headNext.item;
                    // CAS推进head
                    if (head.compareAndSet(curHead, headNext)) {
                        // 成功！这是出队的线性化点
                        // headNext成为新的哨兵节点
                        return item;
                    }
                    // CAS失败：其他线程抢先出队了，重试
                }
            }
        }
    }

    /**
     * 判断队列是否为空
     */
    public boolean isEmpty() {
        return head.get().next.get() == null;
    }
}
```

### 简化版 vs JDK版本的差距

| 方面 | 简化版 | JDK ConcurrentLinkedQueue |
|------|--------|---------------------------|
| 松弛优化 | 无，每次都更新head/tail | 有，减少CAS次数 |
| 自链接 | 无，可能内存泄漏 | 有，帮助GC |
| 遍历优化 | 无 | 有复杂的跳跃逻辑 |
| item可变 | 不可变（final） | 可变（用于逻辑删除） |
| 性能 | 基础可用 | 高度优化 |
| 额外方法 | 只有offer/poll | 完整的Queue接口 |

---

## 十二、常见面试问题深度解答

### Q1: ConcurrentLinkedQueue的底层原理是什么？

**答**：ConcurrentLinkedQueue基于Michael-Scott无锁队列算法实现，底层是一个CAS操作的单向链表。它使用哨兵节点（dummy node）简化边界条件，通过volatile保证head/tail/next的可见性，使用CAS保证并发安全。入队时CAS修改尾节点的next指针，出队时CAS推进head指针。配合松弛（Slack）设计，tail和head不是每次操作都更新，而是允许滞后1-2个节点，减少CAS竞争。

### Q2: 为什么tail不总是指向最后一个节点？

**答**：这是松弛（Slack）优化策略。如果每次入队都CAS更新tail，所有入队线程都会竞争tail这一个热点指针，导致大量CAS失败和重试。通过允许tail滞后，只有当滞后达到一定程度（通常是2个节点）才更新tail，可以显著减少CAS操作次数。代价是入队时可能需要多遍历1-2个节点找到真正的尾部，但遍历是无锁的、很快的，远比CAS竞争的代价小。

### Q3: 什么是自链接？为什么需要？

**答**：自链接是指将已出队节点的next指针指向自己（node.next = node）。目的是切断已出队节点与活跃链表之间的引用链。如果不自链接，假设外部代码持有一个已出队节点的引用，通过其next指针仍然可以遍历到队列中所有后续节点，阻止它们被GC回收。自链接后，从出队节点出发只能看到自己，无法触达活跃链表，帮助GC及时回收内存。

### Q4: offer和poll的时间复杂度是多少？

**答**：在没有竞争或低竞争情况下，offer和poll的摊销时间复杂度都是O(1)。由于松弛设计，可能需要遍历1-2个节点，但这是常数级的。在高竞争情况下，CAS可能失败需要重试，最坏情况下时间不确定，但无锁保证了系统级的前进性（至少有一个线程能成功）。

### Q5: 无锁队列和阻塞队列的区别？

**答**：
- 同步机制：无锁队列用CAS，阻塞队列用Lock
- 阻塞能力：无锁队列不支持阻塞等待（poll返回null表示空），阻塞队列支持put/take阻塞
- 性能：低竞争下无锁队列吞吐量更高（无锁开销），高竞争下各有优劣
- 容量：ConcurrentLinkedQueue无界，LinkedBlockingQueue可有界
- 适用场景：无锁队列适合不需要阻塞的高并发传递；阻塞队列适合标准生产者-消费者模式

### Q6: ConcurrentLinkedQueue的size()为什么不精确？

**答**：因为size()需要遍历整个链表逐个计数，遍历过程中其他线程可能在并发地入队或出队，导致计数不准确。这是无锁设计的固有限制——要实现精确的size需要加锁阻止所有并发操作，这违背了无锁的设计理念。所以文档明确说明size()的结果可能不精确，如果只是判断是否为空，应该用isEmpty()。

### Q7: ConcurrentLinkedQueue是有界还是无界的？有什么风险？

**答**：ConcurrentLinkedQueue是无界的，理论上可以无限增长。主要风险是OOM（内存溢出）：如果生产者速度远大于消费者速度，队列会不断膨胀直到耗尽内存。使用时要么确保消费速度能跟上，要么在业务层面实现流量控制，或者干脆使用有界的LinkedBlockingQueue来获得天然的背压能力。

### Q8: 无锁队列能保证FIFO吗？

**答**：能。虽然多个线程并发入队时，它们入队的顺序可能不确定，但一旦入队成功（CAS成功链接到链表尾部），从队列出队的顺序严格按照链接顺序（即FIFO）。更正式地说，入队的线性化点是CAS成功将新节点链接到尾部的那一刻，出队的线性化点是CAS成功推进head的那一刻，线性化顺序保证了FIFO语义。

### Q9: 如果让你设计一个无锁队列，你会怎么做？

**答**：首先选择数据结构（链表还是数组）。链表方案采用Michael-Scott算法：使用哨兵节点，head指向哨兵，tail指向尾部；入队时CAS链接到尾部，出队时CAS推进head。然后根据场景优化：如果是多生产者多消费者，保持Michael-Scott的通用设计；如果是单消费者，可以去掉head的CAS；如果对GC敏感，加入自链接；如果竞争激烈，加入松弛策略和退避（backoff）。数组方案则参考Disruptor的RingBuffer设计。

---

## 十三、总结

### 知识图谱

```
无锁队列知识体系
├── 理论基础
│   ├── 无锁 (Lock-Free) 进度保证
│   ├── CAS 原子操作
│   └── 协作式设计 (Helping)
├── 经典算法
│   └── Michael-Scott Queue (1996)
│       ├── 哨兵节点
│       ├── 两步入队 (链接 + 推进tail)
│       └── 线性化点分析
├── JDK实现: ConcurrentLinkedQueue
│   ├── 核心方法: offer / poll / peek
│   ├── 松弛设计 (Slack)
│   ├── 自链接 (Self-Link)
│   └── Node内部类
├── 扩展
│   ├── ConcurrentLinkedDeque (双端)
│   ├── Netty MpscQueue
│   ├── Disruptor RingBuffer
│   └── ForkJoinPool WorkQueue
├── 性能分析
│   ├── 缓存行 & 伪共享
│   ├── 适用线程数
│   └── vs 阻塞队列
└── 注意事项
    ├── size()不精确
    ├── 无界OOM风险
    ├── 不支持阻塞
    └── 批量操作非原子
```

---

## 十四、其他经典无锁队列算法深度解析

Michael-Scott Queue 是最知名的无锁队列算法，但绝不是唯一的。不同的算法针对不同的场景（竞争程度、生产者/消费者数量、内存模型、GC 需求）做了不同的取舍。下面我们逐一深度解析每一种经典算法。

---

### 14.1 Valois Queue（1994）——无锁队列的开拓者

#### 14.1.1 历史背景

John D. Valois 在 1994 年发表了论文《Implementing Lock-Free Queues》，这是学术界最早的无锁链表队列实现之一。它比 Michael-Scott Queue 早了两年，可以说是 MS Queue 的"前辈"。

#### 14.1.2 算法思想

Valois Queue 同样基于单向链表，使用哨兵节点（dummy node）。核心操作：

**入队（Enqueue）：**
```
Enqueue(x):
    node = new Node(x, null)
    loop:
        tail = this.tail
        next = tail.next
        if next == null:
            if CAS(tail.next, null, node):   // 链接新节点
                CAS(this.tail, tail, node)    // 推进tail
                return
        else:
            CAS(this.tail, tail, next)        // 帮助推进
```

**出队（Dequeue）：**
```
Dequeue():
    loop:
        head = this.head
        next = head.next
        if next == null:
            return EMPTY
        value = next.item
        if CAS(this.head, head, next):
            free(head)  // 释放旧哨兵
            return value
```

看起来和 Michael-Scott 很像对吗？确实，MS Queue 就是在 Valois 的基础上改进的。

#### 14.1.3 Valois Queue 的致命缺陷

**缺陷1：ABA问题**

Valois 的原始设计没有妥善处理 ABA 问题。考虑这个场景：

1. 线程A读取 head = NodeX，准备CAS
2. 线程B出队 NodeX，释放它的内存
3. 内存分配器把那块内存分配给了一个新节点 NodeY
4. 线程A做CAS：期望值是 NodeX 的地址，而 NodeY 恰好在同一地址 → CAS"成功"了，但实际上是错误的！

在 C/C++ 中，这是真实存在的问题（内存复用）。在 Java 中因为 GC 的存在，只要还有引用指向对象，对象就不会被回收，所以 Java 中天然不存在这个问题。

**缺陷2：内存回收困难**

在没有 GC 的环境中（C/C++），什么时候可以安全释放已出队的节点？如果有其他线程正持有该节点的引用（还在做CAS的过程中），释放就会导致Use-After-Free。

Valois 提出了一种基于引用计数的解决方案，但引用计数本身的原子维护又引入了新的开销和复杂性。

#### 14.1.4 Michael-Scott 如何改进了 Valois

MS Queue 的关键改进：

1. **更精确的一致性检查**：MS Queue 在读取 tail.next 之后会重新检查 tail 是否变化（双重检查），减少了 ABA 的窗口
2. **配合 Hazard Pointers 或 GC**：在 C++ 中使用 Hazard Pointers（Michael 2004年的另一篇论文）来安全回收内存
3. **更简洁的算法结构**：MS Queue 的状态空间更小，正确性更容易证明

#### 14.1.5 Valois Queue 的历史意义

虽然 Valois Queue 有缺陷，但它的贡献是巨大的：
- **首次证明了**无锁FIFO队列是可行的
- **奠定了基本框架**：哨兵节点 + CAS链接 + CAS推进tail
- **启发了后续工作**：MS Queue、Optimistic Queue 都是在它的思路上发展的

---

### 14.2 Optimistic Queue（Ladan-Mozes & Shavit, 2004）——乐观回溯法

#### 14.2.1 动机：减少入队的CAS次数

在 Michael-Scott Queue 中，入队需要两个CAS：
1. CAS tail.next = newNode（链接）
2. CAS tail = newNode（推进）

虽然第二个CAS可能失败也不影响正确性（松弛设计），但在高竞争下，第一个CAS（链接）仍然是热点——所有入队线程都在争抢"最后一个节点的next指针"。

Ladan-Mozes 和 Shavit 在 2004 年提出了 Optimistic Queue，核心目标是：**让入队操作只需要一次CAS就能完成**。

#### 14.2.2 核心思想——双向链表 + 乐观策略

Optimistic Queue 使用**双向链表**（每个节点有 prev 和 next 两个指针），算法的关键洞察是：

> 入队时，我们可以先"乐观地"直接CAS修改tail指向新节点（只一步CAS），然后再慢慢修复prev/next链接。如果发现中间状态不一致，通过从tail向前回溯来修复。

**入队（只需一次CAS）：**
```
Enqueue(x):
    node = new Node(x)
    loop:
        tail = this.tail
        node.next = tail          // 新节点的next指向当前tail（反向链接）
        if CAS(this.tail, tail, node):  // 一步CAS推进tail
            tail.prev = node      // 修复前驱指针（非原子，可以延迟）
            return
```

注意这里的巧妙之处：
- 链表的 next 指针是**从后向前**指的（新节点.next → 旧tail）
- prev 指针是从前向后指的（传统方向）
- 入队只需CAS一次就完成（CAS tail），不需要像MS Queue那样先CAS链接再CAS推进

**出队（需要修复链接）：**
```
Dequeue():
    loop:
        head = this.head
        tail = this.tail
        firstNode = head.prev     // head的前一个节点（物理上是第一个有效节点）

        if head == tail:
            return EMPTY
        
        // 验证链接一致性
        if firstNode.next != head:
            fixLinks(tail, head)  // 链接不一致，需要修复
            continue

        value = firstNode.item
        if CAS(this.head, head, firstNode):
            return value
```

**fixLinks——乐观回溯修复：**
```
fixLinks(tail, head):
    // 从tail向head方向，逐个修复next指针
    cur = tail
    while cur != head:
        prev = cur.next  // next实际上是反向指针
        prev.prev = cur  // 修复prev指针
        cur = prev
```

#### 14.2.3 性能分析

| 操作 | MS Queue CAS次数 | Optimistic Queue CAS次数 |
|------|------------------|--------------------------|
| 入队（成功） | 1-2次CAS | 1次CAS |
| 出队（成功） | 1次CAS | 1次CAS |
| 入队（竞争） | 热点在tail.next | 热点在tail |

优势：
- 入队只需一次CAS，理论上减少了竞争点
- 在中等竞争下（4-16线程）性能比MS Queue好

劣势：
- 需要 fixLinks 操作来修复链接，出队可能需要遍历
- 双向链表节点更大（多一个prev指针），缓存不友好
- 算法更复杂，正确性证明更困难
- 在低竞争下（2线程）可能不如MS Queue（维护双向链接的开销）

#### 14.2.4 适用场景

适合**中等竞争、入队频率远高于出队频率**的场景。因为入队只需一次CAS非常高效，但出队可能需要fixLinks操作。

---

### 14.3 Flat-Combining Queue（Hendler et al., 2010）——换一种思路

#### 14.3.1 颠覆性的设计理念

前面所有算法都在想"如何让多个线程同时操作队列而不冲突"。Flat-Combining（FC）的思路完全不同：

> 既然多个线程竞争一个数据结构必然冲突，不如让**一个线程代替所有线程**执行操作。其他线程把"我想做什么"发布出来，然后等结果就好。

这就像餐厅叫号：不是所有顾客自己去厨房做饭（互相冲突），而是把点单发给一个服务员，服务员统一下单给厨房。

#### 14.3.2 算法结构

```
全局结构：
- lock: 一把全局锁（互斥锁）
- publication_list: 公告链表（每个线程有一个slot）
- queue: 底层的顺序队列（可以是最简单的普通队列）

每个线程的slot：
- operation: ENQUEUE / DEQUEUE / NONE
- data: 入队的数据 / 出队的结果
- active: 是否有待处理的操作
```

**操作流程：**

```
// 任何线程想做操作（以入队为例）
flatCombiningEnqueue(x):
    mySlot.operation = ENQUEUE
    mySlot.data = x
    mySlot.active = true

    while mySlot.active:       // 等待操作被执行
        if tryLock(lock):      // 尝试成为combiner
            // 成为combiner！遍历所有active的slot，批量执行
            scan publication_list:
                for each slot where active == true:
                    if slot.operation == ENQUEUE:
                        queue.add(slot.data)
                        slot.active = false  // 通知该线程：你的操作完成了
                    elif slot.operation == DEQUEUE:
                        slot.data = queue.poll()
                        slot.active = false
            unlock(lock)
            return  // 自己的操作也在上面被处理了
        else:
            // 没抢到锁，等待（spinning / yielding）
            // 某个combiner会帮我执行
    
    // active变成false了，说明有combiner帮我执行了
    return mySlot.data  // 对于出队操作，这里拿到结果
```

#### 14.3.3 为什么叫"Flat-Combining"？

"Flat"是因为多个操作被"压平"成一批，由一个线程顺序执行。"Combining"是因为多个线程的操作被"合并"在一起处理。

#### 14.3.4 看起来有锁，为什么算"无锁"？

严格来说，Flat-Combining**不是无锁的**（它用了互斥锁），它属于**锁+优化**的范畴。但它被放在无锁队列的讨论中，是因为：

1. 它解决的问题和无锁队列相同（高并发队列）
2. 在超高竞争下性能可能**超过**真正的无锁算法
3. 它展示了一种完全不同的并发设计哲学

#### 14.3.5 性能特征

**超高竞争（64+线程）下的杀手锏：**
- 传统无锁队列：CAS失败率极高，CPU空转严重
- Flat-Combining：只有一个combiner在执行，零竞争！其他线程只是等待
- combiner一次遍历处理N个操作，**摊销开销极低**
- 底层队列是顺序操作，可以用最简单的数组队列，**缓存极其友好**

**低竞争下的劣势：**
- 获取锁本身有开销
- 线程需要等待combiner处理
- 额外的publication list遍历开销

**性能对比（典型benchmark）：**
| 线程数 | MS Queue | Flat-Combining |
|--------|----------|----------------|
| 2 | 快 | 慢（锁开销） |
| 8 | 快 | 接近 |
| 32 | 中等 | 快 |
| 64 | 慢（CAS爆炸） | 很快 |
| 128 | 很慢 | 最快 |

#### 14.3.6 变体与优化

1. **FC-Queue with Elimination**：入队和出队可以直接"配对"抵消，不需要真的操作底层队列
2. **Distributed FC**：多个combiner分片处理，减少单combiner的瓶颈
3. **CC-Queue（Concurrency-Combining Queue）**：类似思想的变体实现

#### 14.3.7 Java 实现思路

```java
public class FlatCombiningQueue<E> {

    // 每个线程的操作槽
    static class Slot<E> {
        volatile int operation;  // 0=NONE, 1=ENQUEUE, 2=DEQUEUE
        volatile E data;
        volatile boolean active;
    }

    // 底层的普通队列（只有combiner单线程访问，不需要并发安全）
    private final ArrayDeque<E> queue = new ArrayDeque<>();
    // 公告链表
    private final ConcurrentLinkedQueue<Slot<E>> publicationList = new ConcurrentLinkedQueue<>();
    // combiner锁
    private final ReentrantLock combineLock = new ReentrantLock();
    // ThreadLocal的slot
    private final ThreadLocal<Slot<E>> threadSlot = ThreadLocal.withInitial(() -> {
        Slot<E> slot = new Slot<>();
        publicationList.add(slot);
        return slot;
    });

    public void offer(E item) {
        Slot<E> slot = threadSlot.get();
        slot.data = item;
        slot.operation = 1;  // ENQUEUE
        slot.active = true;

        while (slot.active) {
            if (combineLock.tryLock()) {
                try {
                    // 我是combiner，批量处理所有请求
                    for (Slot<E> s : publicationList) {
                        if (s.active) {
                            if (s.operation == 1) {
                                queue.offer(s.data);
                                s.data = null;
                            } else if (s.operation == 2) {
                                s.data = queue.poll();
                            }
                            s.active = false;
                        }
                    }
                } finally {
                    combineLock.unlock();
                }
                return;
            }
            Thread.yield();  // 让出CPU等待combiner处理
        }
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        Slot<E> slot = threadSlot.get();
        slot.operation = 2;  // DEQUEUE
        slot.active = true;

        while (slot.active) {
            if (combineLock.tryLock()) {
                try {
                    for (Slot<E> s : publicationList) {
                        if (s.active) {
                            if (s.operation == 1) {
                                queue.offer(s.data);
                                s.data = null;
                            } else if (s.operation == 2) {
                                s.data = queue.poll();
                            }
                            s.active = false;
                        }
                    }
                } finally {
                    combineLock.unlock();
                }
                return slot.data;
            }
            Thread.yield();
        }
        return slot.data;
    }
}
```

---

### 14.4 LCRQ——Linked Concurrent Ring Queue（Morrison & Afek, 2013）

#### 14.4.1 动机

MS Queue 的瓶颈在于：所有入队线程都竞争"最后一个节点的next指针"，所有出队线程都竞争"head"。这两个热点在高竞争下成为性能天花板。

LCRQ 的核心思想：**把竞争分散到多个小队列中**。

#### 14.4.2 数据结构

LCRQ 由两层组成：

**底层：CRQ（Concurrent Ring Queue）**
- 一个固定大小的环形数组
- 每个槽位（slot）存储一个元素和状态标记
- 使用 Fetch-And-Add（FAA）而非CAS来分配位置——FAA不会失败！

**顶层：链表串联多个CRQ**
- 多个CRQ节点用链表连接
- head CRQ用于出队
- tail CRQ用于入队
- 当tail CRQ满了，创建新的CRQ挂到链表尾部
- 当head CRQ空了，推进到下一个CRQ

```
链表结构：
head → [CRQ-1(空)] → [CRQ-2(部分)] → [CRQ-3(在入队)] ← tail

每个CRQ内部：
[slot0][slot1][slot2]...[slotN-1]  (环形数组)
  ↑                                    ↑
 deqIdx                              enqIdx
```

#### 14.4.3 CRQ 的核心操作——Fetch-And-Add

CAS的问题：多个线程同时CAS同一个位置，只有一个成功，其他全部失败重试。
FAA的优势：多个线程同时FAA同一个位置，**全部成功**！每个线程得到一个唯一的递增值。

```
// 入队
CRQ_Enqueue(crq, item):
    idx = FAA(crq.enqIdx, 1) % RING_SIZE   // 原子递增，得到自己的位置
    // 直接写入idx位置
    crq.slots[idx] = item

// 出队
CRQ_Dequeue(crq):
    idx = FAA(crq.deqIdx, 1) % RING_SIZE   // 原子递增，得到自己的位置
    return crq.slots[idx]
```

当然实际实现比这复杂得多（需要处理环绕、空/满判断等），但核心优势是：**FAA永远不会"失败重试"**，每个线程一次FAA就能确定自己的位置。

#### 14.4.4 为什么LCRQ性能强

1. **FAA代替CAS**：消除了CAS失败重试的开销
2. **分散竞争**：enqIdx和deqIdx是不同的变量，入队和出队不互相干扰
3. **数组结构**：连续内存，缓存友好
4. **接近Wait-Free**：FAA成功率100%，每个线程的操作步数几乎固定

#### 14.4.5 性能数据

根据原论文的benchmark（在72核Sun SPARC机器上）：

| 算法 | 吞吐量（相对值） |
|------|------------------|
| MS Queue | 1x |
| Flat-Combining | 2-3x |
| LCRQ | 5-10x |

LCRQ在高竞争下的性能是MS Queue的5-10倍！

#### 14.4.6 局限性

1. **实现复杂**：需要处理CRQ满/空、环绕、节点回收等诸多细节
2. **空间开销**：预分配的环形数组可能浪费空间
3. **不适合JVM**：原始设计针对C/C++（依赖内存回收方案），Java中直接使用不太方便
4. **Java中的替代**：JCTools的MpmcArrayQueue是类似思路的Java实现

---

### 14.5 Lamport's SPSC Queue——最极致的简单

#### 14.5.1 场景限定

SPSC = Single-Producer Single-Consumer，只有一个线程写、一个线程读。这是最受限的场景，但也是最常见的场景之一（比如线程间的消息传递、流水线的阶段间传递）。

#### 14.5.2 为什么不需要CAS

关键洞察：如果只有一个writer和一个reader，**它们操作的变量不同**：
- Writer只修改 writeIndex
- Reader只修改 readIndex
- Writer读readIndex（判断是否满）
- Reader读writeIndex（判断是否空）

只有"读"是跨线程的，"写"永远是单线程的。单线程写不需要CAS！只需要保证**写入的可见性**（volatile或内存屏障）。

#### 14.5.3 算法实现

```java
/**
 * Lamport SPSC Queue - 单生产者单消费者无锁队列
 * 不需要CAS！只需要volatile保证可见性
 */
public class LamportSPSCQueue<E> {

    private final Object[] buffer;      // 环形缓冲区
    private final int capacity;

    // 生产者独占修改，消费者只读
    private volatile long writeIndex = 0;
    // 消费者独占修改，生产者只读
    private volatile long readIndex = 0;

    public LamportSPSCQueue(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /**
     * 入队（只有生产者线程调用）
     * 不需要CAS，因为只有一个线程写writeIndex
     */
    public boolean offer(E item) {
        long wIdx = writeIndex;  // 本地读（只有自己写，不需要volatile读）
        long rIdx = readIndex;   // volatile读（读消费者的进度）

        // 判断是否满
        if (wIdx - rIdx >= capacity) {
            return false;  // 队列满了
        }

        // 写入数据
        buffer[(int)(wIdx % capacity)] = item;

        // 更新writeIndex（volatile写，保证消费者能看到）
        writeIndex = wIdx + 1;

        return true;
    }

    /**
     * 出队（只有消费者线程调用）
     * 不需要CAS，因为只有一个线程写readIndex
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        long rIdx = readIndex;    // 本地读
        long wIdx = writeIndex;   // volatile读（读生产者的进度）

        // 判断是否空
        if (rIdx >= wIdx) {
            return null;  // 队列空了
        }

        // 读取数据
        E item = (E) buffer[(int)(rIdx % capacity)];

        // 更新readIndex（volatile写，保证生产者能看到）
        readIndex = rIdx + 1;

        return item;
    }
}
```

#### 14.5.4 为什么这么快

1. **零CAS**：没有任何原子操作，只有普通的内存读写
2. **零竞争**：生产者和消费者操作的核心变量完全分开
3. **零系统调用**：不需要park/unpark
4. **缓存友好**：数组连续内存，CPU预取有效
5. **可预测的延迟**：没有CAS重试，每次操作的步数固定

#### 14.5.5 性能数据

在现代硬件上，Lamport SPSC Queue的吞吐量可以达到**每秒数亿次操作**，几乎是硬件极限。

#### 14.5.6 进一步优化

**1. 缓存行padding：**
```java
// writeIndex和readIndex应该在不同的缓存行
private volatile long writeIndex = 0;
private long p1, p2, p3, p4, p5, p6, p7;  // 56字节padding
private volatile long readIndex = 0;
```

**2. 批量操作（Batching）：**
```java
// 生产者：一次性写多个元素，最后才更新writeIndex
public int offerBatch(E[] items, int count) {
    long wIdx = writeIndex;
    long rIdx = readIndex;
    long available = capacity - (wIdx - rIdx);
    int actual = (int) Math.min(count, available);

    for (int i = 0; i < actual; i++) {
        buffer[(int)((wIdx + i) % capacity)] = items[i];
    }
    writeIndex = wIdx + actual;  // 只一次volatile写
    return actual;
}
```

**3. LazySet优化：**
```java
// 用lazySet代替volatile写，只需StoreStore屏障
// 在x86上StoreStore是免费的（x86天然保证Store顺序）
UNSAFE.putOrderedLong(this, writeIndexOffset, wIdx + 1);
```

#### 14.5.7 应用场景

- Disruptor的单生产者模式
- Netty的线程间通信
- 日志框架的异步写入
- JCTools的SpscArrayQueue/SpscLinkedQueue
- 任何明确只有两个线程交互的场景

---

### 14.6 Disruptor RingBuffer（LMAX, 2011）——金融级高性能

#### 14.6.1 背景

LMAX是伦敦的一家金融交易所。他们需要处理每秒600万笔交易，传统的队列方案（包括无锁队列）都不够快。于是他们发明了Disruptor。

Disruptor不是简单的队列，而是一套完整的事件处理框架。其核心数据结构——RingBuffer——是一个革命性的无锁队列设计。

#### 14.6.2 核心设计

```
RingBuffer（预分配数组）：
[Event0][Event1][Event2][Event3][Event4][Event5][Event6][Event7]
                  ↑                               ↑
              消费者位置                         生产者位置
              (gating sequence)                  (cursor)

Sequence（序号）：
- cursor: 生产者当前写到哪个位置
- gating sequences: 各个消费者当前处理到哪个位置
```

**关键设计决策：**

1. **预分配数组**：RingBuffer创建时就分配好所有Event对象，运行时**不创建新对象**（零GC）
2. **用序号而非指针**：不维护head/tail指针，而是用递增的序号（Sequence）表示位置
3. **序号取模定位**：`slot = buffer[sequence % bufferSize]`
4. **两阶段发布**：生产者先CAS占位，再写入数据，最后发布（更新cursor）

#### 14.6.3 单生产者入队

```java
// 单生产者模式（最快，不需要CAS）
public long next() {
    long nextSequence = this.nextValue + 1;  // 下一个位置

    // 等待消费者腾出空间（背压）
    long wrapPoint = nextSequence - bufferSize;
    while (wrapPoint > cachedGatingSequence) {
        cachedGatingSequence = getMinimumSequence(gatingSequences);
        // 如果消费者太慢，这里会自旋等待
    }

    this.nextValue = nextSequence;
    return nextSequence;
}

public void publish(long sequence) {
    // 更新cursor，通知消费者有新数据
    cursor.set(sequence);
    // 如果有等待策略，唤醒等待的消费者
    waitStrategy.signalAllWhenBlocking();
}
```

单生产者不需要CAS！直接递增序号即可（和Lamport SPSC思路一样）。

#### 14.6.4 多生产者入队

```java
// 多生产者模式（需要CAS）
public long next() {
    long current, next;
    do {
        current = cursor.get();
        next = current + 1;

        // 等待消费者腾出空间
        long wrapPoint = next - bufferSize;
        if (wrapPoint > cachedGatingSequence) {
            cachedGatingSequence = getMinimumSequence(gatingSequences);
            if (wrapPoint > cachedGatingSequence) {
                LockSupport.parkNanos(1);
                continue;
            }
        }
    } while (!cursor.compareAndSet(current, next));  // CAS抢占位置

    return next;
}
```

多生产者需要CAS来争抢序号，但竞争点只有一个（cursor），而且CAS的只是一个long值，非常高效。

#### 14.6.5 消费者模式

Disruptor支持复杂的消费者依赖关系：

```
                    [消费者A] ──→ [消费者C]
生产者 ──→                          ↓
                    [消费者B] ──→ [消费者D]
```

- A和B可以并行消费
- C必须等A完成
- D必须等B和C都完成

这通过SequenceBarrier实现——每个消费者等待它所依赖的所有上游消费者的sequence前进。

#### 14.6.6 等待策略

消费者在没有新数据时怎么等？Disruptor提供多种策略：

| 策略 | 描述 | 适用场景 |
|------|------|---------|
| BusySpinWaitStrategy | 忙等（while循环） | 延迟最低，CPU占满 |
| YieldingWaitStrategy | Thread.yield() | 低延迟，让出部分CPU |
| SleepingWaitStrategy | LockSupport.parkNanos | 平衡延迟和CPU |
| BlockingWaitStrategy | Lock + Condition | 最省CPU，延迟最高 |

金融场景选BusySpin（延迟优先），普通场景选Sleeping或Blocking。

#### 14.6.7 为什么Disruptor这么快

1. **零GC**：预分配对象，运行时不new任何对象
2. **缓存友好**：
   - 数组连续内存，CPU可以有效预取
   - Sequence之间有padding，避免伪共享
3. **最少的原子操作**：
   - 单生产者：零CAS
   - 多生产者：只CAS一个long
4. **无锁**：消费者通过busy-spin或yield等待，不park
5. **批处理**：消费者一次可以处理多个事件（消费到cursor位置）

#### 14.6.8 与ConcurrentLinkedQueue的对比

| 特性 | ConcurrentLinkedQueue | Disruptor RingBuffer |
|------|----------------------|---------------------|
| 数据结构 | 链表 | 环形数组 |
| GC压力 | 每次入队new Node | 零GC |
| 缓存 | 差（节点分散） | 优（连续内存） |
| 容量 | 无界 | 有界（预分配） |
| 背压 | 无 | 天然支持（数组满就等） |
| 消费者模型 | 简单poll | 复杂依赖图 |
| 适用吞吐 | 百万/秒 | 千万-亿/秒 |

---

### 14.7 Kogan-Petrank Wait-Free Queue（2011）——理论极限

#### 14.7.1 Wait-Free的意义

回顾进度保证的层级：
- **Lock-Free**：系统整体前进，但某个线程可能"饿死"（一直CAS失败）
- **Wait-Free**：**每个线程**在有限步内一定完成，不论其他线程如何

Wait-Free是并发算法的"圣杯"——最强的进度保证。但它非常难实现，因为需要保证：即使有线程被调度器一直挂起，其他线程也能帮它完成操作。

#### 14.7.2 核心思想——Helping机制的极致

Kogan和Petrank的设计核心：

1. **Phase-based helping**：每个操作有一个递增的phase号
2. **State array**：全局共享的状态数组，每个线程一个slot
3. **帮助协议**：任何线程在执行自己的操作时，会先检查是否有其他线程"卡住了"需要帮助
4. **优先帮最老的操作**：通过phase号判断哪个操作最"老"（最先发起），优先帮它

```
// 简化的Wait-Free入队
WF_Enqueue(x):
    myOp = new Operation(ENQUEUE, x)
    state[myThreadId] = myOp      // 发布我的操作
    myOp.phase = FAA(globalPhase, 1)  // 获取全局递增的phase

    // 帮助其他线程（最多帮N个，N是线程数）
    for each thread t:
        op = state[t]
        if op != null and op.phase <= myOp.phase:
            help(op)  // 帮助比我更早的操作完成

    // 执行自己的操作（如果还没被别人帮着完成）
    if not myOp.completed:
        doEnqueue(myOp)

    state[myThreadId] = null  // 清除
    return myOp.result
```

#### 14.7.3 为什么是Wait-Free

关键保证：如果线程A的操作已经发布（state[A] = op），那么：
- 任何后续开始的线程B，在执行自己操作之前，会先帮A完成
- 最多经过N个线程的"帮助周期"，A的操作一定被完成
- 所以A最多等待O(N)步（N是线程数），一定能完成

这就是Wait-Free的保证：**有限步内一定完成**，步数上界是O(N * 单次操作的步数)。

#### 14.7.4 代价与权衡

**优势：**
- 最强的进度保证
- 不存在"饿死"问题
- 适合实时系统（延迟有上界）

**劣势：**
- 实现极其复杂（原论文的代码有数百行）
- 每个操作都需要检查和帮助其他线程——开销很大
- 在低竞争下，比Lock-Free慢（因为帮助机制是额外开销）
- 只有在需要**严格的延迟保证**时才值得用

#### 14.7.5 实际应用

Wait-Free算法在工业界的应用非常少。原因是：
1. 大多数场景下Lock-Free已经足够好（饿死问题在实际中很少发生）
2. Wait-Free的常数开销太大
3. 实现和调试的复杂度不值得

主要应用在：
- 硬实时系统（航空航天、核电控制）
- 对延迟有硬性SLA的金融系统
- 学术研究和理论证明

---

### 14.8 Yang-Mellor-Crummey Queue（2016）——FAA代替CAS

#### 14.8.1 动机

CAS的问题在高竞争下非常明显：

```
64个线程同时CAS同一个位置：
线程1: CAS成功 ✓
线程2-64: CAS全部失败 ✗（63次无效操作！）
```

CAS的成功率 = 1/N（N是竞争线程数）。线程越多，浪费越大。

Fetch-And-Add（FAA）则完全不同：

```
64个线程同时FAA同一个位置：
线程1: FAA成功，得到值0 ✓
线程2: FAA成功，得到值1 ✓
线程3: FAA成功，得到值2 ✓
...
线程64: FAA成功，得到值63 ✓
全部成功！零浪费！
```

#### 14.8.2 算法思想

Yang-Mellor-Crummey (YMC) Queue的核心：用FAA来分配位置，然后在各自的位置上写入数据。

```
数据结构：
- 环形数组 buffer[SIZE]
- enqIdx: 入队计数器（FAA递增）
- deqIdx: 出队计数器（FAA递增）
- 每个slot: {value, epoch}  // epoch用于区分"满"和"空"

入队：
YMC_Enqueue(x):
    idx = FAA(enqIdx, 1)        // 原子递增，得到自己的位置
    slot = buffer[idx % SIZE]

    // 等待这个slot变为"空"（上一轮的消费者已经取走了）
    while slot.epoch != idx / SIZE:
        backoff()

    slot.value = x
    slot.epoch = idx / SIZE + 1  // 标记为"满"（发布给消费者）

出队：
YMC_Dequeue():
    idx = FAA(deqIdx, 1)        // 原子递增，得到自己的位置
    slot = buffer[idx % SIZE]

    // 等待这个slot变为"满"（生产者已经写入了）
    while slot.epoch != idx / SIZE + 1:
        backoff()

    value = slot.value
    slot.epoch = (idx / SIZE) + 2  // 标记为"空"（给下一轮使用）
    return value
```

#### 14.8.3 为什么比CAS好

| 对比维度 | CAS方案（MS Queue） | FAA方案（YMC Queue） |
|---------|---------------------|---------------------|
| 操作成功率 | 1/N（N个线程竞争） | 100%（永远成功） |
| 失败后行为 | 重试（浪费CPU） | 无需重试 |
| 缓存行失效 | 每次CAS失败都导致缓存失效 | 一次FAA就定位 |
| 扩展性 | 随线程数下降 | 几乎不随线程数下降 |

#### 14.8.4 FAA方案的等待问题

FAA方案有一个CAS方案没有的问题：**生产者占了位但还没写入数据时，消费者必须等待**。

```
时序：
T1: 线程A FAA得到位置5
T2: 线程B FAA得到位置6
T3: 线程B写入数据到位置6（比A快）
T4: 消费者想消费位置5，但A还没写入！必须等A
```

这意味着某些操作可能需要等待（spin），所以严格来说YMC Queue不是Lock-Free的（可能因为某个线程被挂起而导致其他线程等待）。

解决方案：
1. **超时放弃**：等待太久就标记slot为"无效"，跳过
2. **帮助机制**：检测到某个线程太慢，帮它完成
3. **实践中不太可能**：线程被调度走很久在正常系统中极少发生

#### 14.8.5 性能数据

根据论文benchmark（64核机器）：

| 场景 | MS Queue | YMC Queue | 提升 |
|------|----------|-----------|------|
| 8线程 | 基准 | 1.5-2x | 中等 |
| 32线程 | 基准 | 3-5x | 显著 |
| 64线程 | 基准 | 5-8x | 巨大 |

线程越多，YMC Queue的优势越大（因为CAS的失败率随线程数增加而增加，而FAA不受影响）。

#### 14.8.6 Java中的类似实现

JCTools库的`MpmcArrayQueue`采用了类似的FAA思想：

```java
// JCTools MpmcArrayQueue 简化逻辑
public boolean offer(E e) {
    long pIndex;
    long cIndex;
    do {
        pIndex = lvProducerIndex();  // 读取生产者index
        cIndex = lvConsumerIndex();  // 读取消费者index
        if (pIndex - cIndex >= capacity) return false;  // 满了
    } while (!casProducerIndex(pIndex, pIndex + 1));  // CAS递增（接近FAA）

    // 写入数据
    long offset = calcElementOffset(pIndex);
    soElement(buffer, offset, e);  // Ordered store
    return true;
}
```

JCTools在Java层面尽量模拟了FAA的效果（通过CAS + 小循环），在JDK 9+中可以使用VarHandle的getAndAdd来实现真正的FAA。

---

### 14.9 算法横向对比总结

| 算法 | 年份 | 类型 | 进度保证 | 竞争策略 | 最佳场景 |
|------|------|------|---------|---------|---------|
| Valois | 1994 | 链表 | Lock-Free | CAS | 历史意义 |
| Michael-Scott | 1996 | 链表 | Lock-Free | CAS+帮助 | 通用MPMC |
| Optimistic | 2004 | 双向链表 | Lock-Free | 单次CAS+回溯 | 入队密集 |
| Flat-Combining | 2010 | 委托执行 | 有锁 | 合并执行 | 超高竞争 |
| Kogan-Petrank | 2011 | 链表 | Wait-Free | 全局帮助 | 实时系统 |
| Disruptor | 2011 | 环形数组 | N/A | 序号机制 | 金融级吞吐 |
| LCRQ | 2013 | 链表+环形 | Lock-Free | FAA分散 | 高竞争MPMC |
| YMC | 2016 | 环形数组 | 近Lock-Free | FAA | 高竞争MPMC |
| Lamport SPSC | 经典 | 环形数组 | Wait-Free | 无CAS | 单生产单消费 |

### 14.10 如何选择

```
你的场景是什么？
│
├── 只有一个生产者一个消费者？
│   └── Lamport SPSC Queue（最快）
│
├── 多个生产者，一个消费者？
│   └── MPSC Queue（JCTools MpscLinkedQueue / Netty MpscQueue）
│
├── 通用的多生产者多消费者？
│   ├── 竞争低（<8线程）？
│   │   └── Michael-Scott Queue / ConcurrentLinkedQueue（简单够用）
│   ├── 竞争中（8-32线程）？
│   │   └── LCRQ / YMC Queue / JCTools MpmcArrayQueue
│   └── 竞争超高（64+线程）？
│       └── Flat-Combining / LCRQ
│
├── 需要极致吞吐和零GC？
│   └── Disruptor RingBuffer
│
├── 需要严格的延迟上界（实时系统）？
│   └── Kogan-Petrank Wait-Free Queue
│
└── 需要有界+背压？
    └── Disruptor / ArrayBlockingQueue / MpmcArrayQueue
```

---

### 一句话总结

**ConcurrentLinkedQueue是基于Michael-Scott算法的无锁FIFO队列，通过CAS操作、哨兵节点、松弛指针和自链接四大核心设计，在无锁的前提下实现了高吞吐量的并发队列操作。它用遍历的小代价换取了更少的CAS竞争，用自链接切断了GC引用链，是JDK并发工具箱中"不阻塞、高性能"的首选队列实现。**

而在更广阔的无锁队列家族中，从Lamport最简单的SPSC到Disruptor的极致性能，从Michael-Scott的经典CAS到YMC的FAA革新，从Lock-Free到Wait-Free的理论极限，每种算法都在特定场景下有其独特价值。理解这些算法的设计取舍，才能在实际项目中做出最优选择。

---

> 本文档涵盖了无锁队列从理论到实践的完整知识体系。建议配合JDK源码阅读，动手调试offer/poll流程，深入理解每一个CAS操作的意义。无锁编程的精髓在于：用巧妙的算法设计（协作、松弛、自链接）弥补硬件原子操作能力的局限，在正确性和性能之间取得最佳平衡。