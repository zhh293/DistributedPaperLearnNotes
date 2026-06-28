package com.zhh.handsome.demo1;

public class interrupt {
    public static void main(String[] args) {
       /* 在 Java 中，interrupt()是Thread类的实例方法，核心作用是给目标线程发送一个 “中断信号”，用于请求线程停止当前操作（但并非强制终止）。线程可以根据这个信号自行决定是否响应中断（如停止执行、释放资源等）。它是一种 “协作式” 的线程中断机制，而非 “强制式” 终止。
        一、interrupt()的核心作用
        interrupt()不会直接终止线程，而是通过以下方式与目标线程 “协作”：

        设置中断标志：给目标线程的 “中断状态”（一个boolean变量）设为true。
        唤醒阻塞线程：若目标线程正处于阻塞状态（如调用了sleep()、wait()、join()等方法），interrupt()会让这些方法抛出InterruptedException，使线程从阻塞中醒来。

        简言之：interrupt()是 “请求线程中断” 的信号，线程如何响应由其自身逻辑决定（可以忽略，也可以优雅退出）。
        二、与中断相关的 3 个核心方法
        Thread类提供了 3 个与中断相关的方法，需重点区分：

        方法	作用	细节
        void interrupt()	给目标线程发送中断信号	设置中断标志为true；唤醒阻塞线程
        boolean isInterrupted()	检查目标线程的中断状态	实例方法，不清除中断标志
        static boolean interrupted()	检查当前线程的中断状态	静态方法，会清除中断标志（设为false）
        关键区别示例：
        java
                运行
        Thread t = new Thread(() -> {
            // 模拟线程运行
            while (true) {
                // 检查中断状态（不清除标志）
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("检测到中断（isInterrupted），标志：" + Thread.currentThread().isInterrupted());
                    break;
                }
            }
        });
        t.start();
        t.interrupt(); // 发送中断信号，标志设为true

// 主线程检查t的中断状态
        System.out.println("t的中断状态（外部）：" + t.isInterrupted()); // true

        若使用interrupted()：

        java
                运行
        Thread t = new Thread(() -> {
            if (Thread.interrupted()) { // 检查并清除标志
                System.out.println("第一次检测（interrupted）：" + Thread.interrupted()); // false（已清除）
            }
        });
        t.start();
        t.interrupt(); // 标志设为true
        三、interrupt()在不同场景下的效果
        interrupt()的行为取决于目标线程的状态，主要分为以下两种情况：
        1. 目标线程处于阻塞状态（如sleep()、wait()、join()时）
        当线程因调用sleep()、Object.wait()、Thread.join()等方法进入阻塞状态时，interrupt()会：

        立即唤醒线程，使其退出阻塞状态。
        抛出InterruptedException异常（由阻塞方法抛出）。
        清除中断标志（将中断状态设为false）。

        示例：中断sleep中的线程

                java
        运行
        Thread t = new Thread(() -> {
            try {
                System.out.println("线程开始休眠10秒...");
                Thread.sleep(10000); // 进入阻塞状态
            } catch (InterruptedException e) {
                // 被interrupt()唤醒，抛出异常
                System.out.println("休眠被中断！");
                // 此时中断标志已被清除
                System.out.println("中断标志：" + Thread.currentThread().isInterrupted()); // false
            }
        });
        t.start();
// 1秒后中断线程
        Thread.sleep(1000);
        t.interrupt();

        输出：

        plaintext
        线程开始休眠10秒...
        休眠被中断！
        中断标志：false
        2. 目标线程处于运行状态（未阻塞）
        当线程正在正常运行（未调用阻塞方法）时，interrupt()只会：

        设置中断标志为true（不会直接影响线程执行）。
        线程需主动检查中断标志（通过isInterrupted()或interrupted()），并自行决定是否响应（如退出循环、释放资源）。

        示例：中断运行中的线程

                java
        运行
        Thread t = new Thread(() -> {
            int count = 0;
            // 循环执行，定期检查中断标志
            while (!Thread.currentThread().isInterrupted()) {
                System.out.println("运行中，计数：" + count++);
                try {
                    Thread.sleep(500); // 短暂休眠（模拟工作）
                } catch (InterruptedException e) {
                    // 若休眠中被中断，此处会捕获异常
                    System.out.println("运行中被中断！");
                    // 重新设置中断标志（因为异常会清除标志）
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("线程响应中断，退出执行");
        });
        t.start();
// 3秒后中断线程
        Thread.sleep(3000);
        t.interrupt();

        输出：

        plaintext
        运行中，计数：0
        运行中，计数：1
        运行中，计数：2
        运行中，计数：3
        运行中，计数：4
        运行中，计数：5
        运行中被中断！
        线程响应中断，退出执行

        关键：运行中的线程必须主动检查中断标志，否则interrupt()会被忽略（线程继续执行）。
        四、使用场景：优雅终止线程
        interrupt()的典型用途是请求线程优雅退出，而非强制终止（强制终止如stop()方法已被废弃，可能导致资源泄漏）。

        优雅退出的流程通常是：

        外部调用thread.interrupt()发送中断信号。
        线程内部定期检查中断标志（isInterrupted()）。
        若检测到中断，执行清理操作（释放锁、关闭连接等），然后退出。
        五、关键细节与注意事项
        1. 中断标志的 “清除” 问题
        当线程因InterruptedException退出阻塞时，中断标志会被自动清除（设为false）。若需要让线程继续响应中断，需在catch块中重新设置标志：Thread.currentThread().interrupt()。
        Thread.interrupted()方法会主动清除中断标志，使用时需注意（通常用于单次检查）。
        2. 某些操作不响应中断
        interrupt()无法中断以下状态的线程：

        正在执行synchronized代码块（等待锁时不会响应中断）。
        正在执行java.util.concurrent.locks.Lock的lock()方法（需使用lockInterruptibly()才能响应中断）。
        3. 已终止的线程中断无效
        若线程已执行完毕（run()方法退出），调用interrupt()不会有任何效果（中断标志始终为false）。
        4. 避免 “吞噬” 中断
        捕获InterruptedException后，若不处理（如仅打印日志而不退出或重设标志），会导致中断信号被 “吞噬”，上层代码无法感知中断。正确做法是：

        退出线程（如break循环）。
        重设中断标志（Thread.currentThread().interrupt()），让上层处理。

        反例（错误做法）：

        java
                运行
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace(); // 仅打印日志，未处理中断
        }
// 后续代码无法感知中断，继续执行*/
    }
}
