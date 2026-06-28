package com.zhh.handsome.线程安全集合类;

public class Demo1 {
    //CoccurrentHashMap
    // 2. JDK 1.8 实现：CAS + synchronized + 数组 + 链表 / 红黑树
    // JDK 1.8 彻底抛弃了 Segment 分段锁，改用「Node 数组 + 链表 / 红黑树」的结构，通过CAS 无锁操作 + 局部 synchronized 锁实现线程安全，锁粒度更细（单个链表 / 红黑树的头节点）。
    // 核心结构：
    // 底层是 volatile Node[] table 数组（存储键值对节点）。
    // 每个 Node 是基本存储单元，value 和 next 用 volatile 修饰（保证可见性）。
    // 当链表长度 > 8 且数组长度 > 64 时，链表转为红黑树（提升查询效率，从 O (n) 到 O (log n)）。
    // 锁策略：
    // 无锁操作：通过 CAS 操作（compareAndSwap）处理空节点的插入（避免加锁）。
    // 局部加锁：当节点非空时，对链表头节点或红黑树首节点加 synchronized 锁（只锁当前冲突的链表 / 树，不影响其他节点）。
    // 扩容机制：支持多线程协助扩容（Transfer 机制），通过 sizeCtl 变量控制扩容状态：
    // sizeCtl = 0：默认值，未初始化。
    // sizeCtl = -1：正在初始化。
    // sizeCtl < 0：正在扩容（实际值为 -(1 + 参与扩容的线程数)）。
    // sizeCtl > 0：下次扩容的阈值（容量 * 负载因子，默认负载因子 0.75）。
    // size 计算：通过 baseCount（基础计数）+ counterCells 数组（分散计数，减少竞争）实现，最终 size 是两者的累加和（可能非精确值，因为并发更新有延迟）。
    // 二、核心方法解析
    // 以 JDK 1.8 为例，解析常用方法的实现逻辑。
    // 1. put (K key, V value)：插入键值对
    // 流程：
    // 校验参数：key 或 value 为 null 时抛 NullPointerException（与 HashMap 不同，HashMap 允许 null）。
    // 计算哈希：通过二次哈希（spread(key.hashCode())）减少哈希冲突。
    // 初始化数组：若 table 未初始化，用 CAS 初始化（initTable()）。
    // 定位节点并插入：
    // 若目标索引位置为空，直接用 CAS 插入新 Node（无锁）。
    // 若正在扩容（table[i] 是 ForwardingNode），协助扩容（helpTransfer()）。
    // 若节点非空，对该节点加 synchronized 锁，按链表 / 红黑树结构插入：
    // 链表：遍历链表，若 key 已存在则更新 value；否则插入尾部，插入后检查是否需要转红黑树。
    // 红黑树：按红黑树规则插入或更新。
    // 更新计数：通过 CAS 更新 baseCount，若失败则使用 counterCells 分散计数。
    // 2. get (Object key)：查询值
    // 流程：
    // 计算哈希值，定位到 table 索引。
    // 若索引位置为空，返回 null。
    // 若头节点的 key 匹配，返回其 value。
    // 否则，若头节点是红黑树节点，按红黑树查询；否则遍历链表查询。
    // 特点：get 操作全程无锁！因为 Node 的 value 是 volatile 修饰的，保证了读取的可见性，效率极高。
    // 3. remove (Object key)：删除键值对
    // 流程：
    // 计算哈希，定位索引。
    // 若数组未初始化或索引位置为空，返回 null。
    // 若正在扩容，先协助扩容。
    // 对目标节点加 synchronized 锁，遍历链表 / 红黑树找到 key 对应的节点，删除并调整结构（链表断链 / 红黑树平衡）。
    // 更新计数（同 put 逻辑）。
    // 4. 原子性方法（并发场景核心）
    // ConcurrentHashMap 提供了多个原子性方法，避免「检查 - 修改」复合操作的线程安全问题：
    // putIfAbsent(K key, V value)：若 key 不存在则插入，返回 null；若存在则返回旧值（原子操作，替代 if (!containsKey(key)) put(...)）。
    // replace(K key, V oldVal, V newVal)：仅当 key 对应的 value 为 oldVal 时才替换，返回是否成功。
    // computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction)：若 key 不存在，通过函数计算 value 并插入（原子操作，适合初始化场景）。
    // 三、基本用法
    // ConcurrentHashMap 的 API 与 HashMap 类似，但线程安全，适合多线程读写场景。
    // java
    // 运行
    // import java.util.concurrent.ConcurrentHashMap;
    
    // public class ConcurrentHashMapDemo {
    //     public static void main(String[] args) {
    //         // 创建实例（默认初始容量 16，负载因子 0.75）
    //         ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
    
    //         // 1. 插入键值对
    //         map.put("a", 1);
    //         map.put("b", 2);
    
    //         // 2. 原子插入（避免重复插入）
    //         map.putIfAbsent("a", 100); // 已存在，返回 1，不会覆盖
    //         map.putIfAbsent("c", 3);   // 不存在，插入，返回 null
    
    //         // 3. 查询
    //         System.out.println(map.get("a")); // 1
    
    //         // 4. 原子替换
    //         boolean replaced = map.replace("b", 2, 200); // 成功，返回 true
    //         System.out.println(map.get("b")); // 200
    
    //         // 5. 计算并插入（原子操作）
    //         map.computeIfAbsent("d", k -> k.length()); // 插入 "d" -> 1（"d"长度为1）
    
    //         // 6. 遍历（弱一致性迭代器，不会抛 ConcurrentModificationException）
    //         map.forEach((k, v) -> System.out.println(k + ":" + v));
    //     }
    // }
    // 四、踩坑点总结
    // 1. 复合操作非原子性
    // ConcurrentHashMap 仅保证单个方法的线程安全，但多个方法的复合操作（如先查后改）不原子。
    // java
    // 运行
    // // 错误示例：可能被其他线程修改，导致覆盖
    // if (map.containsKey("key")) {
    //     map.put("key", newValue); // 此时"key"可能已被删除或修改
    // }
    
    // // 正确做法：用原子方法
    // map.putIfAbsent("key", newValue); // 若存在则不操作
    // // 或
    // map.replace("key", oldValue, newValue); // 仅当值为oldValue时替换
    // 2. size () 返回非精确值
    // JDK 1.8 中 size () 是 baseCount + counterCells 之和，由于并发更新时计数可能延迟同步，返回值可能不是实时精确的。不建议依赖 size () 做业务判断（如判断是否为空应用 isEmpty()）。
    // 3. 不允许 key/value 为 null
    // 与 HashMap 不同，ConcurrentHashMap 的 key 和 value 均不能为 null，否则抛 NullPointerException。原因：并发场景下，get(key) == null 无法区分「key 不存在」还是「value 为 null」，会导致歧义。
    // 4. 弱一致性迭代器
    // ConcurrentHashMap 的迭代器（如 keySet().iterator()）是弱一致性的：
    // 迭代过程中，其他线程对 map 的修改可能不被迭代器感知（不会抛出 ConcurrentModificationException）。
    // 例如：迭代时，其他线程插入的新元素可能不在迭代结果中；已删除的元素可能仍被迭代到。
    // 5. 红黑树转换的隐藏条件
    // 当链表长度 > 8 时，并非立即转红黑树，而是先检查数组长度：
    // 若数组长度 < 64：先扩容数组（而非转树）。
    // 若数组长度 ≥ 64：才转为红黑树。
    // 若业务中存在大量哈希冲突，需注意数组扩容可能先于树化，避免性能预期偏差。
    // 6. 扩容时的协助机制
    // 多线程扩容时，当前线程可能会协助其他线程完成扩容（helpTransfer()），可能导致当前操作耗时略增加，但总体提升扩容效率，属于正常现象。
    // 总结
    // ConcurrentHashMap 是多线程场景下哈希表的首选，JDK 1.8 通过 CAS + 局部锁实现了高效并发。使用时需注意：
    // 优先使用原子方法（如 putIfAbsent）避免复合操作风险。
    // 不依赖 size() 的精确值，不传入 null 作为 key/value。
    // 理解弱一致性迭代器的特性，避免迭代时的逻辑错误。
}
