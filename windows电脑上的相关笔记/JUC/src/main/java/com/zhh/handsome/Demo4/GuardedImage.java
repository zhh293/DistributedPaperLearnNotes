package com.zhh.handsome.Demo4;

public class GuardedImage {
    private Object image;
    private volatile boolean isReady = false;
    public synchronized Object getImage(long timeout) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;

        while (!isReady) {
//            long waitTime = timeout - elapsedTime;
            if (timeout<=elapsedTime) {
                throw new InterruptedException("等待图片下载超时");
            }
            /*在这个同步方法中，wait(timeout - elapsedTime) 需要减去流逝时间，而不能直接写 wait(timeout)，核心原因是为了保证总等待时间不超过用户指定的 timeout 毫秒，尤其是在发生「虚假唤醒」或被提前唤醒的场景下。
            具体分析：
            wait(long timeout) 方法的作用是让当前线程进入等待状态，直到以下两种情况之一发生：
            其他线程调用了 notify() 或 notifyAll() 唤醒它；
            等待时间达到了 timeout 毫秒。
            但实际场景中，线程可能会被「虚假唤醒」（即没有被其他线程调用 notify，但线程却意外醒来），或者在未满足 isReady 条件时被提前唤醒。这时候需要通过循环 while (!isReady) 重新检查条件，如果条件仍不满足，就需要继续等待。
            为什么不能直接用 wait(timeout)？
            如果每次循环都直接写 wait(timeout)，会导致总等待时间可能超过预期的 timeout。
            举个例子：
            假设用户指定 timeout = 1000ms（1 秒）。
            第一次进入循环，线程调用 wait(1000)，但 500ms 后因为虚假唤醒提前醒来，此时 isReady 仍为 false。
            如果再次调用 wait(1000)，线程会再等 1000ms，总等待时间就变成了 1500ms，超过了用户预期的 1000ms。
            为什么要减流逝时间？
            elapsedTime 记录了从开始等待到现在已经花费的时间。用 timeout - elapsedTime 计算「剩余等待时间」，可以保证：
            即使发生多次唤醒，每次等待的都是「剩余时间」，累计总等待时间不会超过最初的 timeout。
            同样以上面的例子：
            第一次等待 500ms 后被唤醒，elapsedTime = 500ms。
            剩余时间为 1000 - 500 = 500ms，再次调用 wait(500)。
            即使这次又等满 500ms，总时间也刚好是 1000ms，符合用户预期的超时时间。
            总结：
            wait(timeout - elapsedTime) 的写法是为了在处理虚假唤醒或提前唤醒时，严格保证总等待时间不超过用户指定的 timeout，是多线程编程中处理超时等待的标准写法。如果直接使用 wait(timeout)，会导致超时时间被「重置」，可能出现实际等待时间远超预期的问题。*/
            this.wait(timeout - elapsedTime);
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        return image;
    }
    public synchronized void setImage(Object image) {
        this.image = image;
        this.isReady = true;
        this.notifyAll();
    }
}
/*<select id="selectByIds" resultType="YourResultType">
    SELECT * FROM your_table
    WHERE id IN
<foreach collection="list" item="item" index="index" open="(" separator="," close=")">
    #{item}
</foreach>

</select>*/

//绷不住了，mybatis还是太好看了，虽然jdbc非常轻量而且灵活
//             notifyAll();



//单看这两个肯定没有虚假唤醒啊。但问题是，如果出现了第三个线程抢cpu，用完唤醒wait，wait和complete抢cpu，如果wait抢到了，complete没抢到，就出现了虚假唤醒了。





/*


在多线程编程中，虚假唤醒（Spurious Wakeup） 指的是线程从 wait() 状态中被唤醒，但并非由于其他线程调用 notify() 或 notifyAll() 导致的唤醒。简单来说，就是线程 “无缘无故” 地醒了。
为什么会出现虚假唤醒？
虚假唤醒的根源与操作系统的线程调度机制和底层硬件特性有关：
操作系统层面的实现限制多数操作系统（如 Linux、Windows）的线程调度器在实现时，可能会因为内部信号、中断、资源竞争等原因，导致线程从等待状态中被 “意外” 唤醒。这种唤醒并非由应用程序主动调用 notify 触发，而是操作系统层面的一种 “边缘情况”。
例如，在多核 CPU 中，线程调度可能存在细微的同步误差；或者当系统资源（如锁）被释放时，调度器可能会 “批量唤醒” 多个等待线程，即使其中一些线程并非必要唤醒。
Java 规范的允许Java 语言规范（JLS）明确允许虚假唤醒的存在。这是因为：
禁止虚假唤醒会让操作系统和 JVM 的实现变得复杂且低效（需要额外的检查机制）。
从设计上，Java 更倾向于将处理虚假唤醒的责任交给开发者（通过循环检查条件），而非让底层实现承担额外开销。
并非 “完全随机”虚假唤醒的发生概率通常很低，但无法完全避免。它不是 “bug”，而是多线程环境中一种允许存在的正常现象。
如何应对虚假唤醒？
正因为虚假唤醒可能发生，Java 文档强制要求：调用 wait() 时必须配合循环（while）检查等待条件，而不能用 if 语句。
例如，正确的写法是：
java
        运行
synchronized (obj) {
        // 用while循环，而非if
        while (!condition) {  // 条件不满足时继续等待
        obj.wait(timeout);
    }
            // 条件满足后执行逻辑
            }
这样即使发生虚假唤醒，线程醒来后会再次检查条件：如果条件仍不满足，就继续等待，避免错误执行后续逻辑。


*/
