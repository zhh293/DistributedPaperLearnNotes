package com.zhh.handsome.可重入锁;

import java.util.concurrent.locks.ReentrantLock;

public class Demo {
    /*ReentrantLock 之所以灵活，核心在于它提供了 synchronized 不具备的几个关键特性：可重入、可打断、超时获取。这些特性让锁的控制更精细，能解决更多复杂场景的问题。下面逐个拆解，结合例子和原理讲清楚。
    一、可重入特性：自己不会锁死自己
    什么是可重入？一个线程获取锁后，再次请求同一把锁时不会被自己阻塞，而是直接成功（内部会记录 “重入次数”）。简单说就是：“自己持有的锁，自己可以再次获取”。
    为什么需要可重入？
    实际开发中，方法调用往往是嵌套的。如果锁不可重入，线程在嵌套调用中会自己阻塞自己，导致死锁。
    比如：
    java
            运行
    public class ReentrantDemo {
        private static final ReentrantLock lock = new ReentrantLock();

        public static void main(String[] args) {
            // 外层方法获取锁
            lock.lock();
            try {
                System.out.println("外层方法获取锁");
                innerMethod(); // 调用内层方法，内层也需要获取同一把锁
            } finally {
                lock.unlock();
            }
        }

        // 内层方法也需要获取锁
        private static void innerMethod() {
            lock.lock(); // 如果不可重入，这里会阻塞（自己等自己）
            try {
                System.out.println("内层方法获取锁");
            } finally {
                lock.unlock();
            }
        }
    }
    如果锁不可重入，innerMethod() 中的 lock.lock() 会让线程阻塞（因为线程已经持有锁，却还要等自己释放），导致死锁。但 ReentrantLock 是可重入的，所以上面代码能正常执行，输出：
    plaintext
            外层方法获取锁
    内层方法获取锁
    底层原理（简单说）
    ReentrantLock 基于 AQS 实现，AQS 中有一个 state 变量（int 类型），专门记录 “重入次数”：
    线程第一次获取锁时，state 从 0 变为 1，同时记录当前线程为 “持有锁的线程”。
    同一线程再次获取锁时，state 直接 +1（重入次数增加），无需重新竞争。
    释放锁时，每次 unlock() 会让 state -1；直到 state 减为 0，才真正释放锁（其他线程可以竞争）。
    关键注意点
    释放锁的次数必须和获取次数一致！如果少释放一次，state 不会归零，锁永远不会被真正释放，导致其他线程永远拿不到锁（死锁）。
    比如上面的例子，外层和内层各 lock() 一次，必须各 unlock() 一次，否则锁会残留。
    二、可打断特性：等待锁的线程可以 “中途放弃”
    什么是可打断？当线程正在等待获取锁时，如果其他线程调用了它的 interrupt() 方法，该线程可以 “响应中断”，停止等待锁，抛出 InterruptedException 并退出。简单说就是：“等锁的过程中，可以被‘叫走’”。
    为什么需要可打断？
    synchronized 的一个缺点是：等待锁的线程会一直死等，即使它已经不需要继续等待了（比如任务取消），也无法停止。而 ReentrantLock 的可打断特性可以避免这种 “无限等待”。
    代码例子：演示可打断
            java
    运行
import java.util.concurrent.locks.ReentrantLock;

    public class InterruptibleDemo {
        private static final ReentrantLock lock = new ReentrantLock();

        public static void main(String[] args) throws InterruptedException {
            // 线程A先获取锁，然后休眠（一直持有锁）
            Thread threadA = new Thread(() -> {
                lock.lock();
                try {
                    System.out.println("线程A获取到锁，开始休眠");
                    Thread.sleep(10000); // 持有锁10秒
                } catch (InterruptedException e) {
                    System.out.println("线程A被中断（但它已经持有锁，不影响）");
                } finally {
                    lock.unlock();
                    System.out.println("线程A释放锁");
                }
            }, "ThreadA");

            // 线程B尝试获取锁，但线程A持有，所以B会等待
            Thread threadB = new Thread(() -> {
                try {
                    System.out.println("线程B尝试获取锁...");
                    // 用 lockInterruptibly() 而不是 lock()，允许被打断
                    lock.lockInterruptibly();
                } catch (InterruptedException e) {
                    System.out.println("线程B在等待锁时被中断，放弃等待");
                    return; // 退出线程
                }
                try {
                    System.out.println("线程B获取到锁（如果A释放了的话）");
                } finally {
                    lock.unlock();
                }
            }, "ThreadB");

            threadA.start();
            Thread.sleep(1000); // 确保A先拿到锁
            threadB.start();
            Thread.sleep(2000); // 让B等待2秒后，打断它
            threadB.interrupt(); // 打断正在等待锁的B
        }
    }
    执行结果：
    plaintext
    线程A获取到锁，开始休眠
    线程B尝试获取锁...
    线程B在等待锁时被中断，放弃等待
（10秒后）
    线程A释放锁
    关键点：
    用 lockInterruptibly() 而不是 lock()：lock() 方法获取锁时，即使线程被中断，也不会响应（会继续等待锁）；而 lockInterruptibly() 会检测中断状态，若被中断则抛出异常。
    已经持有锁的线程（如线程 A）被中断时，不会受影响（只会设置中断标志，不会抛出异常），直到它调用 sleep() 等可中断方法时才会响应。
    底层原理（简单说）
    等待锁的线程会被放入 AQS 的同步队列，并用 LockSupport.park() 阻塞。当线程被 interrupt() 时，park() 会被唤醒，线程检查到中断标志后，会从队列中移除，抛出 InterruptedException，不再继续等待。
    三、超时特性：获取不到锁就 “超时放弃”
    什么是超时特性？线程尝试获取锁时，可以设置一个超时时间（比如 1 秒），如果在这段时间内没拿到锁，就放弃等待，返回 false。简单说就是：“等多久还拿不到，就不等了”。
    为什么需要超时？
    避免线程无限等待锁（比如其他线程意外死锁，永远不释放锁）。超时后线程可以做其他处理（比如重试、报错、走备用逻辑），提高系统容错性。
    代码例子：演示超时获取
            java
    运行
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

    public class TimeoutDemo {
        private static final ReentrantLock lock = new ReentrantLock();

        public static void main(String[] args) throws InterruptedException {
            // 线程A先获取锁，持有5秒
            Thread threadA = new Thread(() -> {
                lock.lock();
                try {
                    System.out.println("线程A获取到锁，持有5秒");
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                    System.out.println("线程A释放锁");
                }
            }, "ThreadA");

            // 线程B尝试在1秒内获取锁，超时就放弃
            Thread threadB = new Thread(() -> {
                try {
                    // 尝试获取锁，最多等1秒
                    boolean acquired = lock.tryLock(1, TimeUnit.SECONDS);
                    if (acquired) {
                        try {
                            System.out.println("线程B获取到锁");
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        System.out.println("线程B等待1秒后，仍未获取到锁，放弃");
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }, "ThreadB");

            threadA.start();
            Thread.sleep(1000); // 确保A先拿到锁
            threadB.start();
        }
    }
    执行结果：
    plaintext
    线程A获取到锁，持有5秒
    线程B等待1秒后，仍未获取到锁，放弃
（5秒后）
    线程A释放锁
    关键点：
    用 tryLock(long timeout, TimeUnit unit) 方法：返回 true 表示成功获取锁，false 表示超时未获取。
    超时时间是 “最多等待的时间”，如果在超时前锁被释放，会立即获取并返回 true。
    底层原理（简单说）
    线程调用 tryLock(timeout) 后，会先尝试直接获取锁（非公平模式下），失败则进入同步队列阻塞。AQS 会计算 “超时截止时间”，如果到截止时间还没获取到锁，线程会从队列中移除，返回 false；如果在截止前锁被释放，会被唤醒并尝试获取锁。
    总结：三个特性的核心价值
    特性	核心作用	关键方法	解决的问题
    可重入	允许线程嵌套获取同一把锁，避免自我死锁	lock()/unlock()	方法嵌套调用时的自我阻塞问题
    可打断	等待锁的线程可被中断，避免无限等待	lockInterruptibly()	线程无需继续等待时的 “及时退出”
    超时	获取锁超时后放弃，避免死等	tryLock(timeout)	锁长期被占用时的 “容错处理”
    这些特性让 ReentrantLock 在复杂场景（如需要中断等待、超时控制、公平性）中比 synchronized 更灵活，但也需要手动释放锁（finally 中调用 unlock()），否则容易出问题。实际使用时，根据场景选择即可。*/
}
