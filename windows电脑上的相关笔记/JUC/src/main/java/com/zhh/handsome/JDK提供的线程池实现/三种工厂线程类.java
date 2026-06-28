package com.zhh.handsome.JDK提供的线程池实现;

public class 三种工厂线程类 {
       
}


// 一、Executors.newFixedThreadPool(int nThreads)：固定核心线程数的线程池
// 1. 底层构造（源码 + 参数拆解）
// 这是最常用的 “固定线程池”，本质是对 ThreadPoolExecutor 的封装，核心是「核心线程数 = 最大线程数」+「无界队列」，源码如下：
// java
// 运行
// public static ExecutorService newFixedThreadPool(int nThreads) {
//     return new ThreadPoolExecutor(
//         nThreads,               // corePoolSize：核心线程数 = 传入的nThreads
//         nThreads,               // maximumPoolSize：最大线程数 = 核心线程数（无非核心线程）
//         0L,                     // keepAliveTime：0秒（非核心线程空闲存活时间，此处无意义）
//         TimeUnit.MILLISECONDS,  // 时间单位：毫秒
//         new LinkedBlockingQueue<Runnable>() // 工作队列：默认无界的LinkedBlockingQueue
//     );
// }
// 参数关键特点：
// corePoolSize = maximumPoolSize：线程池只有核心线程，没有非核心线程；
// keepAliveTime=0：无意义（因为没有非核心线程）；
// 工作队列是 LinkedBlockingQueue（默认容量 Integer.MAX_VALUE，逻辑无界）。
// 2. 核心执行逻辑
// 结合之前讲的 execute() 流程，它的任务处理逻辑：
// 提交任务时，若核心线程数未满（<nThreads），创建核心线程执行任务；
// 核心线程数满后，所有新任务都进入无界队列排队；
// 永远不会创建非核心线程（因为 maximumPoolSize=corePoolSize），也永远不会触发拒绝策略（除非 OOM）；
// 核心线程会一直存活（即使空闲），复用执行队列中的任务。
// 3. 设计目的
// JDK 设计它的核心目标：
// 「稳定复用」：固定数量的核心线程长期存活，避免频繁创建 / 销毁线程的开销；
// 「简单可控」：线程数固定，适合任务执行时间相对稳定、并发量可预估的场景；
// 「排队处理」：通过无界队列缓冲任务，保证任务不丢失（理论上，直到内存溢出）。
// 4. 适用场景（仅推荐临时 / 简单场景）
// 任务执行时间短、并发量稳定，且能预估最大并发数（比如后台定时任务、低 QPS 的接口异步处理）；
// 示例：服务器后台处理固定频率的日志清理任务，并发量始终不超过线程数。
// 5. 核心弊端（禁止生产直接使用的原因）
// 无界队列导致 OOM：LinkedBlockingQueue 默认无界，若任务提交速度远大于执行速度，队列会无限膨胀，最终触发 JVM 内存溢出（OOM）；
// 最大线程数失效：因为队列无界，maximumPoolSize 形同虚设，无法通过调整最大线程数应对突发流量；
// 核心线程永不销毁：即使长期空闲，nThreads 个核心线程也会一直占用资源（可通过 allowCoreThreadTimeOut(true) 手动开启核心线程超时销毁，但工具类默认不支持）。
// 二、Executors.newCachedThreadPool()：可缓存的弹性线程池
// 1. 底层构造（源码 + 参数拆解）
// 这是 “弹性线程池”，核心是「无核心线程 + 最大线程数无限 + 同步队列 + 空闲线程超时销毁」，源码如下：
// java
// 运行
// public static ExecutorService newCachedThreadPool() {
//     return new ThreadPoolExecutor(
//         0,                      // corePoolSize：核心线程数=0（无常驻线程）
//         Integer.MAX_VALUE,      // maximumPoolSize：最大线程数=Integer.MAX_VALUE（理论无限）
//         60L,                    // keepAliveTime：非核心线程空闲60秒后销毁
//         TimeUnit.SECONDS,       // 时间单位：秒
//         new SynchronousQueue<Runnable>() // 工作队列：同步队列（不存储任务）
//     );
// }
// 参数关键特点：
// corePoolSize=0：线程池没有常驻核心线程，所有线程都是非核心线程；
// maximumPoolSize=Integer.MAX_VALUE：理论上可创建无限个线程；
// 工作队列是 SynchronousQueue（同步队列）：不存储任何任务，任务提交时必须有线程立即接收，否则直接创建新线程。
// 2. 核心执行逻辑
// 提交任务时，因为核心线程数 = 0，直接尝试将任务放入 SynchronousQueue；
// SynchronousQueue 不存储任务，若此时有空闲线程（刚执行完任务，还没超时销毁），则空闲线程从队列取走任务执行；
// 若没有空闲线程，队列入队失败，触发创建新线程（非核心线程）执行任务；
// 线程执行完任务后，空闲 60 秒若没有新任务，自动销毁；
// 极端情况下，若短时间提交大量任务，会创建大量线程（直到 Integer.MAX_VALUE）。
// 3. 设计目的
// JDK 设计它的核心目标：
// 「极致弹性」：应对突发的、短耗时的任务（比如临时的批量处理、高并发短请求），任务来临时快速创建线程，任务结束后自动销毁线程释放资源；
// 「零常驻开销」：没有核心线程，空闲时线程池几乎不占用资源；
// 「无排队延迟」：同步队列不存储任务，任务要么立即被执行，要么立即创建线程执行，无排队等待时间。
// 4. 适用场景（仅推荐临时 / 短任务场景）
// 任务执行时间极短（毫秒级）、突发并发量高，但任务完成后无持续压力；
// 示例：临时处理一批 CSV 文件解析任务（耗时短、一次性），处理完后线程自动销毁，不占用资源。
// 5. 核心弊端（禁止生产直接使用的原因）
// 创建大量线程导致 OOM：maximumPoolSize=Integer.MAX_VALUE，若短时间提交大量耗时稍长的任务，会创建成千上万的线程，每个线程占用栈空间（默认 1MB），快速耗尽内存导致 OOM；
// 线程频繁创建 / 销毁：若任务提交频率不稳定，会频繁创建和销毁线程，反而增加系统开销（线程创建的内核态切换成本）；
// 无拒绝策略兜底：默认用 AbortPolicy 拒绝策略，但因为最大线程数近乎无限，拒绝策略几乎不会触发，直到 OOM 才会报错。
// 三、Executors.newSingleThreadExecutor()：单线程串行执行的线程池
// 1. 底层构造（源码 + 参数拆解）
// 这是 “单线程池”，本质是「核心线程数 = 1、最大线程数 = 1」+「无界队列」，且包装了一层 FinalizableDelegatedExecutorService（防止修改线程数），源码如下：
// java
// 运行
// public static ExecutorService newSingleThreadExecutor() {
//     return new FinalizableDelegatedExecutorService( // 包装类：禁止修改线程池参数
//         new ThreadPoolExecutor(
//             1,                   // corePoolSize：核心线程数=1
//             1,                   // maximumPoolSize：最大线程数=1
//             0L,                  // keepAliveTime：0秒（无意义）
//             TimeUnit.MILLISECONDS,
//             new LinkedBlockingQueue<Runnable>() // 工作队列：无界LinkedBlockingQueue
//         )
//     );
// }
// 参数关键特点：
// corePoolSize=maximumPoolSize=1：线程池永远只有 1 个核心线程；
// 包装类 FinalizableDelegatedExecutorService：屏蔽了 ThreadPoolExecutor 的 setCorePoolSize、setMaximumPoolSize 等方法，防止修改线程数；
// 工作队列是无界的 LinkedBlockingQueue。
// 2. 核心执行逻辑
// 所有任务都由唯一的核心线程串行执行（先入先出）；
// 核心线程忙时，新任务进入无界队列排队；
// 若核心线程意外终止（比如抛异常），线程池会自动创建一个新的核心线程替代，保证 “始终有一个线程执行任务”；
// 永远不会创建非核心线程，也不会触发拒绝策略（除非 OOM）。
// 3. 设计目的
// JDK 设计它的核心目标：
// 「串行执行」：保证所有任务按提交顺序执行，避免多线程并发带来的线程安全问题（比如无需加锁的任务排队）；
// 「单线程兜底」：即使唯一的线程异常，也会自动重建，保证任务持续执行；
// 「简单易用」：无需关心线程数管理，适合需要 “单线程处理所有任务” 的场景。
// 4. 适用场景（仅推荐简单串行场景）
// 任务需要严格按提交顺序执行，且并发量低（比如单线程处理日志写入、单线程处理消息队列消费）；
// 示例：后台单线程处理用户注册后的短信发送任务，保证短信发送顺序和用户注册顺序一致。
// 5. 核心弊端（禁止生产直接使用的原因）
// 无界队列导致 OOM：和 newFixedThreadPool 一样，LinkedBlockingQueue 无界，任务堆积会导致内存溢出；
// 无法扩展线程数：包装类屏蔽了修改线程数的方法，即使业务并发量上涨，也无法临时增加线程数；
// 单线程瓶颈：所有任务串行执行，若某个任务执行耗时过长（比如 IO 阻塞），会导致后续所有任务排队，系统响应变慢。
// 总结（核心关键点）
// 线程池类型	核心设计目的	核心参数特点	最大弊端	替代方案（生产推荐）
// newFixedThreadPool	固定线程复用，稳定处理任务	核心 = 最大、无界队列	无界队列导致 OOM	手动创建 ThreadPoolExecutor，用有界队列
// newCachedThreadPool	弹性应对短任务，无常驻开销	核心 = 0、最大无限、同步队列	创建大量线程导致 OOM	手动创建 ThreadPoolExecutor，限制最大线程数
// newSingleThreadExecutor	单线程串行执行，保证顺序	核心 = 最大 = 1、无界队列、不可改	无界队列 OOM + 单线程瓶颈	手动创建 ThreadPoolExecutor，用有界队列
// 通用替代原则（生产必看）
// 无论使用哪种线程池，都推荐手动创建 ThreadPoolExecutor，明确以下参数：
// 核心线程数：按业务类型（计算密集型 = CPU 核心数 + 1，IO 密集型 = CPU 核心数 * 2）；
// 最大线程数：核心线程数的 1~2 倍（避免创建过多线程）；
// 工作队列：用有界队列（如 ArrayBlockingQueue，容量根据 QPS 设置）；
// 拒绝策略：自定义（比如记录日志 + 告警，核心任务用 CallerRunsPolicy 兜底）。
// 这也是阿里开发手册禁止直接使用 Executors 工具类的核心原因 —— 封装的默认参数（无界队列、无限最大线程数）存在严重的内存溢出风险，而手动创建能精准控制线程池的行为。