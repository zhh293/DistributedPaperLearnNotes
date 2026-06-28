package com.zhh.handsome.demo1;

public class Synchronized {

    private static final Object lock=new Object();

    public static void main(String[] args){
        synchronized (lock){
//            out();
            new Thread(() -> {
                out();
            }).start();
            System.out.println("离开临界区");
        }
    }
    public static void out(){
        synchronized (lock) {
            System.out.println("进入临界区1111");
        }
    }
}

    /*在 Java 中，synchronized是用于解决多线程并发安全问题的核心关键字，它通过互斥锁机制保证同一时间只有一个线程能执行特定代码块，从而避免多线程对共享资源的无序访问导致的数据不一致问题。下面从作用、用法、底层原理和关键细节四个维度全面讲解：
    一、synchronized 的核心作用
    synchronized的核心作用是实现线程同步，具体体现在三个方面：

    互斥性（原子性）
    同一时间只有一个线程能进入synchronized保护的代码块（或方法），其他线程必须等待当前线程释放锁后才能竞争进入，确保对共享资源的操作是 “原子的”（不可分割）。
    可见性
    线程释放锁时，会将工作内存中修改的共享变量刷新到主内存；线程获取锁时，会清空工作内存中共享变量的值，从主内存重新读取。这保证了一个线程对共享资源的修改，其他线程能立即看到。
    有序性
    通过锁机制隐式禁止了指令重排序（仅针对synchronized块内外的代码），确保代码执行顺序与逻辑顺序一致。
    二、synchronized 的三种用法
    synchronized可以修饰实例方法、静态方法和代码块，不同用法对应不同的 “锁对象”，而锁对象的选择直接决定了同步范围。
            1. 修饰实例方法（对象锁）
    当synchronized修饰实例方法时，锁对象是当前对象实例（this）。
    这意味着：同一个对象实例的所有synchronized实例方法，会共享同一把锁；不同对象实例的synchronized实例方法，使用不同的锁（互不干扰）。

    示例：

    java
            运行
    public class SyncDemo {
        // synchronized修饰实例方法，锁对象是当前实例（this）
        public synchronized void method1() {
            // 临界区代码（操作共享资源）
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("method1执行完毕");
        }

        // 同一实例的另一个synchronized方法，共享同一把锁
        public synchronized void method2() {
            System.out.println("method2执行完毕");
        }

        public static void main(String[] args) {
            SyncDemo demo = new SyncDemo(); // 同一实例

            // 线程1调用method1
            new Thread(demo::method1).start();
            // 线程2调用method2（需等待线程1释放锁后才能执行）
            new Thread(demo::method2).start();
        }
    }

    输出（间隔约 1 秒）：

    plaintext
            method1执行完毕
    method2执行完毕

    原因：两个线程使用同一个demo实例，竞争同一把锁，线程 2 必须等待线程 1 的method1执行完才会执行method2。


            2. 修饰静态方法（类锁）
    当synchronized修饰静态方法时，锁对象是当前类的Class对象（每个类在 JVM 中只有一个Class对象）。
    这意味着：所有该类的实例（无论多少个对象）调用synchronized静态方法时，都会竞争同一把锁（类锁）。

    示例：

    java
            运行
    public class SyncStaticDemo {
        // synchronized修饰静态方法，锁对象是SyncStaticDemo.class
        public static synchronized void staticMethod1() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("staticMethod1执行完毕");
        }

        // 同一类的另一个synchronized静态方法，共享类锁
        public static synchronized void staticMethod2() {
            System.out.println("staticMethod2执行完毕");
        }

        public static void main(String[] args) {
            SyncStaticDemo demo1 = new SyncStaticDemo();
            SyncStaticDemo demo2 = new SyncStaticDemo(); // 不同实例

            // 线程1调用demo1的staticMethod1
            new Thread(demo1::staticMethod1).start();
            // 线程2调用demo2的staticMethod2（需等待线程1释放类锁）
            new Thread(demo2::staticMethod2).start();
        }
    }

    输出（间隔约 1 秒）：

    plaintext
            staticMethod1执行完毕
    staticMethod2执行完毕

    原因：静态方法的锁是SyncStaticDemo.class，无论demo1还是demo2，调用静态同步方法时都竞争同一把类锁。
            3. 修饰代码块（显式指定锁对象）
    synchronized代码块需要显式指定锁对象（语法：synchronized(锁对象) { ... }），锁对象可以是任意非null对象（通常推荐使用专门的锁对象，如Object lock = new Object();）。
    这种方式的灵活性更高：可以精确控制同步范围（只同步需要保护的代码），避免对整个方法同步导致的性能开销。

    常见的锁对象选择：

            this：等同于实例方法的锁（锁定当前对象）；
    类名.class：等同于静态方法的锁（锁定类对象）；
    自定义对象：用于隔离不同的同步场景（不同锁对象的代码块互不干扰）。

    示例（自定义锁对象）：

    java
            运行
    public class SyncBlockDemo {
        private Object lock1 = new Object(); // 自定义锁1
        private Object lock2 = new Object(); // 自定义锁2

        public void methodA() {
            synchronized (lock1) { // 使用lock1作为锁
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("methodA执行完毕");
            }
        }

        public void methodB() {
            synchronized (lock2) { // 使用lock2作为锁（与lock1无关）
                System.out.println("methodB执行完毕");
            }
        }

        public static void main(String[] args) {
            SyncBlockDemo demo = new SyncBlockDemo();
            new Thread(demo::methodA).start();
            new Thread(demo::methodB).start(); // 无需等待methodA，因为锁不同
        }
    }

    输出（几乎同时）：

    plaintext
            methodB执行完毕
    methodA执行完毕

    原因：methodA和methodB使用不同的锁对象（lock1和lock2），两个线程无需竞争，可并行执行。
    三、关键细节与原理
1. 锁的本质：对象监视器（Monitor）
    Java 中每个对象都有一个内置的监视器（Monitor），synchronized的锁机制就是基于 Monitor 实现的：

    当线程进入synchronized块时，会尝试获取对象的 Monitor（通过monitorenter指令）；
    当线程退出synchronized块时，会释放 Monitor（通过monitorexit指令）；
    同一时间只有一个线程能持有 Monitor，其他线程会被阻塞在入口处，直到 Monitor 被释放。
            2. 可重入性（Reentrancy）
    synchronized是可重入锁：同一线程可以多次获取同一把锁，不会导致死锁。
    原理：Monitor 内部维护了一个 “计数器”，线程首次获取锁时计数器为 1；线程再次获取同一把锁时，计数器递增（如 2）；线程释放锁时，计数器递减，直到 0 时真正释放锁。

    示例：

    java
            运行
    public class ReentrantDemo {
        public synchronized void outer() {
            System.out.println("进入outer方法");
            inner(); // 同一线程调用inner（也是synchronized方法）
        }

        public synchronized void inner() {
            System.out.println("进入inner方法"); // 可正常执行，不会死锁
        }

        public static void main(String[] args) {
            new ReentrantDemo().outer();
        }
    }

    输出：

    plaintext
            进入outer方法
    进入inner方法

    原因：outer和inner共享同一把锁（this），主线程获取锁后调用inner时，可直接重入（计数器从 1→2）。
            3. 锁的升级（JVM 优化）
    早期synchronized是 “重量级锁”（依赖操作系统互斥量，开销大），但 JDK 1.6 后引入了锁升级机制，根据竞争程度动态调整锁的类型（从低开销到高开销）：

    偏向锁：无实际竞争时，锁会 “偏向” 第一个获取它的线程（在对象头 Mark Word 中记录线程 ID），后续该线程再次获取锁时无需竞争，直接通过 CAS 更新标记即可（几乎无开销）。
    轻量级锁：当有其他线程竞争时，偏向锁升级为轻量级锁。线程通过 CAS 尝试将对象头的 Mark Word 替换为自己的锁记录（栈上的一块空间），若成功则获取锁；若失败（仍有竞争），则膨胀为重量级锁。
    重量级锁：当竞争激烈时，轻量级锁升级为重量级锁，依赖操作系统的互斥量（Mutex）实现，线程会进入内核态阻塞（开销最大）。

    锁升级是不可逆的（只能从低到高升级，不能降级）。
            4. 与 wait ()/notify () 的配合
    synchronized必须与Object类的wait()、notify()、notifyAll()配合使用，用于线程间通信：

    wait()：使当前线程释放锁并进入等待状态，直到被其他线程notify()或notifyAll()唤醒；
    notify()：随机唤醒一个等待该锁的线程；
    notifyAll()：唤醒所有等待该锁的线程。

    注意：这三个方法必须在synchronized块 / 方法中调用（否则会抛IllegalMonitorStateException），因为它们需要先获取对象的 Monitor。

    示例：

    java
            运行
    public class WaitNotifyDemo {
        private static Object lock = new Object();
        private static boolean flag = false;

        public static void main(String[] args) {
            // 等待线程
            new Thread(() -> {
                synchronized (lock) {
                    while (!flag) { // 循环检查条件（避免虚假唤醒）
                        try {
                            System.out.println("条件不满足，等待...");
                            lock.wait(); // 释放锁并等待
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("条件满足，执行后续操作");
                }
            }).start();

            // 通知线程
            new Thread(() -> {
                synchronized (lock) {
                    flag = true;
                    System.out.println("条件已更新，通知等待线程");
                    lock.notify(); // 唤醒一个等待线程
                }
            }).start();
        }
    }
5. 锁的释放时机
    线程会在以下情况自动释放synchronized锁：

    同步块 / 方法正常执行完毕；
    同步块 / 方法中抛出未捕获的异常；
    调用锁对象的wait()方法（释放锁并进入等待状态）。

    注意：以下情况不会释放锁：

            线程调用Thread.sleep()或Thread.yield()（仅暂停执行，不释放锁）；
    线程被其他线程调用interrupt()（仅设置中断标记，不释放锁）。
            6. 与 volatile 的区别
    特性	synchronized	volatile
    原子性	保证（同步块内操作不可分割）	不保证（仅保证单次读 / 写可见）
    可见性	保证	保证
    有序性	保证（禁止重排序）	保证（禁止指令重排序）
    适用场景	复杂操作（如 i++）	简单变量读写（如状态标记）
    四、常见误区与注意事项
            锁对象不能为null
    synchronized(null) { ... }会抛NullPointerException，因为null没有 Monitor。
    避免用常量作为锁对象
    如String lock = "lock";，由于字符串常量池的存在，不同地方的"lock"可能是同一对象，导致无关代码块意外竞争同一把锁。
    同步范围不宜过大
    尽量只同步需要保护的代码（用代码块而非整个方法），否则会降低并发效率。
    静态同步与实例同步互不干扰
    静态同步方法（类锁）和实例同步方法（对象锁）使用不同的锁，彼此的执行不会相互阻塞。
    警惕 “锁膨胀”
    频繁竞争会导致锁从偏向锁→轻量级锁→重量级锁，而重量级锁开销大，应尽量避免不必要的锁竞争。
    总结
    synchronized是 Java 中最基础的线程同步机制，通过对象监视器实现互斥，保证原子性、可见性和有序性。其用法灵活（修饰方法或代码块），且 JVM 通过锁升级机制优化了性能。
    实际使用时需注意锁对象的选择、同步范围的控制，以及与wait()/notify()的配合，以高效解决并发安全问题。*/



