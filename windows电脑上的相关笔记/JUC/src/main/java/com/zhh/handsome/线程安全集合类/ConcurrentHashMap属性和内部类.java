package com.zhh.handsome.线程安全集合类;

import java.util.concurrent.ConcurrentHashMap;

public class ConcurrentHashMap属性和内部类 {
    public static void main(String[] args) {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();



        /*ConcurrentHashMap 是 Java 并发包（java.util.concurrent）提供的线程安全、高效的哈希表实现，核心定位是替代线程安全但低效的 Hashtable 和 Collections.synchronizedMap()。其设计目标是在保证并发安全的前提下，最大化查询和修改效率 ——Java 8+ 版本通过「数组 + 链表 / 红黑树」数据结构，结合 synchronized 细粒度锁 + CAS 无锁操作，彻底抛弃了 Java 7 的分段锁（Segment）机制，性能大幅提升。
        本文将从「核心属性」和「关键内部类」两部分，结合并发场景的设计思想，进行清晰易懂的拆解。

        一、核心重要属性（Java 8+）
        ConcurrentHashMap 的属性围绕「并发安全控制、数据存储、扩容机制、高效统计」四大核心目标设计，每个属性都有明确的并发语义：
        属性名	       数据类型	                       核心作用
        table	transient volatile Node<K,V>[]	核心存储数组（哈希桶），存储 Node 节点（链表 / 红黑树的根节点），volatile 保证数组可见性
        nextTable	private transient volatile Node<K,V>[]	扩容时的临时数组，扩容完成后替换 table
        sizeCtl	private transient volatile int	初始化 / 扩容的「状态控制器」，不同数值代表不同状态（核心中的核心）
        loadFactor	private final float	负载因子（默认 0.75），控制数组扩容时机
        threshold	private transient int	扩容阈值（table.length * loadFactor），达到该值触发扩容
        modCount	private transient volatile int	修改次数计数器（弱一致性迭代器的基础），避免快速失败（fail-fast）
        counterCells	private transient volatile CounterCell[]	原子性统计 size 的「单元格数组」，减少 CAS 竞争（替代单一原子变量）


        重点属性深度解析
        1. table：核心存储数组
        本质：Node<K,V>[] 数组，每个元素是一个「链表头节点」或「红黑树的 TreeBin 包装节点」。
        volatile 修饰：保证数组引用的可见性 —— 当数组扩容替换（table = nextTable）或某个位置节点被修改（如链表新增节点）时，其他线程能立即看到最新状态，避免并发下的脏读。
        初始化时机：延迟初始化（第一次 put 时），由 sizeCtl 控制，避免初始化浪费。




        2. sizeCtl：并发控制的「核心开关」
        sizeCtl 是 ConcurrentHashMap 最关键的属性，通过不同整数值表达「当前状态」，控制初始化、扩容、正常读写的并发逻辑：
        sizeCtl 数值范围	状态含义
        sizeCtl < 0	特殊状态：
        - -1：正在初始化（单线程初始化，其他线程阻塞等待）
        - -N（N≥2）：正在扩容，有 N-1 个线程参与扩容
                sizeCtl = 0	默认初始值（未初始化），第一次 put 时会触发初始化，将 sizeCtl 设为 -1 锁定
        sizeCtl > 0	正常状态：
        - 未初始化时：表示「初始容量」（如构造函数传入 16，则 sizeCtl=16）
        - 已初始化时：表示「下次扩容的阈值」（table.length * loadFactor）
        举个例子：
        初始化时：线程 A 发现 sizeCtl=0，通过 CAS 将 sizeCtl 设为 -1，开始初始化 table；其他线程发现 sizeCtl=-1，会自旋等待初始化完成。
        扩容时：当 size 达到 threshold，线程会通过 CAS 将 sizeCtl 设为 -2（表示 1 个线程扩容），后续线程发现 sizeCtl=-2，可通过 CAS 把 sizeCtl 减 1（变为 -3），加入扩容队列，实现多线程协同扩容。
        3. counterCells：高效统计 size 的秘密
        ConcurrentHashMap 要统计「当前元素个数」（size() 方法），但直接用一个原子变量（如 AtomicInteger）会导致高并发下 CAS 竞争激烈（所有线程都抢着更新同一个变量）。
        counterCells 是「分段统计」的解决方案：
        本质：CounterCell[] 数组，每个 CounterCell 存储一个分段的元素个数（volatile long value）。
        原理：新增元素时，线程随机选择一个 CounterCell，通过 CAS 更新其 value；统计时，遍历所有 CounterCell 求和，再加上当前正在修改的临时值（baseCount）。
        优势：将 CAS 竞争分散到多个单元格，大幅提升高并发下的 size 统计效率。
        二、关键内部类（Java 8+）
        ConcurrentHashMap 的内部类是其「数据存储、并发控制、结构优化」的核心载体，每个类都有明确的职责分工，且紧密配合属性实现线程安全。
        1. Node<K,V>：基础数据节点（链表节点）
        Node 是 ConcurrentHashMap 存储键值对的「最小单位」，本质是链表节点，设计上重点保证并发可见性：
        java
                运行
        static class Node<K,V> implements Map.Entry<K,V> {
            final int hash;       // key 的哈希值（不可变，避免哈希冲突变化）
            final K key;          // key 不可变（final），保证线程安全
            volatile V val;       // value 用 volatile 修饰，保证修改可见性
            volatile Node<K,V> next; // 下一个节点，volatile 修饰，保证链表结构变化可见

            // 构造器、getter、setValue 等方法（setValue 用 CAS 实现原子更新）
        }
        核心设计：
        key 和 hash 设为 final：避免 key 被修改导致哈希冲突，保证节点在哈希表中的位置稳定。
        val 和 next 用 volatile：当线程修改节点值（setValue）或链表新增节点（next 指向新节点）时，其他线程能立即看到，避免并发下的脏读。
        局限性：链表长度超过 8 时，查询效率从 O (n) 下降到 O (log n)，因此需要转为红黑树。
        2. TreeNode<K,V>：红黑树节点
        TreeNode 继承自 Node，是红黑树的节点类型，用于优化长链表的查询效率：
        java
                运行
        static final class TreeNode<K,V> extends Node<K,V> {
            TreeNode<K,V> left;   // 左子节点
            TreeNode<K,V> right;  // 右子节点
            TreeNode<K,V> prev;   // 前驱节点（用于链表转树/树转链表的过渡）
            boolean red;          // 红黑树节点颜色（红/黑，保证树平衡）

            TreeNode(int hash, K key, V val, Node<K,V> next) {
                super(hash, key, val, next);
            }
        }
        与 HashMap 的 TreeNode 区别：
        ConcurrentHashMap 的 TreeNode 没有 parent 指针（HashMap 有），因为红黑树的旋转操作需要线程安全，简化结构能减少并发冲突。
        不直接作为 table 的元素，而是被 TreeBin 包装后存储（HashMap 的 TreeNode 可直接作为元素）。
        3. TreeBin<K,V>：红黑树的「管理器」
        TreeBin 是红黑树的「包装类」，不存储数据，仅负责管理 TreeNode 组成的红黑树，同时作为「锁对象」保证红黑树操作的线程安全：
        java
                运行
        static final class TreeBin<K,V> extends Node<K,V> {
            TreeNode<K,V> root;    // 红黑树根节点
            volatile TreeNode<K,V> first; // 链表形式的头节点（用于树转链表）
            volatile Thread waiter; // 等待锁的线程（优化锁竞争）
            volatile int lockState; // 锁状态：0=无锁，1=写锁，2=读锁

            // 构造器：接收链表头节点，将链表转为红黑树
            TreeBin(TreeNode<K,V> b) {
                super(TREEBIN, null, null, null); // hash 固定为 TREEBIN（-2）
                this.root = balanceInsertion(root, b); // 红黑树平衡插入
                this.first = b;
            }

            // 红黑树的查找、插入、删除方法（均加锁保证线程安全）
        }
        核心职责：
        包装红黑树：TreeBin 的 hash 固定为 TREEBIN（-2），当 table 中某个位置的元素是 TreeBin 时，表示该位置是红黑树结构。
        细粒度锁：红黑树的所有操作（查找、插入、删除）都需要获取 TreeBin 的锁（通过 lockState 控制），锁的粒度是「红黑树」而非整个数组，大幅减少锁竞争。
        红黑树平衡：内部维护红黑树的平衡逻辑（旋转、变色），保证查询效率为 O (log n)。
        4. ForwardingNode<K,V>：扩容时的「引路节点」
        ForwardingNode 是扩容过程中的「临时节点」，用于引导线程查询 / 修改到新数组，实现「扩容和读写并发进行」：
        java
                运行
        static final class ForwardingNode<K,V> extends Node<K,V> {
            final Node<K,V>[] nextTable; // 指向扩容后的新数组（nextTable）

            ForwardingNode(Node<K,V>[] tab) {
                super(MOVED, null, null, null); // hash 固定为 MOVED（-1）
                this.nextTable = tab;
            }

            // 查找/修改时，会转发到 nextTable 对应的位置
            public V get(Object key) { ... }
            public V put(K key, V value) { ... }
        }
        扩容核心逻辑：
        扩容线程创建 nextTable（新数组，长度是原数组的 2 倍）。
        扩容线程逐步将原数组 table 中的节点迁移到 nextTable，迁移完成后，在原数组的该位置放置 ForwardingNode（hash = MOVED）。
        其他线程读写时，若发现 table 中某个位置是 ForwardingNode，会自动转向 nextTable 操作，避免扩容时阻塞读写。
        5. ReservationNode<K,V>：临时占位节点
        ReservationNode 是「无数据的占位节点」，用于 computeIfAbsent()、merge() 等方法的并发控制，避免重复计算：
        java
                运行
        static final class ReservationNode<K,V> extends Node<K,V> {
            ReservationNode() {
                super(RESERVED, null, null, null); // hash 固定为 RESERVED（-3）
            }
        }
        使用场景：当线程执行 computeIfAbsent(key, func) 时，若 key 不存在，会先在 table 中该位置插入 ReservationNode，表示「正在计算 value」，其他线程发现该节点后，会等待计算完成，避免多个线程重复执行 func（可能耗时或有副作用）。
        6. CounterCell：size 统计的原子单元格
        CounterCell 是 counterCells 数组的元素，用于分段统计元素个数，减少 CAS 竞争：
        java
                运行
        @jdk.internal.vm.annotation.Contended // 避免伪共享（False Sharing）
        static final class CounterCell {
            volatile long value; // 存储分段的元素个数，volatile 保证可见性
            CounterCell(long x) { value = x; }
        }
        @Contended 注解：避免 CPU 缓存行伪共享 —— 多个 CounterCell 若在同一个缓存行，修改一个会导致整个缓存行失效，影响性能。该注解会让每个 CounterCell 独占一个缓存行，提升并发效率。
        7. Segment<K,V>：Java 7 遗留的分段锁（@Deprecated）
        Segment 是 Java 7 中 ConcurrentHashMap 的核心内部类，本质是「分段锁」—— 将 table 分为多个 Segment，每个 Segment 是一个独立的哈希表，锁的粒度是 Segment，避免全表锁。
        但 Java 8 后被弃用（@Deprecated），原因是：
        分段锁的粒度仍不够细（一个 Segment 包含多个哈希桶），高并发下仍有锁竞争。
        扩容时需要锁定整个 Segment，效率低于 Java 8 的多线程协同扩容。
        Java 8 保留 Segment 仅为兼容旧代码，实际不再使用。
        三、核心设计亮点总结
        ConcurrentHashMap 的属性和内部类紧密配合，实现了「线程安全 + 高效并发」的核心目标，关键设计亮点：
        细粒度锁：用 synchronized 锁定单个 Node 或 TreeBin，而非全表锁，锁竞争极小。
        无锁化操作：初始化、扩容、size 统计等场景用 CAS 操作，避免锁开销。
        扩容并发：ForwardingNode 引导读写线程访问新数组，实现扩容和读写并行。
        结构优化：链表长度超过 8 转红黑树，查询效率从 O (n) 提升到 O (log n)。
        高效统计：CounterCell 分段统计 size，避免单一原子变量的 CAS 竞争。
        可见性保证：table、Node 的 val 和 next 等关键变量用 volatile 修饰，配合 happens-before 规则，保证并发可见性。*/












    }
}
