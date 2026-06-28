package com.zhh.handsome.线程安全集合类;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class computeIfAbsent {
    public static void main(String[] args) {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        map.computeIfAbsent("key", k -> "value");
    }
//     computeIfAbsent 是 ConcurrentHashMap 提供的核心原子性方法之一，专门用于「当键不存在时，通过函数计算值并插入；若键已存在，则直接返回现有值」的场景。它的核心价值是将「检查 - 计算 - 插入」三个步骤合并为一个原子操作，完美解决多线程下复合操作的线程安全问题。
// 一、computeIfAbsent 方法详解
// 1. 方法签名
// java
// 运行
// public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction)
// 参数：
// key：要查询的键。
// mappingFunction：函数式接口，接收键作为参数，返回一个值（用于当键不存在时计算要插入的值）。若函数返回 null，则不会插入任何值（相当于不操作）。
// 返回值：
// 若键已存在：返回该键对应的现有值。
// 若键不存在：返回 mappingFunction 计算出的值（并将该键值对插入 map）；若函数返回 null，则返回 null（且不插入）。
// 核心特性：原子性。整个「检查键是否存在→计算值→插入键值对」的过程是线程安全的，不会被其他线程打断，避免了并发场景下的重复计算或数据不一致。
// 2. 执行逻辑（JDK 1.8+）
// 计算 key 的哈希值，定位到 table 数组的索引位置。
// 检查该位置是否存在对应 key 的节点：
// 若存在：直接返回该节点的 value。
// 若不存在：进入原子操作流程。
// 对目标位置的节点加锁（synchronized），再次检查键是否存在（避免加锁前其他线程已插入）。
// 若仍不存在，执行 mappingFunction.apply(key) 计算值：
// 若计算结果为 null：不插入，返回 null。
// 若计算结果非 null：插入键值对，返回计算值。
// 释放锁，保证其他线程能看到最新插入的值（得益于 volatile 修饰的 Node 节点）。
// 3. 与普通方法的对比
// 普通情况下，我们可能用「先查后插」的复合操作实现类似逻辑，但在多线程下会有线程安全问题：
// java
// 运行
// // 非原子操作（错误示例）
// if (!map.containsKey(key)) { // 步骤1：检查
//     V value = computeValue(key); // 步骤2：计算
//     map.put(key, value); // 步骤3：插入
// }
// return map.get(key);
    //按理说上面的代码也能进行改造从而变成线程安全
    //首先外层if判断是否存在key，然后里面用synchronized锁来保证只有一个线程能修改，当然同步代码块中也要再进行一次if判断键是否存在，因为可能在这之前已经有其他线程执行完了同步代码块并且插入了键值对
    //最后完成键值对的插入即可，但是会有变量不可见的问题
    //例子: 比如说线程二先执行完了同步代码块，键值对已经被插入进去了，等到线程一执行同步代码块的时候，进行if判断，但是由于变量不可见的问题，线程一看不到线程二插入的键值对，从而又进行了一次插入操作，导致数据不一致
    //解决办法: 使用volatile修饰变量，保证变量的可见性
    //所以针对你的问题："volatile 加到哪里"？
   //如果你研究过concurrentHashMap的源码的话，你就会现它是通过给 table：核心存储数组，本质：Node<K,V>[] 数组，每个元素是一个「链表头节点」或「红黑树的 TreeBin 包装节点」加上了volatile修饰，保证数组引用的可见性 —— 当数组扩容替换（table = nextTable）或某个位置节点被修改（如链表新增节点）时，其他线程能立即看到最新状态，避免并发下的脏读。
    //所以单纯在HashMap上加volatile修饰，并不能解决多线程下的线程安全问题









/*    「同步块内的可见性问题」：synchronized 已能解决，无需额外 volatile
    你担心「线程一进入同步块后，看不到线程二插入的键值对」，这个担心在「锁对象正确」的前提下是多余的 —— 因为 synchronized 本身就包含「可见性保障」：根据 Java 的 happens-before 规则：线程 A 退出同步块的写操作 → 线程 B 进入同一个同步块的读操作，这两个操作之间有 happens-before 关系，线程 B 能看到线程 A 写的所有数据。
    举个例子：
    线程 B 先获取锁，执行完 map.put(key, value) 后释放锁（写操作）；
    线程 A 等待锁后获取成功，进入同步块执行 map.containsKey(key)（读操作）；
    由于 synchronized 的 happens-before 保障，线程 A 一定能看到线程 B 插入的键值对，不会出现「不可见」导致的重复插入。
    那什么时候需要 volatile 配合？只有当「不使用全量 synchronized 锁」时（比如 ConcurrentHashMap 为了提高并发度，用「局部锁（锁住链表头节点）+ CAS」替代全量锁），才需要 volatile 保障内部结构的可见性。如果是普通 HashMap 改造（用全量锁），同步块已经解决了可见性，无需额外给内部结构加 volatile（当然，改造普通 HashMap 时我们也改不了它的源码，所以直接用同步块即可）。*/

    //这样才算理解了多线程的线程安全问题，我感觉自己又蜕变了。。。嘻嘻嘻



// 上述代码在多线程下可能出现：多个线程同时通过 containsKey 检查（都认为键不存在），导致重复执行 computeValue（浪费资源），且最终 put 操作可能覆盖彼此的结果（数据不一致）。
// 而 computeIfAbsent 通过原子性保证，确保只有一个线程会执行 mappingFunction 计算，其他线程会等待并直接获取已计算的值，从根本上避免了问题。
// 二、computeIfAbsent 能解决的错误代码场景
// 场景 1：多线程初始化「键 - 集合」结构
// 实际开发中，经常需要用 ConcurrentHashMap 存储「键→集合（如 List、Set）」的映射（例如分组统计）。若用普通方法初始化集合，可能导致重复创建集合对象。
// 错误代码：
// java
// 运行
// ConcurrentHashMap<String, List<Integer>> map = new ConcurrentHashMap<>();

// // 多线程执行此方法，为key添加元素
// public void addElement(String key, int num) {
//     // 步骤1：检查key是否存在
//     if (!map.containsKey(key)) {
//         // 步骤2：初始化集合（可能多个线程同时执行）
//         map.put(key, new ArrayList<>()); 
//     }
//     // 步骤3：向集合添加元素
//     map.get(key).add(num);
// }
// 问题：多线程同时进入 if 分支，导致多个 new ArrayList<>() 被创建，最终只有一个会被 put 成功（其他被覆盖），造成内存浪费。
// 正确代码（用 computeIfAbsent）：
// java
// 运行
// public void addElement(String key, int num) {
//     // 原子操作：若key不存在，初始化List；若存在，直接返回已有List
//     List<Integer> list = map.computeIfAbsent(key, k -> new ArrayList<>());
//     list.add(num); // 注意：ArrayList本身非线程安全，若需并发修改需用Collections.synchronizedList
// }
// 解决原理：computeIfAbsent 保证只有一个线程会执行 k -> new ArrayList<>()，其他线程会直接获取已创建的 List，避免重复初始化。
// 场景 2：缓存加载（避免重复计算 / 查询）
// 缓存场景中，若缓存未命中，需要从数据库 / 文件加载数据并写入缓存。若用普通方法，可能导致多个线程同时加载数据（缓存击穿）。
// 错误代码：
// java
// 运行
// ConcurrentHashMap<String, Data> cache = new ConcurrentHashMap<>();

// // 多线程查询缓存，未命中则加载数据
// public Data getFromCache(String key) {
//     Data data = cache.get(key);
//     // 步骤1：缓存未命中
//     if (data == null) {
//         // 步骤2：多线程可能同时执行加载（如查数据库）
//         data = loadFromDB(key); 
//         cache.put(key, data);
//     }
//     return data;
// }
// 问题：缓存未命中时，多个线程同时执行 loadFromDB（耗时操作），导致数据库压力骤增（缓存击穿），且重复加载的数据会覆盖彼此。
// 正确代码（用 computeIfAbsent）：
// java
// 运行
// public Data getFromCache(String key) {
//     // 原子操作：未命中则加载，命中则直接返回
//     return cache.computeIfAbsent(key, k -> loadFromDB(k));
// }
// 解决原理：computeIfAbsent 确保只有一个线程执行 loadFromDB，其他线程会等待并复用已加载的数据，避免重复查询数据库。
// 场景 3：避免 putIfAbsent + 后续操作的冗余
// putIfAbsent 能保证插入的原子性，但如果需要基于插入的结果做后续操作（如修改值），computeIfAbsent 更简洁。
// 冗余代码（用 putIfAbsent）：
// java
// 运行
// ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

// public void increment(String key) {
//     // 步骤1：若key不存在，初始化0
//     map.putIfAbsent(key, 0);
//     // 步骤2：获取并自增（仍需原子操作，否则有线程安全问题）
//     map.replace(key, v -> v + 1); 
// }
// 上述代码虽然能工作，但 putIfAbsent 和 replace 是两个独立操作，略显冗余。
// 简化代码（用 computeIfAbsent）：
// java
// 运行
// public void increment(String key) {
//     // 若key不存在，初始化0；然后直接自增（结合其他原子方法更简洁）
//     map.computeIfAbsent(key, k -> 0);
//     map.replace(key, v -> v + 1);
// }
// 甚至可以进一步用 compute 方法合并，但 computeIfAbsent 在此处清晰表达了「初始化」的意图。
// 三、注意事项
// mappingFunction 不能为 null，否则会抛出 NullPointerException。
// mappingFunction 计算过程需线程安全：函数内部若涉及共享资源操作，需自行保证线程安全（computeIfAbsent 只保证方法本身的原子性，不保证函数内部逻辑）。
// mappingFunction 不应阻塞或耗时过长：因为方法执行时可能对节点加锁，长时间阻塞会降低并发效率。
// 函数返回 null 时不插入数据：若 mappingFunction.apply(key) 返回 null，computeIfAbsent 会返回 null，且不会向 map 中插入任何键值对（相当于「不存在则不操作」）。
// 与 HashMap.computeIfAbsent 的区别：HashMap 也有该方法，但它非线程安全（多线程下可能重复计算），而 ConcurrentHashMap 的实现是线程安全的原子操作，这是核心差异。
    
}
