/*
package com.zhh.handsome.并发包中与CAS有关的类;

public class AutomicReferenceDemo {









    一、AtomicReference 的起源：为什么需要它？
    在并发编程中，JDK 提供的 AtomicInteger、AtomicLong 等原子类只能处理基本数据类型的原子操作，但实际开发中，我们经常需要对对象引用（比如自定义的 User、Order 对象）进行原子操作 —— 而普通的对象引用赋值（obj = new Obj()）是非原子的，多线程下直接修改引用会导致线程安全问题（比如指令重排、可见性问题、更新丢失）。
    举个典型问题：
    java
            运行
    // 普通引用赋值，非原子操作
    private User user;
    // 多线程同时执行：可能出现一个线程赋值一半，另一个线程读取到不完整的引用
    public void updateUser(User newUser) {
        this.user = newUser;
    }
    为了解决对象引用的原子更新问题，JDK 1.5 引入了 java.util.concurrent.atomic.AtomicReference 类，它的核心目标是：让对象引用的 “读取 - 比较 - 更新” 操作具备原子性，无需加锁（synchronized/Lock）就能保证线程安全。
    二、AtomicReference 的核心用法
    AtomicReference 的使用逻辑和 AtomicInteger 高度一致，只是操作的对象从 “基本类型值” 变成了 “对象引用（内存地址）”。下面通过完整示例讲解核心用法：
            1. 基础用法：CAS 原子更新引用
    java
            运行
import java.util.concurrent.atomic.AtomicReference;

    // 自定义引用类型（演示用）
    class User {
        private String name;
        private int age;

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        // 省略getter/toString，方便打印
        public String getName() { return name; }
        public int getAge() { return age; }
        @Override
        public String toString() {
            return "User{name='" + name + "', age=" + age + "}";
        }
    }

    public class AtomicReferenceDemo {
        public static void main(String[] args) {
            // 1. 初始化AtomicReference，绑定初始引用对象
            User initialUser = new User("张三", 20);
            AtomicReference<User> atomicUserRef = new AtomicReference<>(initialUser);

            // 2. 定义新的目标对象
            User newUser = new User("李四", 25);

            // 3. CAS原子更新：对比并替换引用
            // 核心方法：compareAndSet(预期引用, 新引用)
            // - 成功：返回true，引用被替换为newUser
            // - 失败：返回false，引用保持不变
            boolean updateSuccess = atomicUserRef.compareAndSet(initialUser, newUser);
            System.out.println("更新是否成功：" + updateSuccess); // 输出true
            System.out.println("当前引用对象：" + atomicUserRef.get()); // 输出User{name='李四', age=25}

            // 4. 再次CAS（预期值不匹配，更新失败）
            boolean updateFail = atomicUserRef.compareAndSet(initialUser, new User("王五", 30));
            System.out.println("更新是否成功：" + updateFail); // 输出false
            System.out.println("当前引用对象：" + atomicUserRef.get()); // 仍为李四的对象
        }
    }
2. 进阶用法：自旋 + CAS 实现复杂更新（对应 updateAndGet）
    和 AtomicInteger 的 updateAndGet 类似，AtomicReference 也提供了 updateAndGet 方法，底层就是 “自旋 + CAS”，可以自定义引用的更新逻辑：
    java
            运行
    public class AtomicReferenceUpdateDemo {
        public static void main(String[] args) {
            AtomicReference<User> atomicUserRef = new AtomicReference<>(new User("张三", 20));

            // 使用updateAndGet自定义更新逻辑：年龄+5
            User updatedUser = atomicUserRef.updateAndGet(user -> {
                // 自定义更新逻辑：基于当前引用对象创建新对象（不可变对象更安全）
                return new User(user.getName(), user.getAge() + 5);
            });

            System.out.println("更新后的对象：" + updatedUser); // 输出User{name='张三', age=25}
            System.out.println("原子引用中的对象：" + atomicUserRef.get()); // 和上面一致
        }
    }
3. 典型应用场景
    无锁的对象状态更新：比如并发环境下更新配置对象、缓存对象，避免加锁的性能开销；
    实现无锁队列 / 栈：比如基于 AtomicReference 实现无锁链表的节点引用更新；
    乐观锁实现：比如数据库乐观锁的内存层面替代方案，通过 CAS 检查引用是否被修改。
    三、AtomicReference 的底层原理
    AtomicReference 的核心原理和 AtomicInteger 完全一致，只是操作的类型从 int 变成了 Object 引用，拆解如下：
            1. 核心字段（简化版源码）
    java
            运行
    public class AtomicReference<V> implements java.io.Serializable {
        // 底层依赖Unsafe类（提供硬件级原子操作）
        private static final Unsafe unsafe = Unsafe.getUnsafe();
        // value字段的内存偏移量（用于Unsafe直接操作内存）
        private static final long valueOffset;

        // 存储对象引用的核心字段，volatile保证可见性
        private volatile V value;

        static {
            try {
                // 获取value字段的内存偏移地址
                valueOffset = unsafe.objectFieldOffset
                        (AtomicReference.class.getDeclaredField("value"));
            } catch (Exception ex) { throw new Error(ex); }
        }

        // 构造方法：初始化引用
        public AtomicReference(V initialValue) {
            value = initialValue;
        }
    }
    关键：value 被 volatile 修饰，确保多线程下引用的可见性；Unsafe 类用于直接操作内存地址，绕过 JVM 层面的锁机制。
            2. CAS 核心方法（compareAndSet）
    java
            运行
    public final boolean compareAndSet(V expect, V update) {
        // 调用Unsafe的compareAndSwapObject方法，原子操作对象引用
        return unsafe.compareAndSwapObject(this, valueOffset, expect, update);
    }
    compareAndSwapObject 是 Unsafe 提供的 native 方法，底层对应 CPU 的 cmpxchg 原子指令（处理指针 / 引用类型）；
    执行逻辑：
    根据 valueOffset 读取当前 AtomicReference 中 value 字段的内存地址；
    对比该地址存储的引用是否等于 expect（预期引用）；
    若相等：将该地址的引用替换为 update（新引用），返回 true；
    若不等：不修改，返回 false；
    整个过程是硬件级别的原子操作，不可中断。
            3. updateAndGet 底层逻辑（简化版）
    java
            运行
    public final V updateAndGet(UnaryOperator<V> updateFunction) {
        V prev, next;
        do {
            prev = get(); // 获取当前引用（volatile保证最新）
            next = updateFunction.apply(prev); // 自定义更新逻辑，生成新引用
        } while (!compareAndSet(prev, next)); // CAS失败则自旋重试
        return next;
    }
    和 AtomicInteger 的 updateAndGet 逻辑完全一致，只是操作的是对象引用而非 int 值。
    总结
    起源：AtomicReference 是为解决对象引用的原子更新问题而生，弥补了基础原子类只能处理基本类型的不足；
    核心用法：通过 compareAndSet 实现原子替换引用，通过 updateAndGet 实现自定义逻辑的自旋 + CAS 更新，无需加锁即可保证线程安全；
    底层原理：基于 volatile 保证引用的可见性，基于 Unsafe 类的 compareAndSwapObject 方法（CPU 原子指令）实现 CAS，失败时通过自旋重试，核心逻辑和 AtomicInteger 一致，只是操作类型为对象引用。































}
*/
