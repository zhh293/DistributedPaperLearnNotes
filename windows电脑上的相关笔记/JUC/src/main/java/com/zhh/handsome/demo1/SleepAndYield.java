package com.zhh.handsome.demo1;

public class SleepAndYield {
    public static void main(String[] args) {
       /* 一、sleep () 方法
        1. 作用
        sleep(long millis)的核心作用是让当前线程暂停执行指定的时间（毫秒级），期间线程进入阻塞状态（Blocked），暂时让出 CPU 资源。时间结束后，线程会从阻塞状态转为就绪状态（Runnable），等待 CPU 调度再次执行。
        2. 用法
        方法定义：public static native void sleep(long millis) throws InterruptedException
（还有一个重载方法sleep(long millis, int nanos)，支持纳秒级精度，但实际精度受系统时钟限制）
        必须处理异常：sleep()会抛出InterruptedException（当其他线程调用当前线程的interrupt()方法时触发），因此必须用try-catch捕获或向上抛出。
        调用方式：通过Thread.sleep(时间)直接调用（静态方法，无需实例化 Thread）。

        示例代码：

        java
                运行
        public class SleepDemo {
            public static void main(String[] args) {
                new Thread(() -> {
                    for (int i = 0; i < 3; i++) {
                        System.out.println("线程A执行：" + i);
                        try {
                            // 暂停1000毫秒（1秒）
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        }
        3. 关键细节
        不释放锁：如果当前线程持有某个对象的锁（如在synchronized代码块中），sleep()期间不会释放锁，其他线程仍无法访问该对象。
        java
                运行
        synchronized (obj) {
            Thread.sleep(1000); // 期间obj的锁仍被当前线程持有
        }

        时间准确性：指定的睡眠时间是 “最小暂停时间”，线程不一定会在时间结束后立即执行（需等待 CPU 调度），但至少会暂停指定时间。
        阻塞状态：sleep()期间线程处于阻塞状态，不参与 CPU 调度，直到时间结束后才进入就绪状态。
        跨优先级：无论其他线程优先级高低，sleep()都会让当前线程暂停，时间结束后重新参与调度。



        二、yield () 方法
        1. 作用
        yield()的核心作用是让当前线程主动放弃 CPU 执行权，从运行状态（Running）转为就绪状态（Runnable），给其他同优先级或更高优先级的线程提供执行机会。
        2. 用法
        方法定义：public static native void yield()
        无异常：yield()不会抛出任何异常，调用更简单。
        调用方式：通过Thread.yield()直接调用（静态方法）。

        示例代码：

        java
                运行
        public class YieldDemo {
            public static void main(String[] args) {
                Thread t1 = new Thread(() -> {
                    for (int i = 0; i < 5; i++) {
                        System.out.println("线程A执行：" + i);
                        Thread.yield(); // 主动让出CPU
                    }
                }, "线程A");

                Thread t2 = new Thread(() -> {
                    for (int i = 0; i < 5; i++) {
                        System.out.println("线程B执行：" + i);
                    }
                }, "线程B");

                t1.start();
                t2.start();
            }
        }

        执行结果可能交替打印 “线程 A” 和 “线程 B”（取决于 CPU 调度）。
        3. 关键细节
        不释放锁：与sleep()类似，yield()不会释放当前线程持有的锁。
        调度建议：yield()是对 CPU 调度器的 “建议”，而非强制要求。调度器可能忽略该建议（例如，当前没有同优先级线程等待执行时，当前线程可能立即重新获得 CPU）。
        同优先级优先：yield()主要让给同优先级线程执行，低优先级线程通常无法获得机会。
        无时间参数：yield()不会暂停线程，只是让出当前的 CPU 时间片，线程立即进入就绪状态，可能马上再次被调度执行。
        性能影响：频繁调用yield()可能导致线程切换开销增加，降低程序效率，一般用于调试或特定的并发控制场景。
        三、sleep () 与 yield () 的核心区别
        维度	sleep()	yield()
        线程状态	阻塞状态（Blocked）	就绪状态（Runnable）
        暂停时间	明确指定时间（至少暂停该时间）	无时间参数（立即进入就绪状态）
        锁释放	不释放持有的锁	不释放持有的锁
        调度影响	让给所有优先级线程（包括低优先级）	主要让给同优先级或更高优先级线程
        执行确定性	时间到后一定进入就绪状态（确定性高）	调度器可能忽略，不确定性高
        异常处理	必须处理 InterruptedException	无异常
        四、使用场景总结
        sleep()：适用于需要明确暂停一段时间的场景（如定时任务、模拟延迟）。例如：线程执行后暂停 1 秒再继续，或控制并发节奏。
        yield()：适用于让同优先级线程更均匀地分配 CPU 资源的场景（如协作式多任务）。例如：在循环中偶尔调用yield()，避免单个线程长期占用 CPU。*/




        /*案例 - 防止 CPU 占用 100%

                sleep 实现

        在没有利用 cpu 来计算时，不要让 while (true) 空转浪费 cpu，这时可以使用 yield 或 sleep 来让出 cpu 的使用权给其他程序

                java
        while(true) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


        可以用 wait 或条件变量达到类似的效果
        不同的是，后两种都需要加锁，并且需要相应的唤醒操作，一般适用于要进行同步的场景
        sleep 适用于无需做同步的场景*/





        //join方法
        /*为什么需要join
        *
        * 在 Java 中，join()是Thread类的实例方法，核心作用是让当前线程（调用者线程）阻塞，等待目标线程（被调用join()的线程）执行完毕后再继续运行。它是线程同步的重要工具，常用来控制线程执行顺序。以下从作用、用法、底层原理、细节等维度详细解析：
一、join()的核心作用
当线程 A 中调用 threadB.join() 时，线程 A 会进入阻塞状态（WAITING/TIMED_WAITING），直到线程 B 的run()方法执行完毕，线程 A 才会从join()处继续向下执行。

简言之：让调用者线程 “等待” 目标线程完成，实现线程间的串行执行。
二、方法重载与用法
Thread类提供了 3 个版本的join()方法：
1. 无参版：join()
作用：无限等待，直到目标线程执行完毕。
定义：
java
public final synchronized void join() throws InterruptedException

必须处理异常：调用时需捕获InterruptedException（当线程在等待期间被中断时抛出）。
2. 带毫秒超时：join(long millis)
作用：最多等待millis毫秒。若目标线程在超时前执行完毕，调用者线程立即继续；若超时后仍未完成，调用者线程也会停止等待。
定义：
java
public final synchronized void join(long millis) throws InterruptedException

3. 带纳秒超时：join(long millis, int nanos)
作用：更精确的超时控制（支持纳秒级），逻辑与join(long millis)一致。
定义：
java
public final synchronized void join(long millis, int nanos) throws InterruptedException

三、底层实现原理（关键！）
join()的核心机制依赖 **synchronized锁 + wait()/notifyAll()协作 **，步骤如下：

锁的获取：
join()是synchronized方法，调用threadB.join()时，当前线程（如线程 A）会先获取threadB对象的内置锁（monitor）。
循环等待：
若threadB仍在运行（isAlive()返回true），线程 A 会调用threadB.wait()，主动释放threadB的锁，并进入threadB对象的等待集（Wait Set），状态变为WAITING（无参版）或TIMED_WAITING（带超时版）。
自动唤醒：
当threadB的run()方法执行完毕，JVM 内部会自动调用threadB.notifyAll()，唤醒所有等待threadB锁的线程（包括因join()而阻塞的线程 A）。
锁的重入与返回：
线程 A 被唤醒后，重新竞争并获取threadB的锁，从wait()方法返回，最终退出join()的循环，继续执行后续代码。
        *
        *
        *
        *
        *
        *
        * */










    }
}
