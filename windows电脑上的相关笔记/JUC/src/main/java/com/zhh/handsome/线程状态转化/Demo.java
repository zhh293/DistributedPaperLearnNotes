package com.zhh.handsome.线程状态转化;

import java.util.concurrent.TimeUnit;

public class Demo {


    public static void main(String[] args) {
        new Thread(){
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getState());
                System.out.println("start");
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("end");
            }
        }.start();
        System.out.println(Thread.currentThread().getState());
    }


  /*  在 Java 中，线程从创建到销毁会经历一系列状态变化，
    这些状态被定义在Thread.State枚举中，共分为 6 种
    。理解线程状态的转化，是掌握并发编程的基础。
    我们可以用 “人生阶段” 来类比，帮你更直观地理解：

    一、6 种核心状态
    先明确每种状态的含义（结合生活场景）：
    状态名称	含义（类比）	关键特点
    New（新建）	线程对象刚被创建，但还没 “启动”（像刚买的手机，没开机）	只存在于创建后、start()调用前
    Runnable（运行）	线程已启动，正在 “就绪” 或 “运行中”（像开机后的手机，要么正在用，要么在后台待命）	包含 “就绪”（等 CPU 调度）和 “运行”（正在用 CPU）两种情况
    Blocked（阻塞）	因竞争锁失败而 “排队等锁”（像想进会议室但门被锁了，只能在门口等）	仅与synchronized锁竞争相关
    Waiting（等待）	无限期等待被唤醒（像人睡着了，没外界叫醒就不会醒）	需要其他线程显式唤醒（如notify()、unpark()）
    Timed Waiting（超时等待）	有限期等待（像定了闹钟睡觉，时间到了会自己醒）	超时后自动唤醒，也可被提前唤醒
    Terminated（终止）	线程执行完毕或异常结束（像手机用完关机了）	一旦进入，状态不再变化
    二、状态转化全路径（附场景例子）
    线程状态的转化有明确的 “规则”，我们按 “从生到死” 的顺序梳理：
            1. 新建（New）→ 运行（Runnable）
    触发条件：调用线程的start()方法。
    例子：
    java
            运行
    Thread t = new Thread(); // t处于New状态
t.start(); // 调用后，t进入Runnable状态
    原理：start()会告诉 JVM “启动这个线程”，JVM 会为线程分配资源，然后把它加入 “就绪队列”，等待 CPU 调度。
            2. 运行（Runnable）→ 阻塞（Blocked）
    触发条件：线程竞争synchronized锁失败时。
    例子：
    两个线程竞争同一把锁，线程 A 先拿到锁，线程 B 会进入 Blocked 状态，在锁的 “入口队列（EntryList）” 中排队。
    原理：synchronized的底层是monitor（管程），未抢到锁的线程会被放入EntryList，进入 Blocked 状态，不参与 CPU 调度。
            3. 阻塞（Blocked）→ 运行（Runnable）
    触发条件：线程竞争到synchronized锁时。
    例子：
    线程 A 释放锁后，JVM 会从EntryList中选一个线程（比如线程 B）让它拿到锁，线程 B 从 Blocked 转为 Runnable，等待 CPU 调度。
            4. 运行（Runnable）→ 等待（Waiting）
    触发条件：线程调用了无超时的 “等待方法”，常见的有：
            object.wait()（必须在synchronized块中）
            thread.join()（无参版，等待目标线程结束）
            LockSupport.park()（无参版）
    例子：
    java
            运行
    synchronized (obj) {
        obj.wait(); // 当前线程进入Waiting状态，释放锁
    }
    原理：线程会暂时 “放弃执行权”，进入一个 “等待队列”（如wait()会进入对象的WaitSet），直到被其他线程唤醒。
            5. 等待（Waiting）→ 运行（Runnable）
    触发条件：被其他线程显式唤醒，常见的有：
            其他线程调用object.notify()或object.notifyAll()（对应wait()的唤醒）
    被join()等待的线程执行结束（对应join()的唤醒）
            其他线程调用LockSupport.unpark(thread)（对应park()的唤醒）
    例子：
    线程 A 调用obj.wait()进入 Waiting 后，线程 B 调用obj.notify()，线程 A 会从WaitSet移到锁的EntryList，重新竞争锁，拿到锁后转为 Runnable。
            6. 运行（Runnable）→ 超时等待（Timed Waiting）
    触发条件：线程调用了带超时参数的 “等待方法”，常见的有：
            Thread.sleep(long ms)（休眠指定毫秒，不释放锁）
            object.wait(long ms)（释放锁，等待指定时间）
            thread.join(long ms)（最多等指定时间）
            LockSupport.parkNanos(long nanos)或parkUntil(long deadline)
    例子：
    java
            运行
Thread.sleep(1000); // 当前线程进入Timed Waiting，1秒后自动唤醒
    原理：和 Waiting 类似，但线程会设置一个 “唤醒时间”，超时后自动从等待队列中出来，无需其他线程唤醒。
            7. 超时等待（Timed Waiting）→ 运行（Runnable）
    触发条件：两种可能：
    等待时间到（如sleep(1000)到时间后）；
    被其他线程提前唤醒（如wait(1000)还没到时间，被notify()唤醒）。
            8. 运行（Runnable）→ 终止（Terminated）
    触发条件：两种情况：
    线程的run()方法执行完毕（正常结束）；
    线程在运行中抛出未捕获的异常（异常终止）。
    例子：
    线程的run()方法里只有一句System.out.println("完成")，执行完后就进入 Terminated 状态。
    三、关键注意点（避免混淆）
    Runnable 状态的特殊性：Java 中的 “运行” 状态包含两种情况：
    正在 CPU 上执行（真正 “运行中”）；
    已就绪，在等 CPU 调度（“就绪态”）。
    这是因为线程切换由 CPU 调度，JVM 层面不细分这两种情况，统一归为 Runnable。
    Blocked vs Waiting vs Timed Waiting：
    Blocked：只和synchronized锁竞争有关，等 “锁”；
    Waiting：等 “其他线程唤醒”，无限期；
    Timed Waiting：等 “时间到” 或 “被唤醒”，有限期。
    sleep () 和 wait () 的区别：
    sleep(ms)：进入 Timed Waiting，不释放锁，时间到自动唤醒；
    wait(ms)：进入 Timed Waiting（或 Waiting），释放锁，需notify()唤醒或超时唤醒。
    四、状态转化图（文字版）
    plaintext
    New →（start()）→ Runnable
    Runnable →（抢锁失败）→ Blocked →（抢到锁）→ Runnable
    Runnable →（调用无参等待方法）→ Waiting →（被唤醒）→ Runnable
    Runnable →（调用带超时等待方法）→ Timed Waiting →（超时/被唤醒）→ Runnable
    Runnable →（执行完毕/异常）→ Terminated
    理解线程状态转化，能帮你分析并发问题（比如线程为什么卡住、为什么没按预期执行）。比如：如果线程一直处于 Blocked，可能是在等锁；如果一直处于 Waiting，可能是忘了调用notify()唤醒。*/



    /*1. 诊断和解决并发问题
    当程序出现性能瓶颈或死锁时，可以通过分析线程状态来定位问题根源
    如果线程长时间处于 Blocked 状态，说明可能存在锁竞争激烈的问题
    如果线程长时间处于 Waiting 或 Timed Waiting 状态，可能是因为没有正确地被唤醒
2. 优化程序性能
    理解线程状态有助于合理使用同步机制，减少不必要的锁竞争
            可以通过调整线程调度策略和同步方式来提高程序执行效率
    避免过度使用 sleep() 或 wait() 导致线程长时间阻塞
3. 正确使用多线程工具
    根据不同场景选择合适的线程控制方法：
    使用 synchronized 时理解 Blocked 状态的产生原因
    使用 wait()/notify() 机制时清楚 Waiting 状态的转换过程
    合理使用 sleep() 和 join() 等方法控制线程执行顺序
4. 调试和监控应用
            在生产环境中监控线程状态可以帮助发现潜在问题
    使用JVM监控工具（如jstack、jconsole）分析线程状态是排查问题的重要手段
    能够预测和避免线程饥饿、死锁等问题
5. 设计合理的并发架构
            根据业务需求合理设计线程池和任务分配机制
    理解线程生命周期有助于设计更加健壮的并发框架
    能够更好地利用系统资源，避免创建过多线程导致资源耗尽
    掌握线程状态转换就像掌握了多线程编程的"地图"，能够帮助开发者更好地理解和控制程序的行为。*/


}
