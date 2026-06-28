package com.zhh.handsome.并发包中与CAS有关的类;

import java.util.concurrent.atomic.AtomicInteger;

public class AutomicIntegerDemo {
    public static void main(String[] args) {
        AtomicInteger at=new AtomicInteger(0);
        at.incrementAndGet();
        at.decrementAndGet();
//        at.compareAndSet(a, a + 1);
        at.get();
        at.updateAndGet(a -> a + 1);
    }

}











/*

一、Java AtomicInteger 核心实现（聚焦 CAS）
Java 的 AtomicInteger 基于sun.misc.Unsafe类的本地（native）CAS 方法实现，核心是通过硬件级别的原子指令保证操作原子性。以下是核心代码片段（JDK 8+）：
java
        运行
package java.util.concurrent.atomic;

import sun.misc.Unsafe;

public class AtomicInteger extends Number implements java.io.Serializable {
    private static final long serialVersionUID = 6214790243416807050L;

    // 核心值：volatile保证可见性，CAS操作的目标
    private volatile int value;

    // JDK底层工具类，提供CAS等原子操作
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    // value字段在对象中的内存偏移量（CAS需要通过偏移量直接操作内存）
    private static final long valueOffset;

    // 静态初始化：计算value字段的内存偏移地址
    static {
        try {
            valueOffset = unsafe.objectFieldOffset(
                    AtomicInteger.class.getDeclaredField("value")
            );
        } catch (Exception ex) { throw new Error(ex); }
    }

    // 核心CAS方法：比较并设置值（最关键的部分）
    public final boolean compareAndSet(int expect, int update) {
        // 调用Unsafe的native CAS方法
        // 参数：当前对象、字段偏移量、预期值、新值
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }

    // 基于CAS的原子自增（示例：其他原子操作均基于CAS循环实现）
    public final int getAndIncrement() {
        // 底层是CAS循环：失败则重试，直到成功
        return unsafe.getAndAddInt(this, valueOffset, 1);
    }

    // 其他基础方法（省略）
    public final int get() { return value; }
    public AtomicInteger(int initialValue) { value = initialValue; }
}
Unsafe 类的 CAS 核心声明（native 方法）
compareAndSwapInt是本地方法，真正的 CAS 逻辑在 JVM 底层实现：
java
        运行
package sun.misc;

public final class Unsafe {
    // CAS核心native方法
    // 参数：目标对象、字段偏移量、预期值、新值
    // 返回：true=CAS成功，false=CAS失败（值不匹配）
    public final native boolean compareAndSwapInt(Object o, long offset, int expected, int x);
}
JVM 底层 C++ 实现（HotSpot，x86 架构）
Unsafe 的 native 方法最终调用 CPU 的 CAS 指令，以下是 HotSpot 虚拟机的核心 C++ 代码（简化版）：
cpp
        运行
// 路径：src/share/vm/prims/unsafe.cpp
UNSAFE_ENTRY(jboolean, Unsafe_CompareAndSwapInt(
        JNIEnv *env, jobject unsafe, jobject obj, jlong offset, jint expect, jint update
))
UnsafeWrapper("Unsafe_CompareAndSwapInt");
// 解析Java对象的内存地址
oop p = JNIHandles::resolve(obj);
// 计算字段的实际内存地址（对象基地址 + 偏移量）
jint* addr = (jint *) index_oop_from_field_offset_long(p, offset);
// 调用平台相关的原子CAS操作
  return (jboolean)(Atomic::cmpxchg(update, addr, expect) == expect);
UNSAFE_END

// x86架构的CAS实现（路径：src/os_cpu/linux_x86/vm/atomic_linux_x86.inline.hpp）
inline jint Atomic::cmpxchg(jint exchange_value, volatile jint* dest, jint compare_value) {
    // 判断是否为多处理器（多处理器需要lock前缀保证原子性）
    int mp = os::is_MP();
    // 内嵌汇编：lock cmpxchgl 是x86的CAS核心指令
    __asm__ volatile (
            LOCK_IF_MP(%4) "cmpxchgl %1,(%3)"
    : "=a" (exchange_value)
    : "r" (exchange_value), "a" (compare_value), "r" (dest), "r" (mp)
    : "cc", "memory"
  );
    return exchange_value;
}

// 宏定义：多处理器时添加lock前缀
#define LOCK_IF_MP(mp) "cmp $0, " #mp "; je 1f; lock; 1: "*/











//updateAndGet方法的原理

//我的思路是先写一个while，然后get一下，得到预期值
//然后开始CAS原子更新，第一步先再次get一下，得到当前值，然后判断当前值是否等于预期值，如果等于则进行CAS更新（执行用户自定义逻辑），否则重新开始while循环
























/*

//updateAndGet方法的原理

//我的思路是先写一个while，然后get一下，得到预期值
//然后开始CAS原子更新，这个CAS实际上包括以下的步骤，第一步先再次get一下，得到当前值，然后判断当前值是否等于预期值，如果等于则进行CAS更新（执行用户自定义逻辑），否则重新开始while循环，对吧
你想弄清楚 updateAndGet 方法的底层实现原理，并且自己梳理了 “while 自旋 + CAS 原子更新” 的核心思路，想确认这个思路是否正确，同时理解整个执行流程。
updateAndGet 方法的核心原理
你的思路大体上是完全正确的，updateAndGet 是原子类（如 Java 的 AtomicInteger、AtomicLong 等）中的核心方法，其本质就是自旋（while 循环）+ CAS 原子操作，实现无锁的乐观更新，保证操作的原子性。
下面以 Java 的 AtomicInteger 的 updateAndGet 为例，拆解完整的执行逻辑和原理：
        1. 官方核心实现逻辑（简化版）
java
        运行
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

public class AtomicUpdateDemo {
    public static void main(String[] args) {
        AtomicInteger atomicInt = new AtomicInteger(10);
        // 调用updateAndGet，自定义更新逻辑：值+5
        int result = atomicInt.updateAndGet(value -> value + 5);
        System.out.println(result); // 输出15
    }
}

// 模拟AtomicInteger中updateAndGet的核心实现
class MockAtomicInteger {
    private volatile int value; // 保证可见性

    public MockAtomicInteger(int initialValue) {
        this.value = initialValue;
    }

    // 核心方法：updateAndGet
    public final int updateAndGet(IntUnaryOperator updateFunction) {
        int prev, next;
        // 自旋（while循环）：更新失败就重试
        do {
            // 1. 获取当前值作为「预期值」（prev）
            prev = get();
            // 2. 执行用户自定义的更新逻辑，计算新值（next）
            next = updateFunction.applyAsInt(prev);
            // 3. CAS操作：对比当前值是否等于预期值prev
            //    - 相等：将值更新为next，返回true，退出循环
            //    - 不等：返回false，重新进入循环重试
        } while (!compareAndSet(prev, next));
        // 4. 更新成功，返回新值
        return next;
    }

    // 获取当前值（volatile保证可见性）
    public final int get() {
        return value;
    }

    // CAS原子操作（底层依赖Unsafe类的native方法，硬件级别的原子性）
    public final boolean compareAndSet(int expect, int update) {
        // 底层逻辑：
        // 1. 再次读取当前内存中的实际值（而非本地缓存）
        // 2. 对比：实际值 == expect？
        // 3. 若是：将内存值改为update，返回true
        // 4. 若否：不修改，返回false
        // 注：这一步是CPU的原子指令（cmpxchg），不可中断
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }

    // 以下为Unsafe相关占位（仅示意，无需关注细节）
    private static final sun.misc.Unsafe unsafe = sun.misc.Unsafe.getUnsafe();
    private static final long valueOffset;
    static {
        try {
            valueOffset = unsafe.objectFieldOffset(MockAtomicInteger.class.getDeclaredField("value"));
        } catch (Exception ex) { throw new Error(ex); }
    }
}
2. 关键步骤解释（对应你的思路）
你的理解完全正确，补充几个关键细节：
自旋（while 循环）：因为 CAS 可能失败（比如其他线程已经修改了值），所以通过循环不断重试，直到更新成功。
CAS 的原子性：compareAndSet 不是普通的 “判断 + 赋值”（这两步非原子），而是底层依赖 CPU 的 cmpxchg 原子指令，保证 “读取 - 比较 - 更新” 三步是一个不可中断的原子操作。
volatile 保证可见性：value 被 volatile 修饰，确保每次 get() 都能读到最新的内存值，而非线程本地缓存。
        3. 执行流程举例
假设线程 A 调用 atomicInt.updateAndGet(v -> v+5)（初始值 10）：
第一次进入 do-while：prev=10 → next=15 → CAS 检查当前值是否为 10，若是则更新为 15，返回 true，循环结束，返回 15。
若此时线程 B 同时修改了值（比如改成 11），则线程 A 的 CAS 会返回 false → 重新进入循环：prev=11 → next=16 → 再次 CAS，直到成功。*/
