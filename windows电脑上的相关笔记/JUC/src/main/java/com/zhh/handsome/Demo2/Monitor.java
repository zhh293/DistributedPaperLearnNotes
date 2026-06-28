package com.zhh.handsome.Demo2;

public class Monitor {




    /*第一步：先搞懂 Java 对象在内存里长啥样？
    Java 里 new 出来的对象，在 JVM（比如 HotSpot）内存中会分成 3 块，就像一栋房子的 “门牌 + 客厅 + 凑数空间”：
    部分	作用（通俗比喻）
    对象头（Header）	门牌：记录对象的 “身份 + 状态”，是核心
    实例数据（Body）	客厅：存对象的成员变量（比如 User 类的 name、age）
    对齐填充（Padding）	凑数的：JVM 要求对象大小是 8 字节的整数倍，不够就补空
    我们要聊的 “Monitor 相关逻辑”，全在 对象头 里 —— 尤其是对象头中的「Mark Word」。
    第二步：对象头到底存了啥？
    对象头又分两部分（数组对象会多一个 “数组长度” 字段），重点看第一部分：
            1. Mark Word（标记字段）：对象的 “动态状态牌”
    这是对象头的核心，相当于门牌上的 “动态备注”—— 会根据对象的锁状态变化，存不同的信息。可以理解成：对象的 “锁状态” 变了，Mark Word 里记的东西也会变。
    以 32 位 JVM 为例（64 位逻辑类似，只是多了些预留位），不同锁状态下 Mark Word 的结构如下：
    锁状态	Mark Word（32 位）存储内容	锁标志位
    无锁	哈希码（HashCode） + 分代年龄 + 无锁标志	01
    偏向锁	偏向线程 ID + Epoch（版本） + 分代年龄 + 偏向标志	01（偏向模式）
    轻量级锁	指向线程栈中 “锁记录” 的指针 + 轻量级锁标志	00
    重量级锁	指向 Monitor 对象的指针 + 重量级锁标志	10
    划重点：只有当锁升级到 重量级锁 时，Mark Word 才会存一个「指向 Monitor 对象的指针」—— 这就是 “Monitor 和对象头关联” 的核心！
            2. Klass Pointer（类型指针）：对象的 “身份 ID”
    相当于门牌上的 “房子所属小区”—— 存的是指向该对象所属类的元数据（比如 User 对象的 Klass Pointer 指向 User.class 的字节码信息）。JVM 通过它知道：“哦，这个对象是 User 类的实例，能调用 User 的方法”。
    这部分和 Monitor 直接关联不大，但要知道：对象头是 “Mark Word+Klass Pointer” 的组合，Monitor 只和 Mark Word 互动。
    第三步：Monitor 到底是啥？底层咋工作的？
    Monitor 可以理解成 “锁的底层管理工具”，也叫 “管程”—— 你可以把它想象成一个 “带锁的会议室”：
    会议室里只能有一个人（线程）在干活（执行同步代码）；
    想进会议室，得先抢 “钥匙”（锁）；
    没抢到钥匙的人，只能在门口排队；
    里面的人如果暂时不想干活（调用 wait ()），会把钥匙还回去，自己去旁边的休息室等通知。


    Monitor 的底层结构（3 个核心部分）
    每个 Monitor 对象内部都有 3 个关键 “区域”，对应上面的比喻：
    区域	作用（对应会议室比喻）	线程状态
    Owner（拥有者）	持有 “钥匙” 的线程（正在会议室干活的人）	Running（运行）
    EntryList（入口队列）	没抢到钥匙，在门口排队的线程	Blocked（阻塞）
    WaitSet（等待队列）	抢到过钥匙，但暂时退出（调用 wait ()），在休息室等通知的线程	Waiting（等待）
    Monitor 的工作流程（结合 synchronized）
    我们用synchronized (obj) { ... }这段代码，看 Monitor 是怎么和对象头配合工作的：
    线程 1 想进同步块：先看obj的对象头→Mark Word：
    如果是 “无锁状态”（标志位 01），先尝试升级为轻量级锁（用 CAS 改 Mark Word）；
    如果轻量级锁抢不到（有线程 2 也来抢），再升级为重量级锁 —— 此时，Mark Word 会改成「指向 Monitor 对象的指针」（标志位 10）。
    线程 1 抢占 Monitor 的 Owner：成功抢到后，Monitor 的 Owner 就变成线程 1，线程 1 进入同步块执行代码。
    线程 2 也想进同步块：同样看obj的 Mark Word→发现指向 Monitor，就去抢 Monitor 的 Owner—— 没抢到，就进入 Monitor 的 EntryList 排队，线程 2 变成 Blocked 状态。
    线程 1 调用 obj.wait ()：线程 1 会释放 Monitor 的 Owner 身份，自己移到 Monitor 的 WaitSet 里（变成 Waiting 状态），此时 EntryList 里的线程 2 可以抢 Owner 了。
    其他线程调用 obj.notify ()：Monitor 会从 WaitSet 里挑一个线程（比如线程 1），移回 EntryList 排队，等下次抢 Owner。
    线程 1 执行完同步块：释放 Monitor 的 Owner 身份，Mark Word 恢复为无锁状态（或偏向锁状态），EntryList 里的线程继续竞争。


    第四步：关键底层细节（为什么这么设计？）
            1. 为什么要升级到重量级锁才用 Monitor？
    因为 Monitor 的 “线程排队 / 阻塞” 依赖 操作系统的内核态操作（线程从用户态切换到内核态）—— 这个切换开销很大！所以 JVM 搞了 “锁升级” 的优化：
    无锁→偏向锁（只有一个线程抢，直接 “贴标签”，不用 Monitor）；
    偏向锁→轻量级锁（少量竞争，用 CAS 自旋，不用 Monitor）；
    轻量级锁→重量级锁（竞争激烈，自旋没用了，才用 Monitor 阻塞线程）。
    Monitor 是 “最后手段”，用来解决激烈竞争的问题。
            2. 每个对象都有对应的 Monitor 吗？
    不是！只有当对象被用作「synchronized 的锁对象」，且锁升级到重量级锁时，JVM 才会为这个对象分配一个 Monitor（或从 “Monitor 池” 里拿一个复用）。平时没被用作锁的对象，它的 Mark Word 里根本没有 Monitor 的指针 —— 就像没被用的会议室，不需要守门人。
            3. 静态 synchronized 的锁对象是谁？
    静态方法的synchronized，锁对象是「类对象」（比如 User.class）—— 类对象也是 Java 对象，也有对象头。所以逻辑和普通对象一样：类对象的 Mark Word 升级到重量级锁时，会指向一个 Monitor，线程竞争这个 Monitor 的 Owner。



    总结：一句话讲清 Monitor 和对象头的关系
    对象头（尤其是 Mark Word）是 “锁状态的记录者”，Monitor 是 “重量级锁的执行者”—— 当锁升级到重量级时，Mark Word 会记录指向 Monitor 的指针，让线程通过 Monitor 来抢锁、排队、等待，最终实现 synchronized 的同步效果。
    就像：门牌（对象头）记着 “这个门的钥匙在哪个守门人（Monitor）手里”，线程要进门，先看门牌找守门人，再跟守门人抢钥匙，没抢到就排队。
*/











}
