//package com.zhh.handsome.Demo2;
//
//public class CASAndVolatile {
//
//
//    二、两者的联系与互补性
//    CAS 和 volatile 常配合使用，核心原因是：CAS 依赖 “读取最新的共享值”，而 volatile 为其提供了 “可见性保障”。
//    以 AtomicInteger 为例（最典型的结合场景），拆解它们的协作逻辑：
//    java
//            运行
//    public class AtomicInteger extends Number implements java.io.Serializable {
//        // 共享变量value用volatile修饰：
//        // 1. 保证“其他线程修改value后，当前线程能立刻读到最新值”（可见性）；
//        // 2. 禁止指令重排序，确保value的读写逻辑有序。
//        private volatile int value;
//
//        // CAS操作的核心方法：依赖value的最新值做“比较-交换”
//        public final boolean compareAndSet(int expect, int update) {
//            // 调用Unsafe类的native方法，底层走CPU原子指令
//            return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
//        }
//    }
//    具体协作流程：
//    volatile 保证 “可见性”：当线程 A 修改了 value，线程 B 能立刻读到最新的 value（避免线程缓存旧值）。
//    CAS 基于最新值做 “原子操作”：线程执行 compareAndSet(expect, update) 时，能确保 “预期值 expect” 是内存中最新的 value，从而保证 CAS 逻辑的正确性。
//    三、补充：两者的 “局限性” 与 “互补性”
//    volatile 的局限性：仅保证可见性和有序性，不保证 “复合操作的原子性”。例如：
//    java
//            运行
//    volatile int i = 0;
//    i++; // 这不是原子操作！因为i++包含“读取→修改→写入”三步，多线程下会有并发问题
//    CAS 的解决思路：通过原子指令将 “读取→比较→交换” 封装为原子操作，解决上述复合操作的原子性问题。例如 AtomicInteger 的 incrementAndGet() 方法：
//    java
//            运行
//    public final int incrementAndGet() {
//        while (true) {
//            int expect = get(); // 读取最新的value（依赖volatile的可见性）
//            int update = expect + 1;
//            if (compareAndSet(expect, update)) { // CAS保证“+1”操作的原子性
//                return update;
//            }
//        }
//    }
//    总结
//    联系：CAS 依赖 volatile 提供的 “可见性” 来获取最新的共享值，两者结合实现了 “无锁并发” 的核心逻辑（典型如 Atomic 系列类）。
//    互补性：volatile 解决 “可见性、有序性”，CAS 解决 “原子性”，两者协作覆盖了多线程并发中的三大核心问题（可见性、有序性、原子性）。
//}
