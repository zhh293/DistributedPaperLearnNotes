package com.zhh.handsome.JDK提供的线程池实现;

import java.util.concurrent.ThreadPoolExecutor;

public class 普通ThreadPool {


    /*ThreadPoolExecutor超详细清晰易懂的讲解
    你想要一份超详细、清晰易懂的 ThreadPoolExecutor 讲解，核心是理解它的核心原理、参数含义、工作流程和实际使用方式，尤其是能让编程新手也能看懂。下面我会从「为什么用」「核心参数」「工作流程」「代码示例」「最佳实践」几个维度，用生活化的例子把它讲透。
    一、先搞懂：为什么需要 ThreadPoolExecutor？
    ThreadPoolExecutor 是 Java java.util.concurrent 包中线程池的核心实现类，本质是「管理一组可复用线程的容器」。
    用一个生活化的例子理解：
    没有线程池：就像餐厅来了客人就临时招聘服务员（创建线程），客人走了就解雇服务员（销毁线程）—— 招聘 / 解雇（创建 / 销毁线程）的开销大，还容易乱。
    有线程池：餐厅固定雇佣几个「正式服务员」（核心线程），忙的时候再招「临时工」（非核心线程），临时工没事做就限时辞退，客人太多就先让客人在候客区（工作队列）等，实在容不下了就按规则拒绝（拒绝策略）—— 复用线程、控制并发、降低开销。
    线程池的核心价值：
    降低资源消耗：复用已创建的线程，避免频繁创建 / 销毁线程的性能开销（线程创建需要分配栈空间、内核态 / 用户态切换，成本很高）。
    提高响应速度：任务到达时，无需等待线程创建就能立即执行。
    便于管理：可控制最大并发数、设置线程存活时间、监控线程状态，避免线程无限创建导致的内存溢出（OOM）。
    二、核心：ThreadPoolExecutor 的 7 个关键参数
    ThreadPoolExecutor 最核心的是它的构造方法（新手只需掌握这个全参构造），7 个参数决定了线程池的全部行为：
    java
            运行
    public ThreadPoolExecutor(
            int corePoolSize,        // 1. 核心线程数
            int maximumPoolSize,     // 2. 最大线程数
            long keepAliveTime,      // 3. 非核心线程空闲存活时间
            TimeUnit unit,           // 4. 时间单位
            BlockingQueue<Runnable> workQueue,  // 5. 工作队列
            ThreadFactory threadFactory,        // 6. 线程工厂
            RejectedExecutionHandler handler    // 7. 拒绝策略
    )
    逐个拆解（结合餐厅例子）
    参数	通俗解释	餐厅类比
    corePoolSize（核心线程数）	线程池的「常驻线程数」，即使空闲也不会被销毁（除非设置 allowCoreThreadTimeOut=true）	餐厅固定雇佣的「正式服务员」数量（比如 3 个），不管有没有客人，这 3 个都在岗
    maximumPoolSize（最大线程数）	线程池允许的「总线程数上限」（核心线程 + 非核心线程）	餐厅最多能同时有多少服务员（比如 5 个 = 3 正式 + 2 临时）
    keepAliveTime（空闲时间）	非核心线程（临时工）空闲后的「最大存活时间」	临时工没客人服务时，最多待 10 分钟，超时就辞退
    unit（时间单位）	keepAliveTime 的单位（秒 / 分钟等）	上面的「10 分钟」里的「分钟」就是单位
    workQueue（工作队列）	存放「等待执行的任务」的阻塞队列	餐厅的「候客区」，服务员忙不过来时，客人先在候客区等
    threadFactory（线程工厂）	用于创建新线程，可自定义线程名称、优先级、是否为守护线程	餐厅的「人事部」，负责招聘服务员（给服务员命名、定规则）
    handler（拒绝策略）	线程池 + 工作队列都满了时，处理新任务的策略	餐厅满座 + 候客区也满了，对新客人的处理方式（比如「抱歉，没位置了」）
    补充：关键参数的细节
1. 工作队列（workQueue）的常见类型
    队列类型	特点	适用场景
    ArrayBlockingQueue	有界数组队列（容量固定）	可控并发，避免队列无限膨胀，推荐生产使用
    LinkedBlockingQueue	可指定容量（默认无界）	慎用默认无界队列（会导致 maximumPoolSize 失效，任务无限排队，易 OOM）
    SynchronousQueue	同步队列（不存储任务）	任务需快速处理，每个任务提交后必须有线程立即接收（如 newCachedThreadPool）
    PriorityBlockingQueue	优先级队列	需按任务优先级执行的场景
2. 拒绝策略（handler）的 4 种默认实现
    拒绝策略	行为	适用场景
    AbortPolicy（默认）	直接抛出 RejectedExecutionException 异常，阻止程序运行	对任务不能丢失、需要感知异常的场景
    CallerRunsPolicy	由「提交任务的线程」自己执行该任务	非核心任务，希望缓解线程池压力的场景（比如日志打印）
    DiscardPolicy	直接丢弃新任务，不抛异常、不执行	任务可丢失的场景（比如非核心的统计任务）
    DiscardOldestPolicy	丢弃队列中「最旧的任务」，尝试重新提交当前任务	希望优先执行最新任务的场景
    三、ThreadPoolExecutor 的工作流程（核心逻辑）







    用餐厅例子再梳理一遍：
客人（任务）来餐厅，先看正式服务员（核心线程）有没有空闲：有空就直接服务（执行任务）。
正式服务员都忙了，就看候客区（工作队列）有没有位置：有位置就排队等。
候客区也满了，就看能不能招临时工（非核心线程）：能招就招临时工服务。
临时工也招满了（达到最大线程数），就按规则拒绝客人（执行拒绝策略）。
四、完整代码示例（可直接运行）
下面写一个贴近实际开发的例子，包含「自定义线程工厂」「有界工作队列」「自定义拒绝策略」，注释详细：
java
运行
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolExecutorDemo {
    // 自定义线程工厂：给线程命名，便于排查问题
    static class CustomThreadFactory implements ThreadFactory {
        // 原子整数：保证线程数计数安全
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String prefix = "demo-thread-";

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + threadNumber.getAndIncrement());
            // 设置为非守护线程：避免主线程结束后线程池被强制关闭
            thread.setDaemon(false);
            // 设置线程优先级：默认5即可，无需修改
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 1. 获取CPU核心数：用于合理设置线程数
        int cpuCoreNum = Runtime.getRuntime().availableProcessors();
        System.out.println("CPU核心数：" + cpuCoreNum);

        // 2. 配置线程池参数（按IO密集型任务配置）
        int corePoolSize = cpuCoreNum * 2;    // 核心线程数：IO密集型=CPU核心数*2
        int maximumPoolSize = cpuCoreNum * 4; // 最大线程数：核心数*4
        long keepAliveTime = 30;              // 非核心线程空闲30秒销毁
        TimeUnit unit = TimeUnit.SECONDS;
        // 有界工作队列：容量100，避免无限膨胀
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(100);
        // 自定义线程工厂
        ThreadFactory threadFactory = new CustomThreadFactory();
        // 自定义拒绝策略（也可用默认的AbortPolicy）
        RejectedExecutionHandler rejectHandler = (r, executor) -> {
            System.out.println("任务被拒绝：" + r.toString() + "，当前线程池活跃数：" + executor.getActiveCount());
            // 可在这里做日志记录、告警等操作
        };

        // 3. 创建线程池（手动创建，推荐方式）
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                workQueue,
                threadFactory,
                rejectHandler
        );

        // 4. 提交10个任务测试
        for (int i = 0; i < 10; i++) {
            int taskId = i;
            executor.execute(() -> {
                try {
                    // 模拟任务执行（比如IO操作：数据库/网络请求）
                    Thread.sleep(1000);
                    System.out.println("任务" + taskId + "执行完成，执行线程：" + Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    e.printStackTrace();
                }
            });
        }

        // 5. 关闭线程池（重要：避免资源泄漏）
        // shutdown()：平缓关闭，等待已提交的任务执行完毕
        executor.shutdown();
        // 等待线程池终止，超时10秒
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // 超时后强制关闭
            executor.shutdownNow();
        }

        System.out.println("所有任务执行完毕，线程池已关闭");
    }
}
代码运行结果（示例）
plaintext
CPU核心数：8
任务0执行完成，执行线程：demo-thread-1
任务1执行完成，执行线程：demo-thread-2
任务2执行完成，执行线程：demo-thread-3
...
任务9执行完成，执行线程：demo-thread-8
所有任务执行完毕，线程池已关闭
关键方法说明
方法	作用
execute(Runnable task)	提交「无返回值」的任务（最常用）
submit(Callable<T> task)	提交「有返回值」的任务，返回 Future<T> 可获取结果
shutdown()	平缓关闭：不再接收新任务，等待队列 + 正在执行的任务完成
shutdownNow()	强制关闭：中断正在执行的任务，返回未执行的任务列表
awaitTermination(timeout, unit)	等待线程池终止，超时返回 false（用于确保线程池关闭）
getActiveCount()	获取当前活跃线程数（监控用）
getCompletedTaskCount()	获取已完成的任务总数（监控用）
五、避坑：Executors 工具类的弊端（新手必看）
JDK 提供了 Executors 工具类快速创建线程池，但阿里开发手册明确禁止使用，原因是默认参数有内存溢出风险：
工具方法	问题
Executors.newFixedThreadPool(n)	工作队列是无界的 LinkedBlockingQueue，任务过多会导致 OOM
Executors.newCachedThreadPool()	最大线程数是 Integer.MAX_VALUE，任务过多会创建大量线程导致 OOM
Executors.newSingleThreadExecutor()	工作队列无界，同样有 OOM 风险
✅ 正确做法：手动创建 ThreadPoolExecutor，明确所有参数，尤其是「有界工作队列」和「合理的最大线程数」。
六、最佳实践（新手直接套用）
线程数配置规则：
计算密集型任务（如数学运算、排序）：corePoolSize = CPU核心数 + 1（减少线程切换开销）。
IO 密集型任务（如数据库、网络请求）：corePoolSize = CPU核心数 * 2（线程大部分时间在等 IO，可多开）。
工作队列必须有界：容量根据业务 QPS 设置（比如 100~1000），避免无界队列。
自定义线程工厂：给线程命名（如 order-service-thread-1），便于日志排查问题。
拒绝策略要兜底：至少记录日志，核心任务建议用 AbortPolicy（抛异常感知），非核心任务用 CallerRunsPolicy。
必须关闭线程池：在程序结束前调用 shutdown()，避免线程泄漏。
监控线程池状态：通过 getActiveCount()/getCompletedTaskCount() 监控，及时发现异常。









    */








}
