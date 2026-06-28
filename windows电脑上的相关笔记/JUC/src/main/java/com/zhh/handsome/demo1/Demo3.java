package com.zhh.handsome.demo1;

public class Demo3 {
    //被多个线程同时操作的变量就会出现线程安全问题。所以说局部变量还是爽，天然线程安全，因为都处在各自的栈帧中，不会被其他线程所访问到。。。
    ///听的头疼，好累啊，我操了。。。

    /*for (int i = 0; i < 2000; i++) {
        Thread thread = new Thread(() -> {
            // 买票
            int amount = window.sell(random(amount: 5));
            // 统计买票数
            amountList.add(amount);
        });
        threadList.add(thread);
        thread.start();
    }*/

    /*像这种写法是不是会导致当一个线程执行添加操作时，刚做完检查应添加到哪个索引，然后时间片用完了，被其他线程抢走，同样执行添加操作，并检查到同样的索引位置，这时多个线程就会往同一个地方不断添加。从而导致覆盖，对吗
    你的理解是正确的，这种写法确实会导致线程安全问题，核心原因是 amountList（假设是 ArrayList 等非线程安全的集合）的 add 操作不是原子操作，多线程并发执行时会出现 “覆盖”“数据不一致” 甚至 “数组越界” 等问题。
    具体分析：以 ArrayList 的 add 操作为例
    ArrayList 的 add 方法内部逻辑大致分为两步（简化版）：

    java
    public boolean add(E e) {
        // 步骤1：确保数组容量足够（可能涉及扩容、复制数组等操作）
        ensureCapacityInternal(size + 1);
        // 步骤2：把元素放到数组末尾，然后 size++
        elementData[size++] = e;
        return true;
    }

    这两步操作不是原子的（即无法 “一口气做完”）。当多线程并发调用 add 时，会出现以下问题：
            1. 元素覆盖风险
    假设线程 A 和线程 B 同时执行 add：

    线程 A 执行到 elementData[size++] = e 时，刚计算好要把元素放到索引 n 的位置，但还没执行 size++，此时时间片用完，被线程 B 抢走。
    线程 B 执行 add，此时 size 还是原来的值，所以线程 B 也计算出要把元素放到同一个索引 n 的位置，执行 elementData[n] = e 并完成 size++。
    线程 A 恢复执行，继续把自己的元素放到索引 n 的位置，覆盖线程 B 刚放入的元素。
            2. size 计数错误
    size++ 本身是 “读取 - 修改 - 写入” 的非原子操作（比如线程 A 读 size=5，线程 B 也读 size=5，各自加 1 后写回，最终 size 可能只增加了 1，而非 2），导致集合的 “元素数量” 统计错误。
            3. 数组越界或扩容异常
    如果多个线程同时触发 “扩容” 逻辑，可能导致数组复制时数据紊乱，甚至因为并发修改数组长度，直接抛出 ArrayIndexOutOfBoundsException。
    解决办法
    要解决这种线程安全问题，有以下几种常见方式：

    使用线程安全的集合：例如 CopyOnWriteArrayList（写时复制，适合读多写少场景）、Collections.synchronizedList(new ArrayList<>())（全局加锁，适合并发度不高的场景）。
    手动加锁：在 add 操作前后用 synchronized 或 ReentrantLock 加锁，保证同一时间只有一个线程能执行 add。
    使用并发容器的原子方法：如果是 Map 类需求，可考虑 ConcurrentHashMap 等并发容器的原子操作。

    总之，非线程安全的集合（如 ArrayList）在多线程并发修改时，必然会因为 “操作非原子性” 和 “共享状态竞争” 出现问题，需要通过线程安全的容器或同步手段解决。*/


}
