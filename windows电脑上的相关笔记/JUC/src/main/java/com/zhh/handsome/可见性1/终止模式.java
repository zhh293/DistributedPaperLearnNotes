package com.zhh.handsome.可见性1;

public class 终止模式 {

    //两阶段终止




    /*两阶段终止（Two-Phase Termination）是一种协作式线程终止模式，核心目的是：让一个线程在收到终止请求后，先完成必要的清理工作（如释放资源、保存状态），再安全退出，避免强制终止导致的资源泄漏或数据不一致。
    为什么需要两阶段终止？
    直接强制终止线程（如调用 Thread.stop()）是极其危险的：
    线程可能在执行临界区代码（如持有锁时）被终止，导致锁永远无法释放，引发死锁；
    线程可能正在操作文件、网络连接等资源，强制终止会导致资源未关闭（如文件句柄泄漏）；
    线程可能正在更新数据，强制终止会导致数据处于 “半成品” 状态（如部分写入数据库）。
    两阶段终止通过 “先请求终止，再等待线程自行清理退出” 的方式，解决了这些问题。
    两阶段的具体含义
    两阶段终止将线程终止过程拆分为两个明确的阶段：
    第一阶段：发起终止请求（“通知阶段”）
    外部线程（如主线程）通过某种方式（如设置中断标志、发送信号）告知目标线程 “需要终止”。
    第二阶段：执行终止前的清理（“收尾阶段”）
    目标线程检测到终止请求后，不再执行新的任务，而是先完成当前工作的收尾（如释放锁、关闭连接、保存数据），最后主动退出。
    实现方式：两种经典方案
    两阶段终止的核心是 “目标线程能及时感知终止请求”，常见实现有以下两种：
    方案 1：基于 volatile 标志位（适用于非阻塞场景）
    通过一个 volatile 修饰的布尔变量作为 “终止标志”，目标线程定期检查该标志，一旦为 true 则执行清理并退出。
    示例代码：
    java
            运行
    public class TwoPhaseTerminationWithFlag {
        // 终止标志（volatile保证可见性）
        private volatile boolean isTerminated = false;
        // 目标线程
        private Thread workerThread;

        // 启动线程
        public void start() {
            workerThread = new Thread(() -> {
                while (!isTerminated) { // 定期检查终止标志
                    try {
                        // 模拟线程正在执行任务（非阻塞）
                        System.out.println("执行任务...");
                        Thread.sleep(1000); // 假设任务执行间隔1秒
                    } catch (InterruptedException e) {
                        // 此处不处理中断，仅用于唤醒sleep（方案2会用到）
                        Thread.currentThread().interrupt(); // 重新设置中断状态
                    }
                }
                // 第二阶段：执行清理工作
                System.out.println("执行清理：释放资源、保存数据...");
                System.out.println("线程已终止");
            });
            workerThread.start();
        }

        // 发起终止请求（第一阶段）
        public void stop() {
            isTerminated = true;
            // 若线程在sleep，需要中断唤醒（否则可能等1秒后才检查标志）
            workerThread.interrupt();
        }

        public static void main(String[] args) throws InterruptedException {
            TwoPhaseTerminationWithFlag tpt = new TwoPhaseTerminationWithFlag();
            tpt.start();
            // 运行3秒后发起终止
            Thread.sleep(3000);
            tpt.stop();
        }
    }
    优点：实现简单，适用于线程主要在 “非阻塞循环” 中运行的场景（如定期轮询任务）。缺点：若线程处于长时间阻塞状态（如 Thread.sleep(10000)），可能无法及时检测到标志位，导致终止延迟。
    方案 2：基于 Thread.interrupt ()（适用于阻塞场景）
    利用线程的 “中断状态” 作为终止信号。Thread.interrupt() 可以唤醒阻塞中的线程（如 sleep、wait、join 时会抛出 InterruptedException），让线程及时响应终止请求。
    示例代码：
    java
            运行
    public class TwoPhaseTerminationWithInterrupt {
        private Thread workerThread;

        public void start() {
            workerThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) { // 检查中断状态
                    try {
                        // 模拟阻塞操作（如等待消息、IO读取等）
                        System.out.println("等待任务...");
                        Thread.sleep(5000); // 长时间阻塞
                    } catch (InterruptedException e) {
                        // 捕获中断异常：说明收到终止请求
                        Thread.currentThread().interrupt(); // 重新设置中断状态（确保循环退出）
                    }
                }
                // 第二阶段：清理工作
                System.out.println("执行清理：关闭连接、释放锁...");
                System.out.println("线程已终止");
            });
            workerThread.start();
        }

        // 发起终止请求：调用interrupt()
        public void stop() {
            if (workerThread != null) {
                workerThread.interrupt(); // 第一阶段：发送中断信号
            }
        }

        public static void main(String[] args) throws InterruptedException {
            TwoPhaseTerminationWithInterrupt tpt = new TwoPhaseTerminationWithInterrupt();
            tpt.start();
            Thread.sleep(3000); // 运行3秒后终止
            tpt.stop();
        }
    }
    核心逻辑：
    目标线程通过 Thread.currentThread().isInterrupted() 检查中断状态（替代 volatile 标志）；
    当外部调用 interrupt() 时：
    若线程处于运行中，仅设置中断状态，线程下一次检查时会退出；
    若线程处于阻塞（如 sleep），会抛出 InterruptedException，线程可在异常中捕获终止请求，立即响应。
    优点：能及时唤醒阻塞中的线程，终止响应更快，适用于有阻塞操作的场景。注意：捕获 InterruptedException 后，中断状态会被清除，需通过 Thread.currentThread().interrupt() 重新设置，否则循环可能无法退出。
    两阶段终止的关键注意事项
    清理工作必须线程安全：清理阶段（如释放锁、关闭资源）需确保原子性，避免被再次中断导致清理不完整。
    避免 “终止风暴”：若多个线程依赖同一资源，终止时需按顺序清理（如先终止子线程，再终止主线程），避免资源竞争。
    禁止使用 stop ()/suspend ()：这些方法已被 JDK 标记为过时，会强制终止线程，破坏两阶段终止的安全性。
    确保所有阻塞点都能响应终止：线程若有多个阻塞操作（如 sleep、wait、BlockingQueue.take()），需在每个阻塞点都处理中断，避免漏检。
    总结
    两阶段终止的核心思想是 **“协作式终止”**：通过 “通知 - 清理 - 退出” 的流程，让线程在可控的状态下安全终止。
    非阻塞场景：用 volatile 标志位简单实现；
    阻塞场景：用 Thread.interrupt() 响应更及时。*/


}
