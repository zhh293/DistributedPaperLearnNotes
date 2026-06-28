package com.zhh.handsome.Demo3;

public class waitBssleep {


    /*一、最核心的区别：是否释放锁
    这是 wait() 和 sleep() 最本质的差异，直接决定了它们的使用场景：
    方法	是否释放持有的锁？	底层原因
    wait()	✅ 是（必须释放）	wait() 依赖 ObjectMonitor（监视器锁），调用时会主动将 _owner 设为 null（释放锁），让其他线程有机会竞争锁（对应之前讲的 wait() 步骤 4）。
    sleep()	❌ 否（绝对不释放）	sleep() 是 Thread 类的 “纯暂停” 方法，仅让线程放弃 CPU 时间片，不涉及锁的操作 —— 即使线程持有 synchronized 锁，sleep 期间锁仍归该线程所有。
    二、所属类与调用前提
    两者的 “归属” 和 “调用条件” 差异很大，直接关联到底层依赖的机制：
    维度	wait()	sleep()
    所属类	java.lang.Object 类（所有对象都有）	java.lang.Thread 类（静态方法）
    调用前提	必须在 synchronized 同步块 / 方法中调用	无强制前提（可在任何代码块中调用）
    违规后果	未持有锁时调用 → 抛 IllegalMonitorStateException	无违规后果（任意场景都能调用）
    底层依赖	依赖对象关联的 ObjectMonitor（锁实体）	依赖操作系统的 “定时器”（如 Linux 的 nanosleep）
    例子：直观感受调用前提
            java
    运行
    // 1. wait() 必须在 synchronized 中，否则报错
    Object obj = new Object();
// obj.wait(); // 直接调用 → 抛 IllegalMonitorStateException

    synchronized (obj) {
        obj.wait(); // 正确：持有 obj 的锁，可调用 wait()
    }

// 2. sleep() 无前提，直接调用
Thread.sleep(1000); // 正确：无需持有任何锁
    三、唤醒机制
    两者的 “唤醒方式” 完全不同，决定了线程如何恢复执行：
    方法	唤醒方式	细节说明
    wait()	1. 其他线程调用 对象.notify()/notifyAll()；
            2. 其他线程调用 线程.interrupt()；
            3. 超时自动唤醒（若传了超时参数，如 wait(1000)）	被唤醒后不会直接执行，需重新加入锁竞争队列（_EntryList），竞争到锁后才继续执行（对应 wait() 步骤 6）。
    sleep()	1. 睡眠时间到后自动唤醒；
            2. 其他线程调用 线程.interrupt() 强制唤醒	被唤醒后（无论时间到还是被中断），直接从暂停处继续执行（无需竞争锁，因为 sleep 期间没释放锁）。
    例子：唤醒后的差异
            java
    运行
    Object lock = new Object();
    Thread t1 = new Thread(() -> {
        synchronized (lock) {
            try {
                lock.wait(1000); // 唤醒后需重新竞争 lock 锁
                System.out.println("t1 继续执行"); // 只有竞争到锁才会打印
            } catch (InterruptedException e) {}
        }
    });

    Thread t2 = new Thread(() -> {
        try {
            Thread.sleep(1000); // 唤醒后直接执行
            System.out.println("t2 继续执行"); // 时间到后一定打印（无锁竞争）
        } catch (InterruptedException e) {}
    });
    四、线程状态变化
    调用两种方法后，线程在 JVM 中的状态（Thread.State）不同，可通过 thread.getState() 观察：
    方法	线程状态	说明
    wait()（无超时）	WAITING（无限期等待）	直到被 notify / 中断才会退出该状态。
    wait(long timeout)	TIMED_WAITING（限期等待）	超时 / 被 notify / 中断 会退出该状态。
    sleep(long millis)	TIMED_WAITING（限期等待）	超时 / 被中断 会退出该状态（无 WAITING 状态，因为 sleep 必须传时间）。
    注意：
    WAITING 和 TIMED_WAITING 都是 “阻塞状态”，但前者是 “无限期”，后者是 “限期”；
    两种状态下，线程都不占用 CPU 资源，仅等待唤醒。
    五、使用场景
    设计目的的不同，导致两者的应用场景完全隔离：
    方法	核心用途	典型场景
    wait()	线程间通信（等待 / 通知机制）	生产者 - 消费者模型：
            - 消费者调用 queue.wait() 等待 “队列有数据”；
            - 生产者生产数据后调用 queue.notify() 唤醒消费者。
    sleep()	暂停线程（延迟执行）	1. 模拟业务延迟（如接口调用后等待 1s 重试）；
            2. 控制线程执行节奏（如循环打印时，每次间隔 500ms）。
    经典场景对比：
    java
            运行
    // 场景1：生产者-消费者（用 wait() 实现线程通信）
    Queue<Integer> queue = new LinkedList<>();
    int MAX_SIZE = 5;

    // 生产者
    Thread producer = new Thread(() -> {
        synchronized (queue) {
            while (queue.size() == MAX_SIZE) {
                try {
                    queue.wait(); // 队列满，等待消费者消费
                } catch (InterruptedException e) {}
            }
            queue.add(1); // 生产数据
            queue.notify(); // 唤醒消费者
        }
    });

    // 消费者
    Thread consumer = new Thread(() -> {
        synchronized (queue) {
            while (queue.isEmpty()) {
                try {
                    queue.wait(); // 队列空，等待生产者生产
                } catch (InterruptedException e) {}
            }
            queue.poll(); // 消费数据
            queue.notify(); // 唤醒生产者
        }
    });

    // 场景2：模拟延迟（用 sleep() 实现）
    Thread delayThread = new Thread(() -> {
        System.out.println("开始请求接口...");
        try {
            Thread.sleep(2000); // 模拟接口调用延迟 2s
        } catch (InterruptedException e) {}
        System.out.println("接口返回结果");
    });
    六、异常处理
    两者被中断（interrupt()）时，都会抛出 InterruptedException，但后续处理逻辑有差异：
    方法	中断后的处理
    wait()	1. 抛 InterruptedException；
            2. 清除线程的 “中断状态”（Thread.interrupted() 会返回 false）；
            3. 线程从 _WaitSet 移出，进入锁竞争队列。
    sleep()	1. 抛 InterruptedException；
            2. 清除线程的 “中断状态”；
            3. 线程直接从暂停处继续执行（无锁竞争）。
    例子：中断后的状态清除
            java
    运行
    Thread t = new Thread(() -> {
        try {
            Thread.sleep(1000); // 或 lock.wait(1000)
        } catch (InterruptedException e) {
            System.out.println(Thread.currentThread().isInterrupted()); // 输出 false：中断状态被清除
        }
    });
t.start();
t.interrupt(); // 中断线程*/



//    wait() 是 “为了释放锁而暂停”，用于线程间协作；sleep() 是 “单纯为了暂停而暂停”，不涉及锁操作。


}
