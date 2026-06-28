// 一、CountDownLatch：等待多线程 “集合” 的工具
// 1. 先看生活例子：什么时候需要 CountDownLatch？
// 场景 1：公司团建集合
// 导游（主线程）需要等所有员工（子线程）都到齐（完成各自的准备工作），才能发车（继续执行）；
// 员工到齐一个，计数器减 1，直到计数器为 0，导游发车。
// 场景 2：考试交卷
// 监考老师（主线程）要等所有考生（子线程）都交卷（完成考试），才能收卷（继续执行）；
// 每个考生交卷，计数器减 1，全部交卷后（计数器 0），老师收卷。
// 场景 3：多线程下载文件
// 主线程要等 3 个下载线程分别下载完 “文件片段 1、2、3”，才能合并成完整文件；
// 每个线程下载完，计数器减 1，3 个都完成后，主线程执行合并逻辑。
// 核心痛点：主线程需要等待多个子线程完成特定任务后，才能继续往下走—— CountDownLatch 就是专门解决这个 “等待集合” 问题的。
// 2. 核心定义
// CountDownLatch（翻译：“倒计时门闩”）：
// 本质是一个「不可重置的计数器 + 等待 / 唤醒机制」；
// 初始化时指定计数器初始值（比如 “3 个员工” 对应初始值 3）；
// 子线程完成任务后调用 countDown() 方法，计数器减 1；
// 主线程调用 await() 方法，会阻塞直到计数器变为 0，之后继续执行。
// 关键特性：计数器不可重置—— 一旦计数器减到 0，再调用 countDown() 也不会改变状态，await() 会直接返回（比如 “员工都到齐了，再有人来也不会重新等”）。





//  核心方法（3 个关键方法，简单到不用记）
// 方法名	作用	调用方	注意点
// CountDownLatch(int count)	构造方法：初始化计数器值	主线程	count 必须≥0，否则抛 IllegalArgumentException
// void countDown()	计数器减 1（若减到 0，唤醒所有等待线程）	子线程（任务完成后）	即使 count 已经 0，调用也不会报错，无效果
// void await()	阻塞当前线程，直到计数器为 0	主线程（或需等待的线程）	可被中断，中断后抛 InterruptedException
// boolean await(long timeout, TimeUnit unit)	阻塞指定时间，超时后不管计数器是否为0 都返回	主线程	返回值：计数器为 0 返回 true，超时返回 false

// // 1. 主线程初始化：3个任务，计数器=3
// CountDownLatch latch = new CountDownLatch(3);

// // 2. 子线程1完成任务后
// latch.countDown(); // 计数器=2

// // 3. 子线程2完成任务后
// latch.countDown(); // 计数器=1

// // 4. 子线程3完成任务后
// latch.countDown(); // 计数器=0，唤醒所有等待的线程

// // 5. 主线程阻塞等待
// latch.await(); // 计数器=0，直接继续执行（若没到0，阻塞到0）
// 4. 底层原理（基于 AQS 共享锁，通俗讲）
// CountDownLatch 底层依赖 Java 并发的核心框架 AQS（AbstractQueuedSynchronizer），核心逻辑用 “大白话” 讲：
// AQS 里有个 state 变量，直接作为 CountDownLatch 的「计数器」；
// 构造方法 CountDownLatch(3) → AQS 的 state=3；
// 子线程调用 countDown() → AQS 的 state 减 1（state--），如果 state 变成 0，就遍历 AQS 的等待队列，唤醒所有阻塞的线程；
// 主线程调用 await() → 检查 AQS 的 state 是否为 0：
// 是：直接返回；
// 否：把主线程包装成「共享模式」的节点，加入 AQS 等待队列，然后阻塞线程；
// 当 state 减到 0，唤醒等待队列里的所有线程，主线程继续执行。
// 关键细节：AQS 的「共享模式」—— 多个线程可以同时等待（比如不止一个主线程要等子线程完成），唤醒时会批量唤醒所有等待线程（效率高）。






// 5. 实战案例：多线程下载文件，主线程合并
// 需求：3 个线程分别下载 3 个文件片段，全部下载完成后，主线程合并成完整文件。
// 步骤 1：编写下载线程（子线程）
// java
// 运行
// // 下载线程：模拟下载文件片段
// class DownloadThread extends Thread {
//     private String fragmentName; // 文件片段名（如“片段1”）
//     private CountDownLatch latch; // 共享的CountDownLatch

//     public DownloadThread(String fragmentName, CountDownLatch latch) {
//         this.fragmentName = fragmentName;
//         this.latch = latch;
//     }

//     @Override
//     public void run() {
//         try {
//             System.out.println(Thread.currentThread().getName() + " 开始下载 " + fragmentName);
//             // 模拟下载耗时（1-3秒随机）
//             Thread.sleep(new Random().nextInt(2000) + 1000);
//             System.out.println(Thread.currentThread().getName() + " 下载完成 " + fragmentName);
//         } catch (InterruptedException e) {
//             Thread.currentThread().interrupt();
//         } finally {
//             // 关键：任务完成后，计数器减1（必须放在finally，防止异常导致没减）
//             latch.countDown();
//         }
//     }
// }
// 步骤 2：主线程初始化 + 等待 + 合并
// java
// 运行
// public class CountDownLatchDemo {
//     public static void main(String[] args) throws InterruptedException {
//         // 1. 初始化CountDownLatch：3个文件片段，计数器=3
//         CountDownLatch latch = new CountDownLatch(3);

//         // 2. 启动3个下载线程
//         new DownloadThread("文件片段1", latch).start();
//         new DownloadThread("文件片段2", latch).start();
//         new DownloadThread("文件片段3", latch).start();

//         // 3. 主线程阻塞等待：直到3个线程都下载完成（计数器=0）
//         System.out.println("主线程：等待所有文件片段下载完成...");
//         latch.await(); // 这里会阻塞，直到3个countDown()都执行

//         // 4. 计数器=0，主线程继续执行：合并文件
//         System.out.println("主线程：所有片段下载完成，开始合并文件！");
//     }
// }
// 运行结果（顺序可能不同，但合并一定在最后）
// plaintext
// 主线程：等待所有文件片段下载完成...
// Thread-0 开始下载 文件片段1
// Thread-1 开始下载 文件片段2
// Thread-2 开始下载 文件片段3
// Thread-1 下载完成 文件片段2
// Thread-0 下载完成 文件片段1
// Thread-2 下载完成 文件片段3
// 主线程：所有片段下载完成，开始合并文件！
// 关键注意点：
// countDown() 必须放在 finally 里：防止子线程抛出异常，导致计数器没减 1，主线程一直阻塞（死等）；
// 若想避免死等：用 await(long timeout, TimeUnit unit) 加超时时间，比如 latch.await(5, TimeUnit.SECONDS)，超时后主线程继续执行（或处理超时逻辑）。
// 6. 优缺点
// 优点：
// 简单易用：3 个核心方法，逻辑清晰，不用关心底层并发细节；
// 高效：基于 AQS 共享锁，唤醒线程时批量唤醒，无额外开销；
// 灵活：支持多个线程等待（不止主线程），也支持超时等待，避免死等。
// 缺点：
// 计数器不可重置：一旦减到 0，后续再调用 countDown() 无效，无法重复使用（比如想再等一次 3 个线程完成，必须新建 CountDownLatch）；
// 无异常反馈：主线程只能知道 “所有线程完成” 或 “超时”，无法知道具体哪个子线程出了问题。
// 7. 常见误区
// 误区 1：CountDownLatch 可以重复使用？
// 错！计数器不可重置。比如：
// java
// 运行
// CountDownLatch latch = new CountDownLatch(1);
// latch.countDown(); // 计数器=0
// latch.await(); // 直接返回
// latch.countDown(); // 无效果，计数器还是0
// latch.await(); // 还是直接返回
// 若需重复使用：用 CyclicBarrier（循环屏障），后续可以对比。
// 误区 2：countDown () 必须在子线程调用？
// 错！任何线程都可以调用，包括主线程。比如主线程自己完成一个任务后，也可以调用 countDown()：
// java
// 运行
// CountDownLatch latch = new CountDownLatch(3);
// // 主线程完成一个任务
// latch.countDown(); // 计数器=2
// // 再启动2个子线程，各countDown()一次，最终计数器=0
// 误区 3：await () 会阻塞所有线程？
// 错！await() 只阻塞「调用它的线程」。比如主线程调用 await() 就阻塞主线程，其他子线程正常执行。
// 误区 4：子线程执行完但没调用 countDown ()，主线程会一直等？
// 对！这是常见的 “死等” 问题。解决办法：
// 把 countDown() 放在 finally 里；
// 用 await(long timeout) 加超时时间。
// 8. 与 CyclicBarrier 的区别（避免混淆）
// 很多人会把 CountDownLatch 和 CyclicBarrier 搞混，用表格清晰区分：
// 特性	CountDownLatch	CyclicBarrier
// 核心作用	主线程等待多子线程完成（“一对多”）	多线程互相等待，直到都到达屏障（“多对多”）
// 计数器是否可重置	不可重置（一次性）	可重置（循环使用）
// 等待的线程角色	等待线程（如主线程）和工作线程（如子线程）分离	所有线程都是工作线程，互相等待
// 触发条件	计数器减到 0	所有线程都调用 await()
// 典型场景	主线程等待子线程完成后合并结果	多线程分工协作，比如分阶段执行任务（先完成阶段 1，再一起执行阶段 2）
// 示例（CyclicBarrier 场景）：3 个运动员先跑到起点（屏障 1），再一起起跑；跑到终点（屏障 2），再一起颁奖 —— 可以重复使用屏障。