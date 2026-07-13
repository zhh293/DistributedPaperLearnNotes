# Java ThreadLocal 深度解析

> 本文基于 JDK 8/11 源码，逐行剖析 ThreadLocal 的底层原理，从使用到源码再到内存泄漏，力求讲透每一个细节。面向有一定 Java 基础但不熟悉并发底层原理的读者。

---

## 目录

- [一、为什么需要 ThreadLocal](#一为什么需要-threadlocal)
- [二、ThreadLocal 的基本使用](#二threadlocal-的基本使用)
- [三、底层存储结构全景](#三底层存储结构全景)
- [四、ThreadLocalMap 源码深度解析](#四threadlocalmap-源码深度解析)
- [五、内存泄漏问题深度剖析](#五内存泄漏问题深度剖析)
- [六、expungeStaleEntry 清理机制源码分析](#六expungestaleentry-清理机制源码分析)
- [七、InheritableThreadLocal](#七inheritablethreadlocal)
- [八、TransmittableThreadLocal（TTL）](#八transmittablethreadlocalttl)
- [九、ThreadLocal 在框架中的应用](#九threadlocal-在框架中的应用)
- [十、常见面试问题总结](#十常见面试问题总结)

---

## 一、为什么需要 ThreadLocal

### 1.1 多线程共享变量的问题

在多线程环境下，最常见的问题是**多个线程同时操作同一个共享变量**，导致数据不一致。比如下面这个经典例子：

```java
public class Counter {
    private int count = 0;

    public void increment() {
        count++; // 非原子操作：读-改-写三步
    }

    public int getCount() {
        return count;
    }
}
```

`count++` 看起来是一行代码，实际上包含了三个操作：读取 count 的值、加 1、写回 count。在多线程并发执行时，两个线程可能同时读到相同的值，各自加 1 后写回，最终结果比预期少。

解决这类问题，传统方案是**加锁**（`synchronized` 或 `Lock`），让同一时刻只有一个线程能操作共享变量。但加锁意味着**串行化**——线程要排队等待，性能开销大，还容易死锁。

### 1.2 ThreadLocal 的定位：空间换时间

ThreadLocal 提供了另一种思路：**与其让多个线程争抢一个变量，不如给每个线程各发一份**。

```java
ThreadLocal<Integer> counter = new ThreadLocal<>();

// 线程A
counter.set(1);  // 线程A操作的是自己的副本
// 线程B
counter.set(100); // 线程B操作的是自己的副本，互不影响
```

每个线程通过同一个 ThreadLocal 对象，访问到的都是**自己线程私有的变量副本**。线程之间完全隔离，不需要加锁，也不会有竞争。

这就是 **"空间换时间"** 的思想：
- `synchronized`：所有线程共享一个变量，通过加锁保证安全（时间换空间）
- `ThreadLocal`：每个线程各有一份变量副本，天然隔离，无需加锁（空间换时间）

### 1.3 典型使用场景

**场景一：数据库连接管理**

每个线程维护自己的数据库连接，避免连接被其他线程抢占：

```java
private static ThreadLocal<Connection> connectionHolder = ThreadLocal.withInitial(() -> {
    try {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
});

public static Connection getConnection() {
    return connectionHolder.get();
}
```

**场景二：SimpleDateFormat 线程安全**

`SimpleDateFormat` 是出了名的线程不安全（内部有共享的 Calendar 字段）。使用 ThreadLocal 为每个线程提供独立的实例：

```java
private static ThreadLocal<SimpleDateFormat> dateFormat =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

public static String format(Date date) {
    return dateFormat.get().format(date);
}
```

**场景三：用户上下文传递**

在 Web 应用中，请求经过 Controller → Service → DAO 多层调用，需要在各层获取当前登录用户信息，但不想通过方法参数层层传递。Spring 的 `RequestContextHolder` 就是基于 ThreadLocal 实现的：

```java
// 简化版：用户上下文
public class UserContext {
    private static ThreadLocal<User> currentUser = new ThreadLocal<>();

    public static void set(User user) {
        currentUser.set(user);
    }

    public static User get() {
        return currentUser.get();
    }

    public static void clear() {
        currentUser.remove();
    }
}

// 在拦截器中设置
public class UserInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        User user = parseUserFromToken(req.getHeader("Authorization"));
        UserContext.set(user); // 设置当前线程的用户
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserContext.clear(); // 请求结束，清理！防止内存泄漏
    }
}

// 在 Service 中直接获取，无需通过参数传递
public class OrderService {
    public void createOrder(Order order) {
        User currentUser = UserContext.get(); // 直接拿到当前用户
        order.setUserId(currentUser.getId());
        orderMapper.insert(order);
    }
}
```

---

## 二、ThreadLocal 的基本使用

### 2.1 核心 API

| 方法 | 说明 |
|------|------|
| `void set(T value)` | 设置当前线程的线程局部变量的值 |
| `T get()` | 返回当前线程的线程局部变量的值 |
| `void remove()` | 移除当前线程的线程局部变量（重要！防止内存泄漏） |
| `static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier)` | 创建一个带初始值的 ThreadLocal（Java 8+） |

### 2.2 代码示例

```java
public class ThreadLocalDemo {

    // 方式一：普通创建
    static ThreadLocal<String> threadLocal1 = new ThreadLocal<>();

    // 方式二：带初始值（Java 8+ 推荐写法）
    static ThreadLocal<Integer> threadLocal2 = ThreadLocal.withInitial(() -> 0);

    // 方式三：匿名内部类重写 initialValue（Java 8 之前的方式）
    static ThreadLocal<StringBuilder> threadLocal3 = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder();
        }
    };

    public static void main(String[] args) {
        // 主线程
        threadLocal1.set("main-thread");
        threadLocal2.set(100);

        Thread t1 = new Thread(() -> {
            threadLocal1.set("thread-1");   // 子线程设置自己的值
            threadLocal2.set(200);
            System.out.println("t1: " + threadLocal1.get() + ", " + threadLocal2.get());
            // 输出: t1: thread-1, 200
            threadLocal1.remove(); // 用完记得清理
            threadLocal2.remove();
        });

        Thread t2 = new Thread(() -> {
            // 子线程没有设置过，threadLocal1.get() 返回 null（因为初始值为 null）
            System.out.println("t2: " + threadLocal1.get());
            // 输出: t2: null
            // threadLocal2 有初始值，get() 返回 0
            System.out.println("t2: " + threadLocal2.get());
            // 输出: t2: 0
        });

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("main: " + threadLocal1.get() + ", " + threadLocal2.get());
        // 输出: main: main-thread, 100
    }
}
```

关键点理解：
- `threadLocal1` 是同一个 ThreadLocal 对象，被三个线程（main、t1、t2）共享
- 但每个线程调用 `set()/get()` 操作的都是**自己线程内部的副本**
- t2 没有调用过 `set()`，所以 `threadLocal1.get()` 返回的是初始值 `null`（因为 `initialValue()` 默认返回 `null`）
- `threadLocal2` 通过 `withInitial` 设了初始值 `0`，所以 t2 调用 `get()` 时返回 `0`

---

## 三、底层存储结构全景

理解 ThreadLocal 的关键，是搞清楚**数据到底存在哪里**。很多人的第一反应是：值存在 ThreadLocal 对象里。但这是**错误的**。

### 3.1 核心关系：Thread → ThreadLocalMap → Entry[]

值实际上存在**每个线程自己的 ThreadLocalMap 里**。ThreadLocal 只是充当一个** key** 的角色。

来看 `Thread` 类的源码：

```java
// java.lang.Thread 类中的关键字段
public class Thread implements Runnable {
    // ... 其他字段省略

    // 每个线程都有一个 ThreadLocalMap，用于存储该线程的所有 ThreadLocal 变量
    // 这个字段的注释说得很清楚：
    // "ThreadLocal values pertaining to this thread.
    //  This map is maintained by the ThreadLocal class."
    ThreadLocal.ThreadLocalMap threadLocals = null;

    // 用于 InheritableThreadLocal，后面会讲
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;

    // ...
}
```

关键发现：**`threadLocals` 字段在 Thread 类中，不在 ThreadLocal 类中**。每个 Thread 对象都持有自己的 ThreadLocalMap。ThreadLocalMap 是 ThreadLocal 的静态内部类。

### 3.2 结构图

```
┌─────────────────────────────────────────────────────────────────────┐
│  Thread 对象（线程A）                                                  │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  ThreadLocalMap threadLocals                                  │    │
│  │                                                               │    │
│  │  ┌──────────┬──────────┬──────────┬──────────┬──────────┐   │    │
│  │  │ Entry[0] │ Entry[1] │ Entry[2] │ Entry[3] │  ......   │   │    │
│  │  ├──────────┼──────────┼──────────┼──────────┼──────────┤   │    │
│  │  │ key=TL_A │ key=TL_B │  null    │ key=TL_C │           │   │    │
│  │  │ val="x"  │ val=100  │ (空槽)    │ val=obj  │           │   │    │
│  │  └──────────┴──────────┴──────────┴──────────┴──────────┘   │    │
│  │      ↑ 弱引用        ↑ 弱引用               ↑ 弱引用           │    │
│  └───────┼───────────────┼──────────────────────┼───────────────┘    │
│          │               │                      │                     │
│          │   key是WeakReference<ThreadLocal<?>> │                     │
│          │               │                      │                     │
└──────────┼───────────────┼──────────────────────┼─────────────────────┘
           │               │                      │
    ┌──────▼──────┐ ┌──────▼──────┐        ┌──────▼──────┐
    │ ThreadLocal  │ │ ThreadLocal  │        │ ThreadLocal  │
    │   对象 TL_A  │ │   对象 TL_B  │        │   对象 TL_C  │
    │ (static字段) │ │ (static字段) │        │ (static字段) │
    └─────────────┘ └─────────────┘        └─────────────┘
```

要点总结：
1. **ThreadLocalMap 存在 Thread 对象中**，每个线程有自己的 Map
2. **ThreadLocalMap 的 key 是 ThreadLocal 对象本身**（弱引用），value 是实际存储的值（强引用）
3. **一个线程可以有多个 ThreadLocal 变量**，它们都存储在同一个 ThreadLocalMap 的 Entry 数组中
4. 当调用 `threadLocal.set(value)` 时，实际操作的是**当前线程**的 ThreadLocalMap，以 `this`（ThreadLocal 对象）为 key 存入值

这就解释了为什么不同线程操作同一个 ThreadLocal 对象能互不影响——因为它们操作的本来就是各自线程里不同的 Map。

---

## 四、ThreadLocalMap 源码深度解析

### 4.1 Entry 的设计：弱引用 key + 强引用 value

```java
// ThreadLocal.ThreadLocalMap.Entry
static class ThreadLocalMap {

    // Entry 继承自 WeakReference<ThreadLocal<?>>
    // 也就是说，Entry 的 key（ThreadLocal 对象）是被弱引用指向的
    static class Entry extends WeakReference<ThreadLocal<?>> {
        // value 是强引用，直接持有实际值对象
        Object value;

        // 构造方法：key 是 ThreadLocal，value 是实际值
        Entry(ThreadLocal<?> k, Object v) {
            super(k); // 调用 WeakReference 的构造方法，将 k 作为弱引用指向的对象
            value = v; // value 用强引用持有
        }
    }

    // ... 其他字段和方法
}
```

逐行解释：

1. `Entry extends WeakReference<ThreadLocal<?>>`：Entry 继承了弱引用，泛型参数是 `ThreadLocal<?>`。这意味着 Entry 本质上是一个"指向 ThreadLocal 对象的弱引用"+ 一个 value 字段。

2. `super(k)`：调用 `WeakReference(ThreadLocal<?> k)`，将 ThreadLocal 对象 `k` 作为弱引用的目标。弱引用的特点是：**当对象只被弱引用指向时，下次 GC 就会被回收**。

3. `value = v`：value 是一个普通的 Object 引用，是**强引用**。

**为什么要设计成弱引用 key？**

假设有一个静态的 ThreadLocal 变量：

```java
public class MyClass {
    // 静态变量，生命周期与类一样长
    static ThreadLocal<BigObject> threadLocal = new ThreadLocal<>();
}
```

如果在某处执行了 `threadLocal = null`（释放了静态引用），那么：
- **如果 key 是强引用**：ThreadLocalMap 的 Entry 会一直强引用 ThreadLocal 对象，导致 ThreadLocal 对象永远无法被 GC 回收。而且 Entry 还强引用着 value，造成双重泄漏。
- **如果 key 是弱引用（当前设计）**：当外部不再强引用 ThreadLocal 对象时，GC 会回收 ThreadLocal 对象。Entry 的 key 变成 null（被清理）。虽然 value 还在（这是残留的泄漏），但至少 key 被回收了，后续的清理机制可以处理掉 value。

简单说：**弱引用是第一道防线，让 key 能被 GC 回收，为后续的清理机制创造条件**。

**为什么 value 是强引用？**

因为 value 是用户实际存储的数据，ThreadLocal 必须可靠地持有它。如果 value 也是弱引用，那么用户存进去的数据随时可能被 GC 回收，这是不可接受的。

**value 强引用正是内存泄漏的根源**——后面会详细分析。

### 4.2 初始容量、扩容因子、哈希计算

```java
static class ThreadLocalMap {

    // 初始容量：16
    // 注意：必须是 2 的幂次方，方便用位运算取模
    private static final int INITIAL_CAPACITY = 16;

    // 底层存储数组
    private Entry[] table;

    // 当前已存放的 Entry 数量
    private int size = 0;

    // 扩容阈值（load factor 固定为 2/3）
    private int threshold; // 默认值为 0，在初始化时设置为 len * 2/3

    // 设置扩容阈值
    private void setThreshold(int len) {
        threshold = len * 2 / 3; // 负载因子 = 2/3 ≈ 0.667
    }
```

容量和扩容规则：
- 初始容量 16，必须是 2 的幂次
- 负载因子 2/3（比 HashMap 的 0.75 略低），因为 ThreadLocalMap 使用开放地址法（线性探测），冲突率不能太高
- 当 `size >= threshold` 时触发 `rehash()`，rehash 会先清理过期 Entry，然后如果清理后 size 仍然 > threshold 的 3/4，就进行扩容

### 4.3 黄金分割数 0x61c88647 的魔数哈希

```java
// ThreadLocal 类中的方法
private final int threadLocalHashCode = nextHashCode();

// 原子计数器，每次创建新的 ThreadLocal 时自增
private static AtomicInteger nextHashCode =
    new AtomicInteger();

// 黄金分割数（unsigned 32-bit）
private static final int HASH_INCREMENT = 0x61c88647;

// 生成下一个哈希码
private static int nextHashCode() {
    return nextHashCode.getAndAdd(HASH_INCREMENT); // 自增 HASH_INCREMENT
}
```

`0x61c88647` 是什么？这是一个特殊的常数，它的数学意义是 **(2^32 - 1) × (√5 - 1) / 2**，也就是无符号 32 位整数的黄金分割比例。

为什么用它？因为 ThreadLocalMap 使用**开放地址法（线性探测）**，不使用链表，所以**哈希分布的均匀性至关重要**。如果分布不均匀，会产生大量连续冲突，线性探测的性能急剧下降。

`0x61c88647` 的奇妙之处在于：连续累加这个值再对 2 的幂次取模，能让序列**均匀散布在数组各处**，几乎不会出现连续聚集。

我们来验证一下效果：

```java
// 模拟连续创建 ThreadLocal 时的哈希分布
int len = 16;
int hash = 0;
for (int i = 0; i < 16; i++) {
    hash += 0x61c88647; // 每次累加
    int slot = hash & (len - 1); // 等价于 hash % len（当 len 是 2 的幂次时）
    System.out.println("第 " + i + " 个 ThreadLocal -> 槽位: " + slot);
}
// 输出（十进制，可能有负数因为 Java int 是有符号的，但位运算后取模结果是对的）：
// 第 0 个 -> 槽位: 0
// 第 1 个 -> 槽位: 7
// 第 2 个 -> 槽位: 14
// 第 3 个 -> 槽位: 5
// 第 4 个 -> 槽位: 12
// 第 5 个 -> 槽位: 3
// 第 6 个 -> 槽位: 10
// ...
```

可以看到槽位分布非常均匀，几乎不会出现连续冲突。这就是黄金分割数的威力。

注意与 HashMap 的区别：
- HashMap 使用链地址法（拉链法），冲突时在同一个桶上挂链表
- ThreadLocalMap 使用开放地址法（线性探测法），冲突时向后寻找下一个空位
- 所以 ThreadLocalMap 更需要均匀的哈希分布

### 4.4 set() 方法源码逐行分析

先看 ThreadLocal 的 `set()` 方法入口：

```java
// java.lang.ThreadLocal.set()
public void set(T value) {
    // 1. 获取当前线程
    Thread t = Thread.currentThread();
    // 2. 获取当前线程的 ThreadLocalMap
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        // 3. 如果 Map 已存在，直接调用 Map 的 set 方法
        map.set(this, value);
    } else {
        // 4. 如果 Map 还不存在（第一次调用），创建 Map
        createMap(t, value);
    }
}

// 获取线程的 ThreadLocalMap
ThreadLocalMap getMap(Thread t) {
    return t.threadLocals; // 直接返回 Thread 对象的 threadLocals 字段
}

// 创建并初始化 ThreadLocalMap
void createMap(Thread t, T firstValue) {
    t.threadLocals = new ThreadLocalMap(this, firstValue);
}
```

流程很清晰：
1. 获取当前线程
2. 获取线程的 `threadLocals` 字段
3. 如果为 null（线程第一次使用 ThreadLocal），创建新的 ThreadLocalMap
4. 如果不为 null，调用 `ThreadLocalMap.set(this, value)`，注意 key 是 `this`（ThreadLocal 对象本身）

现在看 `ThreadLocalMap.set()` 方法——**这是最复杂的核心方法**：

```java
// ThreadLocal.ThreadLocalMap.set()
private void set(ThreadLocal<?> key, Object value) {

    // 我们不使用显式的引用队列——而是使用 key == null 来判断过期 Entry
    Entry[] tab = table;
    int len = tab.length;
    
    // 1. 计算哈希槽位
    //    使用 threadLocalHashCode & (len - 1) 取模
    //    等价于 threadLocalHashCode % len（因为 len 是 2 的幂次）
    int i = key.threadLocalHashCode & (len-1);

    // 2. 从槽位 i 开始线性探测
    //    使用快速路径：先探测最近的几个槽位
    for (Entry e = tab[i];
         e != null;              // 遇到 null 槽位就停止
         e = tab[i = nextIndex(i, len)]) {  // 否则移到下一个槽位（环形）

        ThreadLocal<?> k = e.get(); // 获取 Entry 的 key（弱引用）

        if (k == key) {
            // 2a. 找到了 key 相同的 Entry —— 直接替换 value
            e.value = value;
            return;
        }

        if (k == null) {
            // 2b. 遇到了过期 Entry（key 为 null，说明 ThreadLocal 被 GC 回收了）
            //     这不是简单的替换！需要调用 replaceStaleEntry 进行清理+设置
            replaceStaleEntry(key, value, i);
            return;
        }
        // 2c. 如果 key 不为 null 也不等于当前 key，继续向后探测
    }

    // 3. 循环结束说明找到了空槽位（tab[i] == null），在这里创建新 Entry
    tab[i] = new Entry(key, value);
    int sz = ++size;

    // 4. 启发式清理：尝试清理一些过期 Entry
    //    cleanSomeSlots 返回 true 表示清理了过期 Entry（说明可能还有更多）
    //    如果返回 false 且 size 达到阈值，则触发 rehash
    if (!cleanSomeSlots(i, sz) && sz >= threshold) {
        rehash(); // 重新哈希：先清理所有过期 Entry，再判断是否需要扩容
    }
}
```

逐步解析这段代码的执行逻辑：

**第 1 步：计算初始槽位**

```java
int i = key.threadLocalHashCode & (len-1);
```

用 ThreadLocal 对象的哈希码（通过黄金分割数累加得到的）对数组长度取模，得到初始槽位。位运算 `& (len-1)` 比 `%` 运算更快，但要求 len 是 2 的幂次。

**第 2 步：线性探测循环**

从槽位 `i` 开始，依次检查每个位置（到达数组末尾时回绕到开头，即环形数组）：

- 如果 `tab[i]` 为 null → 探测结束，在这里放新 Entry
- 如果 `tab[i]` 的 key == 当前 key → 已存在，直接替换 value，return
- 如果 `tab[i]` 的 key == null → 过期 Entry（ThreadLocal 已被 GC 回收），调用 `replaceStaleEntry`
- 否则 → 继续探测下一个位置

`nextIndex` 的实现——环形数组：

```java
private static int nextIndex(int i, int len) {
    // 如果当前不是最后一个，返回下一个
    // 如果是最后一个，回绕到 0
    return ((i + 1 < len) ? i + 1 : 0);
}

private static int prevIndex(int i, int len) {
    // 反向探测
    return ((i - 1 >= 0) ? i - 1 : len - 1);
}
```

**第 3 步：创建新 Entry**

如果循环正常结束（遇到 null 槽位），在空槽位创建新的 Entry，size 加 1。

**第 4 步：启发式清理 + 扩容判断**

```java
if (!cleanSomeSlots(i, sz) && sz >= threshold) {
    rehash();
}
```

`cleanSomeSlots(i, sz)` 会以对数复杂度探测是否有过期 Entry 需要清理。如果清理了（返回 true），说明可能有更多过期 Entry，不急着扩容。如果没清理到（返回 false）且 size 达到阈值，就执行 `rehash()`。

注意 set 方法中有一个重要设计：**遇到过期 Entry（k == null）时，不是简单地覆盖它**，而是调用 `replaceStaleEntry`，这个方法会同时清理过期 Entry 并将新值放到合适的位置。

### 4.5 replaceStaleEntry 方法

```java
// 在发现过期 Entry 后调用
// staleSlot 是发现过期 Entry 的位置
private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                               int staleSlot) {
    Entry[] tab = table;
    int len = tab.length;
    Entry e;

    // 1. 向前扫描，找到最前面的一个过期 Entry
    //    这是为了确保后续 expungeStaleEntry 能清理最大范围
    int slotToExpunge = staleSlot;
    for (int i = prevIndex(staleSlot, len);
         (e = tab[i]) != null;
         i = prevIndex(i, len)) {
        if (e.get() == null) {
            slotToExpunge = i; // 更新最前面的过期 Entry 位置
        }
    }

    // 2. 从 staleSlot 向后扫描，寻找 key 匹配的 Entry 或空槽位
    for (int i = nextIndex(staleSlot, len);
         (e = tab[i]) != null;
         i = nextIndex(i, len)) {
        ThreadLocal<?> k = e.get();

        if (k == key) {
            // 2a. 找到匹配的 key：交换位置（把匹配的 Entry 移到 staleSlot）
            //     这样可以让 key 尽量靠近它的哈希槽位，减少后续探测距离
            e.value = value; // 更新 value
            tab[i] = tab[staleSlot]; // 把过期 Entry 移到后面
            tab[staleSlot] = e;      // 把匹配的 Entry 放到 staleSlot

            // 如果向前扫描没有找到更早的过期 Entry，
            // 那就从交换后的位置开始清理
            if (slotToExpunge == staleSlot) {
                slotToExpunge = i;
            }
            // 执行清理（从 slotToExpunge 开始）
            cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
            return;
        }

        if (k == null && slotToExpunge == staleSlot) {
            // 2b. 向后扫描时又遇到过期 Entry，更新清理起点
            slotToExpunge = i;
        }
    }

    // 3. 如果没找到匹配的 key，在 staleSlot 位置创建新 Entry
    //    （复用过期 Entry 的位置）
    tab[staleSlot].value = null; // 清理旧 value
    tab[staleSlot] = new Entry(key, value);

    // 4. 如果存在其他过期 Entry，清理它们
    if (slotToExpunge != staleSlot) {
        cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
    }
}
```

这个方法做了三件事：
1. 向前找到最早的过期 Entry 位置（扩大清理范围）
2. 向后查找是否已存在相同 key 的 Entry，如果找到就交换位置并清理
3. 如果没找到，在过期位置创建新 Entry，然后清理其他过期 Entry

### 4.6 get() 方法源码逐行分析

先看 ThreadLocal 的 `get()` 方法：

```java
// java.lang.ThreadLocal.get()
public T get() {
    // 1. 获取当前线程
    Thread t = Thread.currentThread();
    // 2. 获取当前线程的 ThreadLocalMap
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        // 3. 在 Map 中查找
        ThreadLocalMap.Entry e = map.getEntry(this);
        if (e != null) {
            @SuppressWarnings("unchecked")
            T result = (T)e.value;
            return result;
        }
    }
    // 4. 如果 Map 不存在或没找到，调用 setInitialValue
    return setInitialValue();
}

// setInitialValue：设置初始值
private T setInitialValue() {
    // 调用 initialValue 获取初始值（默认返回 null）
    T value = initialValue();
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        map.set(this, value); // 将初始值存入 Map
    } else {
        createMap(t, value); // 创建 Map 并存入初始值
    }
    return value;
}
```

注意 `get()` 方法的一个隐含行为：**如果当前线程还没有为这个 ThreadLocal 设置过值，`get()` 会调用 `initialValue()` 并将结果存入 Map**。这意味着第一次 `get()` 也会创建 Entry。

现在看 `ThreadLocalMap.getEntry()`：

```java
// ThreadLocalMap.getEntry()
private Entry getEntry(ThreadLocal<?> key) {
    // 1. 计算哈希槽位
    int i = key.threadLocalHashCode & (table.length - 1);
    Entry e = table[i];
    if (e != null && e.get() == key) {
        // 2. 快速路径：直接命中！
        //    槽位 i 的 Entry 不为 null 且 key 匹配
        return e;
    } else {
        // 3. 没有直接命中，可能发生了哈希冲突（被线性探测到了其他位置）
        //    或者该位置是过期 Entry
        return getEntryAfterMiss(key, i, e);
    }
}
```

**快速路径**是最理想的情况：哈希槽位直接就是目标 Entry，O(1) 复杂度。

如果没有命中，走 `getEntryAfterMiss`：

```java
// getEntryAfterMiss：哈希冲突后的线性探测查找
private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
    Entry[] tab = table;
    int len = tab.length;

    // 从槽位 i 开始向后线性探测
    while (e != null) {
        ThreadLocal<?> k = e.get();

        if (k == key) {
            // 1. 找到匹配的 key，返回
            return e;
        }

        if (k == null) {
            // 2. 遇到过期 Entry（key 被 GC 回收了）
            //    顺带触发清理！这是一个被动清理机制
            expungeStaleEntry(i);
        }

        // 3. 移到下一个槽位
        i = nextIndex(i, len);
        e = tab[i];
    }
    // 4. 遇到 null 槽位，说明 key 不存在，返回 null
    return null;
}
```

`get()` 方法的一个精妙之处：**在查找过程中如果遇到过期 Entry，会顺带调用 `expungeStaleEntry` 清理**。这是一种被动清理策略，不需要专门起一个清理线程，而是在正常的 get 操作中"顺便"清理。

### 4.7 remove() 方法源码分析

```java
// java.lang.ThreadLocal.remove()
public void remove() {
    // 1. 获取当前线程的 ThreadLocalMap
    ThreadLocalMap m = getMap(Thread.currentThread());
    if (m != null) {
        // 2. 调用 Map 的 remove 方法
        m.remove(this);
    }
}

// ThreadLocalMap.remove()
private void remove(ThreadLocal<?> key) {
    Entry[] tab = table;
    int len = tab.length;
    
    // 1. 计算哈希槽位
    int i = key.threadLocalHashCode & (len-1);
    
    // 2. 线性探测查找
    for (Entry e = tab[i];
         e != null;
         e = tab[i = nextIndex(i, len)]) {
        if (e.get() == key) {
            // 3. 找到目标 Entry：
            //    3a. 清除弱引用（将 Reference 的 referent 置为 null）
            e.clear();
            //    3b. 清理该位置及其后的过期 Entry
            expungeStaleEntry(i);
            return;
        }
    }
}
```

`remove()` 的流程很简单：
1. 找到对应的 Entry
2. 调用 `e.clear()` —— 这是 `WeakReference.clear()` 方法，将弱引用指向的对象置为 null（此时 Entry 的 key 变为 null，但 Entry 本身还在数组中，value 还在）
3. 调用 `expungeStaleEntry(i)` —— 彻底清理这个 Entry 以及它后面连续的过期 Entry（将 Entry 从数组中移除，value 置为 null，帮助 GC 回收 value）

`e.clear()` 的源码（在 `java.lang.ref.Reference` 中）：

```java
public void clear() {
    this.referent = null; // 直接将引用对象置为 null
}
```

注意：**仅仅调用 `e.clear()` 是不够的**，因为虽然 key 变成了 null，但 Entry 对象还在数组中，value 还被强引用着。必须配合 `expungeStaleEntry` 才能彻底释放。

### 4.8 rehash 与扩容

```java
// ThreadLocalMap.rehash()
private void rehash() {
    // 1. 全量清理：扫描整个数组，清理所有过期 Entry
    expungeStaleEntries();

    // 2. 清理后如果 size 仍然超过 threshold 的 3/4，才真正扩容
    //    注意这里用的是 threshold / 4 * 3 = threshold * 0.75
    //    即 size >= threshold - threshold/4 = threshold * 3/4
    if (size >= threshold - threshold / 4) {
        resize();
    }
}

// 全量清理过期 Entry
private void expungeStaleEntries() {
    Entry[] tab = table;
    int len = tab.length;
    for (int j = 0; j < len; j++) {
        Entry e = tab[j];
        if (e != null && e.get() == null) {
            expungeStaleEntry(j); // 清理每个过期 Entry
        }
    }
}

// 扩容：容量翻倍
private void resize() {
    Entry[] oldTab = table;
    int oldLen = oldTab.length;
    int newLen = oldLen * 2; // 容量翻倍
    Entry[] newTab = new Entry[newLen];
    int count = 0;

    // 将旧数组的 Entry 重新哈希到新数组
    for (int j = 0; j < oldLen; ++j) {
        Entry e = oldTab[j];
        if (e != null) {
            ThreadLocal<?> k = e.get();
            if (k == null) {
                // 过期 Entry，直接丢弃（帮助回收 value）
                e.value = null;
            } else {
                // 重新计算槽位
                int h = k.threadLocalHashCode & (newLen - 1);
                while (newTab[h] != null) {
                    h = nextIndex(h, newLen); // 线性探测找空位
                }
                newTab[h] = e;
                count++;
            }
        }
    }

    setThreshold(newLen); // 设置新的扩容阈值
    size = count;
    table = newTab;
}
```

扩容策略总结：
1. 先全量清理过期 Entry（可能清理后就不需要扩容了）
2. 如果清理后 size 仍然 >= threshold * 3/4，扩容为原来的 2 倍
3. 扩容时重新哈希所有 Entry（跳过过期 Entry）

---

## 五、内存泄漏问题深度剖析

### 5.1 泄漏场景的完整链路

这是 ThreadLocal 面试中被问得最多的问题。让我们一步一步梳理泄漏是如何发生的。

**前置条件：线程池场景**

```java
public class LeakDemo {
    // ThreadLocal 变量
    static ThreadLocal<BigObject> threadLocal = new ThreadLocal<>();

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 100; i++) {
            pool.execute(() -> {
                // 设置一个大对象
                threadLocal.set(new BigObject(1024 * 1024)); // 1MB
                // 使用...
                // 忘记 remove() 了！
            });
        }
    }
}
```

**泄漏链路分析：**

```
步骤 1：外部代码将 ThreadLocal 变量置为 null
    threadLocal = null;
    此时：GC Root（静态变量 threadLocal）不再指向 ThreadLocal 对象
    但 Entry.key（弱引用）仍然指向 ThreadLocal 对象

步骤 2：GC 发生
    由于 ThreadLocal 对象只被弱引用（Entry.key）指向
    GC 回收了 ThreadLocal 对象
    Entry.key 变为 null（弱引用指向的对象被回收后，get() 返回 null）

步骤 3：Entry 变成"残骸"
    此时 Entry 的状态：key = null, value = BigObject 对象
    value 仍然被 Entry 强引用着

步骤 4：引用链分析（为什么 value 无法回收）
    Thread（线程池核心线程，不会被销毁）
        → Thread.threadLocals（ThreadLocalMap）
            → ThreadLocalMap.table[]（Entry 数组）
                → Entry（key=null, value=BigObject）
                    → BigObject（强引用）

    只要线程不死（线程池核心线程默认不会被回收），这条引用链就一直存在
    BigObject 永远无法被 GC 回收 → 内存泄漏！
```

用图来表示这个引用链：

```
    GC Root
    ┌──────────────────┐
    │  Thread (线程池核心线程) │ ← 核心线程不会被销毁
    └────────┬─────────┘
             │ 强引用 (threadLocals 字段)
    ┌────────▼─────────┐
    │  ThreadLocalMap    │
    │  table[]           │
    └────────┬─────────┘
             │ 强引用 (数组元素)
    ┌────────▼─────────┐
    │  Entry             │
    │  key = null (弱引用，已被GC回收)  │
    │  value ────────────────┐ 强引用
    └──────────────────┘    │
                            │
                    ┌───────▼────────┐
                    │  BigObject (1MB) │ ← 永远无法被回收！
                    └────────────────┘
```

### 5.2 为什么设计成弱引用 key？

**假设 key 是强引用会怎样？**

```
外部引用 threadLocal = null 后：

    Thread (核心线程，不死)
        → ThreadLocalMap
            → Entry
                → key (强引用) → ThreadLocal 对象 ← 连 key 都回收不了！
                → value (强引用) → BigObject ← value 也回收不了

双重泄漏：ThreadLocal 对象和 value 都无法回收
```

对比弱引用：

| 设计 | key 能否回收 | value 能否回收 | 泄漏严重程度 |
|------|-------------|---------------|------------|
| 强引用 key | 不能 | 不能 | 严重（双重泄漏） |
| 弱引用 key（当前设计） | 能（GC后 key=null） | 不能（需手动清理） | 较轻（可通过清理机制解决） |

所以弱引用是一种**兜底设计**：至少让 key 能被回收，使 Entry 变成可识别的"过期 Entry"（key == null），为后续的被动清理机制创造条件。

### 5.3 弱引用只是兜底，真正的解决方案是 remove()

弱引用只是减少了泄漏的严重程度，**并没有从根本上解决 value 的泄漏问题**。虽然 ThreadLocalMap 有被动清理机制（get/set/remove 时会顺带清理过期 Entry），但：

1. **不是实时清理**：只有当线程再次调用 get/set/remove 且碰巧遇到过期 Entry 时才会清理
2. **可能永远不触发**：如果线程之后不再使用任何 ThreadLocal，清理永远不会触发
3. **cleanSomeSlots 是启发式的**：只做对数级别的探测，不一定能清理所有过期 Entry

**根本解决方案：使用完务必调用 remove()**

```java
// 正确姿势：try-finally 中 remove
ThreadLocal<User> userThreadLocal = new ThreadLocal<>();
try {
    userThreadLocal.set(currentUser);
    // 业务逻辑...
    doSomething();
} finally {
    userThreadLocal.remove(); // 无论是否异常，都执行 remove
}
```

`remove()` 会直接将 Entry 从数组中移除，同时清除 value 的引用，彻底断开引用链，让 value 可被 GC 回收。

### 5.4 最佳实践总结

```java
// 1. ThreadLocal 变量尽量声明为 private static
//    避免随实例创建大量 ThreadLocal 对象
private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

// 2. 使用后务必 remove
public String formatDate(Date date) {
    try {
        return DATE_FORMAT.get().format(date);
    } finally {
        DATE_FORMAT.remove(); // 线程池场景必须 remove！
    }
}

// 3. 在框架层面统一处理（如拦截器/Filter）
public class ThreadLocalFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) {
        try {
            UserContext.set(getUser(req));
            chain.doFilter(req, resp);
        } finally {
            UserContext.remove(); // 请求结束统一清理
        }
    }
}
```

---

## 六、expungeStaleEntry 清理机制源码分析

ThreadLocalMap 有两套清理机制，这是理解 ThreadLocal 内存管理的关键。

### 6.1 expungeStaleEntry：全量连续清理

这是最核心的清理方法，在 get、set、remove 中都会被调用。

```java
// staleSlot 是发现过期 Entry 的位置
// 返回值：下一个需要清理的槽位（用于 cleanSomeSlots 继续探测）
private int expungeStaleEntry(int staleSlot) {
    Entry[] tab = table;
    int len = tab.length;

    // 1. 清理起始位置的过期 Entry
    tab[staleSlot].value = null; // 断开 value 的强引用，帮助 GC
    tab[staleSlot] = null;       // 从数组中移除 Entry
    size--;                       // 数量减 1

    Entry e;
    int i;
    // 2. 从 staleSlot+1 开始向后扫描，直到遇到 null 槽位
    //    注意：连续的 Entry 区间内（中间没有 null）可能有更多过期 Entry
    //    同时需要重新哈希非过期 Entry（因为清理后空出来的槽位可能影响探测路径）
    for (i = nextIndex(staleSlot, len);
         (e = tab[i]) != null;
         i = nextIndex(i, len)) {
        ThreadLocal<?> k = e.get();
        if (k == null) {
            // 2a. 又发现过期 Entry，清理
            e.value = null;
            tab[i] = null;
            size--;
        } else {
            // 2b. 非过期 Entry：重新计算它的理想槽位
            int h = k.threadLocalHashCode & (len - 1);
            if (h != i) {
                // 如果当前槽位 i 不是它的理想槽位 h
                // 说明它是因为冲突被探测到这里的
                // 现在前面可能有空位了，应该把它移回更靠近理想槽位的位置
                tab[i] = null; // 先清空当前位置

                // 从理想槽位 h 开始线性探测，找到新的空位
                while (tab[h] != null) {
                    h = nextIndex(h, len);
                }
                tab[h] = e; // 放到更靠前的位置
            }
            // 如果 h == i，说明本来就在理想位置，不需要移动
        }
    }
    // 3. 返回扫描结束时的 null 槽位位置
    //    cleanSomeSlots 会从这里继续探测
    return i;
}
```

这个方法做了三件事：
1. 清理起始位置的过期 Entry
2. 向后扫描直到遇到 null，清理沿途的过期 Entry
3. 对非过期的 Entry 进行**rehash（重新哈希）**——将它们移到更靠近理想槽位的位置，优化后续查找性能

为什么需要重新哈希？举个例子：

```
假设数组长度 8，ThreadLocal A 的理想槽位是 2，ThreadLocal B 的理想槽位也是 2

初始状态：
[_, _, A, B, _, _, _, _]
         ↑ 槽位2    ↑ 槽位3（B因冲突被探测到这里）

如果 A 变成过期 Entry 并被 expungeStaleEntry 清理：

清理后（不重新哈希）：
[_, _, null, B, _, _, _, _]
         ↑ 空槽位   ↑ B还在槽位3

此时如果查找 B：从槽位2开始探测，遇到 null 就停了！找不到 B！
这就是 BUG！

所以必须重新哈希：把 B 从槽位3移到槽位2

清理后（重新哈希）：
[_, _, B, null, _, _, _, _]
         ↑ B移到了槽位2
```

这就是为什么 `expungeStaleEntry` 中非过期 Entry 需要重新计算位置——**线性探测法依赖连续性，清理产生的空槽位会打断探测链**。

### 6.2 cleanSomeSlots：对数探测清理

```java
// i 是一个已知的位置（通常是刚插入的位置）
// n 是当前 Map 的 size
// 返回 true 如果清理了任何过期 Entry
private boolean cleanSomeSlots(int i, int n) {
    boolean removed = false;
    Entry[] tab = table;
    int len = tab.length;
    do {
        // i 移到下一个位置
        i = nextIndex(i, len);
        Entry e = tab[i];
        if (e != null && e.get() == null) {
            // 发现过期 Entry！
            n = len; // 重置 n 为数组长度，扩大后续探测范围
            removed = true;
            // 调用 expungeStaleEntry 清理（会清理连续的过期 Entry）
            i = expungeStaleEntry(i);
        }
    } while (n > (n >>>= 1)); // n 每次右移一位（除以2），直到 n == 0
    // 循环次数约为 log2(n)
    return removed;
}
```

这个方法的特点：
- **对数复杂度**：探测次数约为 `log2(n)`，不是全量扫描
- **发现过期 Entry 就扩大范围**：一旦发现过期 Entry，将 n 重置为数组长度，扩大后续探测范围
- **没发现就快速结束**：如果一路没发现过期 Entry，很快退出

**为什么不全量扫描？**

全量扫描（遍历整个数组）时间复杂度是 O(n)，而 ThreadLocalMap 的设计目标是在 set/get 时保持高效。如果每次 set 都全量扫描，性能会显著下降。

对数探测是一种**权衡**：用 O(log n) 的代价来"碰运气"式地发现过期 Entry。配合 `expungeStaleEntry` 的连续清理，在实际使用中能清理掉大部分过期 Entry，同时保持良好的性能。

### 6.3 清理机制触发时机总结

| 触发点 | 调用的清理方法 | 说明 |
|--------|---------------|------|
| `set()` 遇到过期 Entry | `replaceStaleEntry` → `expungeStaleEntry` + `cleanSomeSlots` | 最全面的清理 |
| `set()` 插入新 Entry 后 | `cleanSomeSlots` | 启发式探测 |
| `set()` size 达到阈值 | `rehash` → `expungeStaleEntries`（全量清理） | 最彻底的清理 |
| `get()` 遇到过期 Entry | `expungeStaleEntry` | 被动清理 |
| `remove()` | `expungeStaleEntry` | 清理当前位置及后续 |

---

## 七、InheritableThreadLocal

### 7.1 解决的问题

普通 ThreadLocal 的值是**线程隔离**的，子线程无法获取父线程的 ThreadLocal 值：

```java
ThreadLocal<String> tl = new ThreadLocal<>();
tl.set("parent-value");

Thread child = new Thread(() -> {
    System.out.println(tl.get()); // 输出: null —— 子线程拿不到父线程的值！
});
child.start();
```

`InheritableThreadLocal` 解决了这个问题：**在创建子线程时，将父线程的值复制给子线程**。

```java
InheritableThreadLocal<String> itl = new InheritableThreadLocal<>();
itl.set("parent-value");

Thread child = new Thread(() -> {
    System.out.println(itl.get()); // 输出: parent-value —— 子线程拿到了！
});
child.start();
```

### 7.2 底层实现

回顾 Thread 类中的两个字段：

```java
public class Thread implements Runnable {
    // 普通 ThreadLocal 使用的 Map
    ThreadLocal.ThreadLocalMap threadLocals = null;

    // InheritableThreadLocal 使用的 Map
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;
}
```

InheritableThreadLocal 使用的是 Thread 的 `inheritableThreadLocals` 字段，而不是 `threadLocals`。这两个 Map 是完全独立的。

来看 `InheritableThreadLocal` 的源码：

```java
public class InheritableThreadLocal<T> extends ThreadLocal<T> {

    // 子线程获取值时的计算方法
    // 默认直接返回父线程的值，子类可以重写来修改传递的值
    protected T childValue(T parentValue) {
        return parentValue;
    }

    // 重写 getMap：返回 Thread 的 inheritableThreadLocals 而不是 threadLocals
    ThreadLocalMap getMap(Thread t) {
        return t.inheritableThreadLocals;
    }

    // 重写 createMap：创建 inheritableThreadLocals
    void createMap(Thread t, T firstValue) {
        t.inheritableThreadLocals = new ThreadLocalMap(this, firstValue);
    }
}
```

核心机制在于 **Thread 的构造方法**。当 new Thread() 时，会调用 `init()` 方法：

```java
// java.lang.Thread.init()（简化版，保留关键逻辑）
private void init(ThreadGroup g, Runnable target, String name,
                  long stackSize, AccessControlContext acc,
                  boolean inheritThreadLocals) {
    // ... 其他初始化逻辑省略 ...

    Thread parent = currentThread(); // 获取当前线程（父线程）

    // 关键：如果父线程有 inheritableThreadLocals 且允许继承
    if (inheritThreadLocals && parent.inheritableThreadLocals != null) {
        // 将父线程的 inheritableThreadLocals 复制给子线程
        this.inheritableThreadLocals =
            ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
    }

    // ... 其他初始化逻辑省略 ...
}
```

`createInheritedMap` 方法：

```java
// ThreadLocal.createInheritedMap
static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
    return new ThreadLocalMap(parentMap);
}

// ThreadLocalMap 的私有构造方法：从父 Map 复制
private ThreadLocalMap(ThreadLocalMap parentMap) {
    Entry[] parentTable = parentMap.table;
    int len = parentTable.length;
    setThreshold(len);
    table = new Entry[len];

    // 遍历父 Map 的每个 Entry
    for (int j = 0; j < len; j++) {
        Entry e = parentTable[j];
        if (e != null) {
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
            if (key != null) {
                // 调用 key.childValue(e.value) 计算子线程的值
                // 对于 InheritableThreadLocal，childValue 默认返回原值
                Object value = key.childValue(e.value);
                Entry entry = new Entry(key, value);
                // 重新计算槽位
                int h = key.threadLocalHashCode & (len - 1);
                while (table[h] != null) {
                    h = nextIndex(h, len);
                }
                table[h] = entry;
                size++;
            }
        }
    }
}
```

关键逻辑：
1. 遍历父线程的 `inheritableThreadLocals` 中的所有 Entry
2. 对每个 Entry，调用 `childValue()` 计算子线程的值（默认直接返回父值）
3. 在子线程的 Map 中创建新 Entry

注意：复制的是**浅拷贝**——如果 value 是引用类型，父子线程操作的是同一个对象。

### 7.3 childValue 的自定义

```java
// 自定义 InheritableThreadLocal：传递副本而不是引用
InheritableThreadLocal<List<String>> itl = new InheritableThreadLocal<List<String>>() {
    @Override
    protected List<String> childValue(List<String> parentValue) {
        // 返回父值的副本，避免父子线程操作同一对象
        return new ArrayList<>(parentValue);
    }
};
```

### 7.4 局限性

**InheritableThreadLocal 只在 `new Thread()` 时传递一次。**

在线程池场景下，线程是**复用**的：

```java
ExecutorService pool = Executors.newFixedThreadPool(4);

InheritableThreadLocal<String> itl = new InheritableThreadLocal<>();
itl.set("main-value");

// 第一个任务：能拿到 "main-value"（因为线程首次创建时复制了父线程的值）
pool.execute(() -> {
    System.out.println(itl.get()); // 可能输出: main-value
});

itl.set("updated-value"); // 更新值

// 第二个任务：拿不到 "updated-value"！
// 因为线程已经创建过了（被线程池复用），不会再次执行 init() 复制逻辑
pool.execute(() -> {
    System.out.println(itl.get()); // 输出的还是旧值！
});
```

这就引出了下一节的 TransmittableThreadLocal。

---

## 八、TransmittableThreadLocal（TTL）

### 8.1 解决的问题

`InheritableThreadLocal` 在 `new Thread()` 时传递值，但**线程池中线程是复用的**，不会重新执行 Thread 的 init() 方法，所以 InheritableThreadLocal 的值无法在线程池场景下正确传递。

`TransmittableThreadLocal`（简称 TTL）是**阿里巴巴开源的解决方案**（[GitHub: alibaba/transmittable-thread-local](https://github.com/alibaba/transmittable-thread-local)），专门解决线程池场景下的上下文传递问题。

核心思想：**在任务提交到线程池时"拍快照"，在任务执行前"恢复快照"，任务执行后"还原"**。

### 8.2 核心原理：capture-replay-restore

三个关键时机：

```
提交任务时（提交线程）
    ↓ capture()：抓取当前线程的所有 TTL 值快照
    ↓
任务被调度执行时（池中线程）
    ↓ replay()：将快照值设置到当前执行线程
    ↓
执行业务任务
    ↓
任务执行后（池中线程）
    ↓ restore()：还原执行线程之前的 TTL 值（避免污染下一个任务）
```

### 8.3 使用方式

**方式一：用 TtlRunnable 装饰任务**

```java
TransmittableThreadLocal<String> context = new TransmittableThreadLocal<>();
context.set("trace-id-123");

ExecutorService pool = Executors.newFixedThreadPool(4);

// 用 TtlRunnable 包装
pool.execute(TtlRunnable.get(() -> {
    System.out.println(context.get()); // 输出: trace-id-123 ✓
}));

// 更新值后再次提交
context.set("trace-id-456");
pool.execute(TtlRunnable.get(() -> {
    System.out.println(context.get()); // 输出: trace-id-456 ✓ 每次都拿最新的！
}));
```

**方式二：用 TtlExecutors 代理线程池（更优雅）**

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
// 用 TtlExecutors 包装线程池
ExecutorService ttlPool = TtlExecutors.getTtlExecutorService(pool);

// 之后直接提交 Runnable，无需手动包装
context.set("value-1");
ttlPool.execute(() -> {
    System.out.println(context.get()); // 输出: value-1 ✓
});

context.set("value-2");
ttlPool.execute(() -> {
    System.out.println(context.get()); // 输出: value-2 ✓
});
```

方式二的原理：代理线程池会在 `execute()` 方法内部自动用 `TtlRunnable` 包装任务。

### 8.4 源码原理简析

**TransmittableThreadLocal 类核心逻辑：**

```java
public class TransmittableThreadLocal<T> extends ThreadLocal<T> implements TtlCopier<T> {

    // 持有者：每个 TTL 实例会注册到 holder 中
    // holder 本身也是一个 ThreadLocal，存储当前线程所有使用过的 TTL 实例
    private static volatile ThreadLocal<WeakHashMap<TransmittableThreadLocal<Object>, ?>> holder =
        new ThreadLocal<WeakHashMap<TransmittableThreadLocal<Object>, ?>>() {
            @Override
            protected WeakHashMap<TransmittableThreadLocal<Object>, ?> initialValue() {
                return new WeakHashMap<>();
            }
        };

    @Override
    public void set(T value) {
        super.set(value); // 先正常设置到当前线程
        if (null == value) {
            removeValueFromHolder(); // 如果设置为 null，从 holder 移除
        } else {
            addValueToHolder(); // 否则注册到 holder
        }
    }

    // 抓取快照（在提交任务时调用）
    public static TransmittableThreadLocal.Transmitter.TransmitCallback capture() {
        // 遍历 holder 中所有 TTL 实例，获取它们的值
        // 返回一个快照对象
        // ...
    }

    // 恢复快照（在任务执行前调用）
    public static Object replay(Object captured) {
        // 将快照中的值设置到当前执行线程
        // 同时备份当前线程的原有值（用于后续 restore）
        // ...
    }

    // 还原（在任务执行后调用）
    public static void restore(Object backup) {
        // 将当前线程的 TTL 值恢复为备份值
        // ...
    }

    // childValue 和 InheritableThreadLocal 一样
    @Override
    protected T childValue(T parentValue) {
        return parentValue;
    }
}
```

核心设计：
1. **holder**：一个 ThreadLocal<WeakHashMap<TransmittableThreadLocal, ?>>，记录当前线程使用过的所有 TTL 实例。当调用 `set()` 时自动注册。
2. **capture()**：遍历 holder，收集所有 TTL 的当前值，生成快照。
3. **replay()**：将快照中的值设置到执行线程，同时备份执行线程的原值。
4. **restore()**：将执行线程的 TTL 值恢复为备份值，防止任务间互相污染。

**TtlRunnable 的核心逻辑：**

```java
public final class TtlRunnable implements Runnable {

    private final AtomicReference<Object> capturedRef;
    private final Runnable runnable;
    private final boolean releaseTtlValueReferenceAfterRun;

    private TtlRunnable(Runnable runnable, boolean releaseTtlValueReferenceAfterRun) {
        // 1. 在构造时（即提交任务时）抓取快照
        this.capturedRef = new AtomicReference<Object>(TransmittableThreadLocal.Transmitter.capture());
        this.runnable = runnable;
        this.releaseTtlValueReferenceAfterRun = releaseTtlValueReferenceAfterRun;
    }

    @Override
    public void run() {
        Object captured = capturedRef.get();
        if (captured == null || releaseTtlValueReferenceAfterRun && !capturedRef.compareAndSet(captured, null)) {
            throw new IllegalStateException("TTL value reference is released after run!");
        }

        // 2. 任务执行前：恢复快照（同时备份当前线程的原值）
        Object backup = TransmittableThreadLocal.Transmitter.replay(captured);
        try {
            // 3. 执行真正的业务逻辑
            runnable.run();
        } finally {
            // 4. 任务执行后：还原当前线程的 TTL 值
            TransmittableThreadLocal.Transmitter.restore(backup);
        }
    }
}
```

执行流程图：

```
提交线程（主线程）                     线程池工作线程
─────────────────                    ─────────────────

1. TtlRunnable构造
   ↓ capture()
   快照 = {traceId: "xxx", userId: 1}
   
   ↓ 提交到线程池                      2. 线程池调度执行 run()
                                        ↓ replay(快照)
                                        将快照值设置到当前线程
                                        备份当前线程的原值
                                        
                                        3. 执行业务代码
                                        runnable.run()
                                        // 业务代码中 get() 能拿到正确的值
                                        
                                        4. finally: restore(备份)
                                        还原当前线程的 TTL 值
                                        // 避免影响下一个任务
```

### 8.5 典型应用场景

**分布式链路追踪（TraceId 传递）**

```java
// 链路追踪上下文
public class TraceContext {
    private static TransmittableThreadLocal<String> traceIdTtl = new TransmittableThreadLocal<>();
    private static TransmittableThreadLocal<String> spanIdTtl = new TransmittableThreadLocal<>();

    public static void setTraceId(String traceId) {
        traceIdTtl.set(traceId);
    }

    public static String getTraceId() {
        return traceIdTtl.get();
    }

    // ... spanId 类似
}

// 在请求入口设置 TraceId
public class TraceFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) {
        String traceId = req.getHeader("X-Trace-Id");
        TraceContext.setTraceId(traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            TraceContext.clear();
        }
    }
}

// 在线程池中的异步任务也能拿到 TraceId（通过 TTL 传递）
ExecutorService pool = TtlExecutors.getTtlExecutorService(Executors.newFixedThreadPool(4));

public void asyncProcess() {
    // 主线程设置的 traceId
    TraceContext.setTraceId("trace-001");
    
    pool.execute(() -> {
        // 线程池中的线程也能拿到 "trace-001"
        log.info("Processing with traceId={}", TraceContext.getTraceId());
    });
}
```

**日志 MDC（Mapped Diagnostic Context）**

```java
// SLF4J/Logback 的 MDC 本质上也是 ThreadLocal
// 在线程池场景下需要用 TTL 版本
public class TtlMDCAdapter implements MDCAdapter {
    private static TransmittableThreadLocal<Map<String, String>> mdcMap =
        new TransmittableThreadLocal<Map<String, String>>() {
            @Override
            protected Map<String, String> initialValue() {
                return new HashMap<>();
            }

            @Override
            protected Map<String, String> childValue(Map<String, String> parentValue) {
                return new HashMap<>(parentValue); // 返回副本
            }
        };

    @Override
    public void put(String key, String val) {
        mdcMap.get().put(key, val);
    }

    @Override
    public String get(String key) {
        return mdcMap.get().get(key);
    }

    // ... 其他方法
}
```

---

## 九、ThreadLocal 在框架中的应用

### 9.1 Spring：RequestContextHolder

Spring MVC 的 `RequestContextHolder` 让你可以在任何地方获取当前请求的 `HttpServletRequest`：

```java
// Spring 源码简化版
public abstract class RequestContextHolder {
    // 使用 ThreadLocal 存储请求属性
    private static final ThreadLocal<RequestAttributes> requestAttributesHolder =
        new NamedThreadLocal<>("Request attributes");

    public static void setRequestAttributes(RequestAttributes attributes) {
        if (attributes == null) {
            requestAttributesHolder.remove();
        } else {
            requestAttributesHolder.set(attributes);
        }
    }

    public static RequestAttributes getRequestAttributes() {
        return requestAttributesHolder.get();
    }

    public static HttpServletRequest getRequest() {
        RequestAttributes attrs = getRequestAttributes();
        return (HttpServletRequest) attrs.resolveReference(RequestAttributes.REFERENCE_REQUEST);
    }
}
```

在 `DispatcherServlet` 中，Spring 通过 `FrameworkServlet` 在请求开始时设置、请求结束时清理：

```java
// FrameworkServlet.processRequest（简化版）
protected final void processRequest(HttpServletRequest request, HttpServletResponse response) {
    RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
    try {
        // 设置当前请求到 ThreadLocal
        RequestContextHolder.setRequestAttributes(buildRequestAttributes(request, response));
        // 执行业务逻辑
        doService(request, response);
    } finally {
        // 请求结束，恢复之前的属性（清理）
        RequestContextHolder.setRequestAttributes(previousAttributes);
    }
}
```

### 9.2 Spring：TransactionSynchronizationManager

Spring 事务管理器使用 ThreadLocal 保证同一个线程中的多个数据库操作使用同一个事务连接：

```java
public abstract class TransactionSynchronizationManager {
    // 事务资源（如数据库连接）绑定到当前线程
    private static final ThreadLocal<Map<Object, Object>> resources =
        new NamedThreadLocal<>("Transactional resources");

    // 事务同步回调
    private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
        new NamedThreadLocal<>("Transaction synchronizations");

    // 当前事务名称
    private static final ThreadLocal<String> currentTransactionName =
        new NamedThreadLocal<>("Current transaction name");

    // 获取绑定到当前线程的资源（如 DataSource → Connection）
    public static Object getResource(Object key) {
        Map<Object, Object> map = resources.get();
        return map != null ? map.get(key) : null;
    }

    // 绑定资源到当前线程
    public static void bindResource(Object key, Object value) {
        Map<Object, Object> map = resources.get();
        if (map == null) {
            map = new HashMap<>();
            resources.set(map);
        }
        map.put(key, value);
    }
}
```

这样，同一个事务中的多个 DAO 操作可以通过 `getResource(dataSource)` 获取同一个 Connection，保证事务的一致性。

### 9.3 MyBatis：SqlSession 管理

MyBatis 的 `SqlSession` 不是线程安全的。在 Spring 集成环境下，MyBatis 通过 ThreadLocal 确保 SqlSession 的线程安全：

```java
// MyBatis-Spring 的 SqlSessionUtils（简化版）
public final class SqlSessionUtils {
    // 使用 ThreadLocal 持有 SqlSession
    private static final ThreadLocal<Map<SqlSessionFactory, SqlSessionHolder>> executorTypeHolder =
        new ThreadLocal<>();

    public static SqlSession getSqlSession(SqlSessionFactory sessionFactory) {
        SqlSessionHolder holder = getHolder(sessionFactory);
        if (holder != null && holder.isSynchronizedWithTransaction()) {
            return holder.getSqlSession(); // 复用当前线程的 SqlSession
        }
        // 创建新的 SqlSession 并绑定到当前线程
        SqlSession session = sessionFactory.openSession();
        registerSessionHolder(sessionFactory, session);
        return session;
    }
}
```

### 9.4 Netty：FastThreadLocal 的优化设计

Netty 实现了自己的 `FastThreadLocal`，通过**索引替代哈希**来提升性能：

```java
// Netty 的 FastThreadLocal 核心设计
public class FastThreadLocal<V> {
    // 每个 FastThreadLocal 分配一个全局唯一的索引
    private final int index;

    public FastThreadLocal() {
        // 通过 InternalThreadLocalMap 的 nextIndex 获取唯一索引
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    public V get() {
        // 直接通过索引访问数组，O(1)，无哈希计算，无冲突，无探测
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }
        return initialize(threadLocalMap);
    }

    public void set(V value) {
        // 直接通过索引设置，O(1)
        InternalThreadLocalMap.get().setIndexedVariable(index, value);
    }
}
```

对比 JDK ThreadLocal：

| 特性 | JDK ThreadLocal | Netty FastThreadLocal |
|------|----------------|----------------------|
| 定位方式 | 哈希 + 线性探测 | 直接索引 |
| 时间复杂度 | 平均 O(1)，冲突时 O(n) | 严格 O(1) |
| 哈希冲突 | 有 | 无 |
| 内存占用 | 按需分配 | 预分配数组（可能有浪费） |
| 适用场景 | 通用 | 高频访问、大量 ThreadLocal |

FastThreadLocal 的核心思想：**每个 FastThreadLocal 在创建时分配一个递增的整数索引，存储时直接通过索引访问数组**，完全避免了哈希计算和冲突探测。代价是数组需要预分配一定大小，可能有空间浪费。在 Netty 的高性能网络框架中，这种优化是有意义的。

### 9.5 Dubbo：RpcContext

Dubbo 使用 ThreadLocal 存储每次 RPC 调用的上下文信息：

```java
// Dubbo 的 RpcContext（简化版）
public class RpcContext {
    // 每次 RPC 调用的上下文通过 ThreadLocal 隔离
    private static final ThreadLocal<RpcContext> LOCAL = new ThreadLocal<RpcContext>() {
        @Override
        protected RpcContext initialValue() {
            return new RpcContext();
        }
    };

    // 请求参数
    private Map<String, String> attachments;
    // 调用方地址
    private String remoteAddress;
    // 方法名
    private String methodName;

    public static RpcContext getContext() {
        return LOCAL.get();
    }

    public static void removeContext() {
        LOCAL.remove(); // Dubbo 在 Filter 中调用，确保清理
    }
}
```

在 Dubbo Filter 链中，每个 RPC 请求开始时设置 RpcContext，请求结束后清理，确保线程池中复用的线程不会受到上一个请求的上下文污染。

---

## 十、常见面试问题总结

### Q1：ThreadLocal 的实现原理？

**回答要点：**

ThreadLocal 并不是把数据存在自身对象里，而是每个 Thread 对象持有一个 `ThreadLocalMap` 字段（`threadLocals`）。ThreadLocalMap 是 ThreadLocal 的静态内部类，底层是一个 Entry 数组，使用**开放地址法（线性探测）**处理哈希冲突。

当调用 `threadLocal.set(value)` 时：
1. 获取当前线程的 ThreadLocalMap
2. 以 ThreadLocal 对象本身作为 key（弱引用），value 为实际值
3. 通过 `threadLocalHashCode & (len-1)` 计算槽位，线性探测处理冲突

当调用 `threadLocal.get()` 时：
1. 获取当前线程的 ThreadLocalMap
2. 以 ThreadLocal 对象为 key 查找 Entry
3. 如果直接命中则返回，否则线性探测查找

每个线程有自己的 Map，所以线程之间天然隔离，不需要加锁。

---

### Q2：ThreadLocal 为什么会内存泄漏？怎么解决？

**回答要点：**

泄漏的根因是 **Entry 的 key 是弱引用，value 是强引用**。当 ThreadLocal 外部引用被置为 null 后：
1. GC 回收了 ThreadLocal 对象（因为 key 是弱引用），Entry 的 key 变为 null
2. 但 value 仍被 Entry 强引用，Entry 被 ThreadLocalMap 强引用，ThreadLocalMap 被 Thread 强引用
3. 如果线程是线程池中的核心线程（不会被销毁），这条引用链一直存在，value 永远无法被回收

ThreadLocalMap 有被动清理机制（get/set/remove 时会顺带清理过期 Entry），但不是实时的，也不是全量的。

**解决方案**：使用完 ThreadLocal 后，在 `finally` 块中调用 `remove()`，彻底清除 Entry 和 value。

---

### Q3：ThreadLocal 的 key 为什么用弱引用？

**回答要点：**

如果 key 是强引用，当外部将 ThreadLocal 变量置为 null 后，ThreadLocalMap 的 Entry 仍然强引用 ThreadLocal 对象，导致 ThreadLocal 对象本身也无法被回收。这样不仅 value 泄漏，连 key 也泄漏了，泄漏更严重。

用弱引用后，ThreadLocal 对象可以被 GC 回收，Entry 的 key 变为 null，成为"过期 Entry"。ThreadLocalMap 的被动清理机制可以识别并清理这些过期 Entry（至少 key 被回收了，为清理创造了条件）。

但弱引用只是"兜底"，不能替代 `remove()`。真正的解决方案仍然是手动 remove。

---

### Q4：InheritableThreadLocal 和 TransmittableThreadLocal 的区别？

**回答要点：**

**InheritableThreadLocal**：
- 解决父子线程之间的值传递问题
- 在 `new Thread()` 时，Thread 的 `init()` 方法会复制父线程的 `inheritableThreadLocals`
- **局限性**：只在创建线程时传递一次。线程池场景下线程是复用的，不会重新创建线程，所以值无法更新传递

**TransmittableThreadLocal（TTL）**：
- 阿里巴巴开源，解决线程池场景下的值传递问题
- 核心原理：在任务**提交时** capture 快照，在任务**执行前** replay 恢复，任务**执行后** restore 还原
- 通过 `TtlRunnable` 装饰器或 `TtlExecutors` 代理线程池实现
- 每次提交任务都会捕获最新的 TTL 值，保证线程池场景下值能正确传递

| 特性 | InheritableThreadLocal | TransmittableThreadLocal |
|------|----------------------|------------------------|
| 传递时机 | new Thread() 时 | 任务提交+执行时 |
| 线程池支持 | 不支持 | 支持 |
| 实现方式 | Thread.init() 复制 | capture-replay-restore |
| 值是否最新 | 只复制一次 | 每次提交都捕获最新值 |

---

### Q5：ThreadLocal 和 synchronized 的区别？

**回答要点：**

两者都能解决多线程并发问题，但思路完全不同：

| 维度 | synchronized | ThreadLocal |
|------|-------------|-------------|
| 核心思想 | 多个线程共享一个变量，加锁控制访问 | 每个线程各有一份副本，互不影响 |
| 设计哲学 | 时间换空间（一份共享数据，排队访问） | 空间换时间（多份数据，并行访问） |
| 适用场景 | 多线程需要操作同一份数据，保证一致性 | 每个线程需要独立的变量副本 |
| 性能 | 有锁竞争开销（上下文切换、线程阻塞） | 无锁，但有内存开销（每个线程一份副本） |
| 线程安全 | 通过互斥保证 | 通过隔离保证 |
| 典型场景 | 计数器、共享状态修改 | 数据库连接、格式化器、用户上下文 |

选择建议：
- 如果多个线程需要操作**同一份数据**并保证最终一致性 → 用 `synchronized`
- 如果每个线程需要**独立的副本**，不需要共享 → 用 `ThreadLocal`
- 不要用 ThreadLocal 来解决同步问题，也不要用 synchronized 来做线程隔离

---

### Q6：ThreadLocalMap 为什么用线性探测法而不是链地址法？

**回答要点：**

1. **ThreadLocal 数量通常不多**：一个线程通常只有几个到十几个 ThreadLocal 变量，冲突率低，线性探测足够高效
2. **没有链表节点的内存开销**：链地址法需要为每个 Entry 创建链表节点（额外的对象头和指针），而开放地址法直接存在数组中，内存更紧凑
3. **缓存友好**：开放地址法的数据在连续的数组中，CPU 缓存命中率高
4. **黄金分割数哈希**：`0x61c88647` 的散列效果极好，冲突率很低，线性探测的效率接近 O(1)

但线性探测也有缺点：删除元素需要特殊处理（不能直接置 null，需要 rehash 后续元素），所以在 HashMap 等通用 Map 中不适合，但在 ThreadLocalMap 这种小规模、低冲突的场景下是合理的选择。

---

### Q7：ThreadLocal 的 hash 冲突怎么处理？

**回答要点：**

ThreadLocalMap 使用**开放地址法中的线性探测法**处理冲突：

1. 计算初始槽位 `i = threadLocalHashCode & (len-1)`
2. 如果槽位 `i` 已被占用且 key 不同，探测下一个位置 `i+1`（到达数组末尾回绕到 0）
3. 重复直到找到空槽位或 key 匹配的 Entry

与 HashMap 的链地址法（拉链法）不同，ThreadLocalMap 不使用链表，冲突时直接在数组中向后寻找空位。由于 ThreadLocal 数量通常很少，加上黄金分割数的均匀散列，冲突率很低，线性探测的效率很高。

---

## 附录：ThreadLocal 核心知识点速查表

```
┌────────────────────────────────────────────────────────────────┐
│                    ThreadLocal 核心架构                          │
├────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Thread                                                          │
│  ├── threadLocals (ThreadLocalMap) ← 存储普通 ThreadLocal         │
│  └── inheritableThreadLocals (ThreadLocalMap) ← 存储可继承的       │
│                                                                  │
│  ThreadLocalMap                                                  │
│  ├── Entry[] table  ← 底层数组                                    │
│  ├── Entry extends WeakReference<ThreadLocal<?>>                 │
│  │   ├── key (弱引用) → ThreadLocal 对象                          │
│  │   └── value (强引用) → 实际值                                  │
│  ├── INITIAL_CAPACITY = 16                                       │
│  ├── threshold = len * 2/3 (负载因子 2/3)                         │
│  └── HASH_INCREMENT = 0x61c88647 (黄金分割数)                     │
│                                                                  │
│  冲突处理：线性探测法（开放地址法）                                  │
│  清理机制：expungeStaleEntry + cleanSomeSlots                     │
│  内存泄漏：key弱引用(可回收) + value强引用(需手动remove)            │
│                                                                  │
├────────────────────────────────────────────────────────────────┤
│                    传递机制对比                                    │
├────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ThreadLocal          → 线程隔离，不传递                           │
│  InheritableThreadLocal → 父→子传递(new Thread时)                 │
│  TransmittableThreadLocal → 线程池场景传递(capture-replay-restore) │
│                                                                  │
└────────────────────────────────────────────────────────────────┘
```

---

> **本文总结**：ThreadLocal 的核心设计是"每个线程拥有自己的变量副本"，通过 Thread 中的 ThreadLocalMap 实现。ThreadLocalMap 使用弱引用 key + 强引用 value 的 Entry 设计，配合线性探测法和被动清理机制来管理内存。使用时务必在 finally 中调用 remove() 防止内存泄漏。在线程池场景下，使用 TransmittableThreadLocal 来正确传递上下文。
