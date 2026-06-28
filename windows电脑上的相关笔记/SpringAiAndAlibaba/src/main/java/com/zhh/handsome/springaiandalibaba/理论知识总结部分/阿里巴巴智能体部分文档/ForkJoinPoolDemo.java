package com.zhh.handsome.springaiandalibaba.理论知识总结部分.阿里巴巴智能体部分文档;

public class ForkJoinPoolDemo {

   /* 我为什么单独把这个线程池拿出来讲呢，因为他太特殊了
    它是 JDK 7 引入的一种特殊的线程池，专为解决「可拆分的递归任务（分治任务）」设计（比如大数据量的排序、求和、过滤），核心思想是：
    分而治之（Fork/Join）：将大任务拆分成多个小任务（Fork），并行执行后合并结果（Join）；
    工作窃取（Work Stealing）：让空闲线程 “窃取” 忙碌线程的任务，最大化利用 CPU 资源，避免线程空闲。
    相比传统ThreadPoolExecutor，它解决了 “线程负载不均” 的问题（比如部分线程忙死、部分线程闲死），尤其适合计算密集型任务。*/



//    二、底层原理（深度拆解）
//            2.1 核心架构
//    ForkJoinPool的底层由三大核心组件构成，结构如下：
//    graph TD
//    A[ForkJoinPool] --> B[全局队列（Global Queue）]
//    A --> C[工作线程组（ForkJoinWorkerThread[]）]
//    C --> D[工作线程1（ForkJoinWorkerThread）]
//    C --> E[工作线程2（ForkJoinWorkerThread）]
//    D --> F[本地工作队列1（WorkQueue，双端队列）]
//    E --> G[本地工作队列2（WorkQueue，双端队列）]
//
//
//
//
//            （1）核心组件详解
//    组件	作用
//    ForkJoinWorkerThread	ForkJoinPool专属的线程类型（继承Thread），每个线程绑定一个WorkQueue
//    WorkQueue	双端队列（Deque），存储待执行的ForkJoinTask，支持 CAS 保证并发安全
//    全局队列	存储外部提交的任务（比如pool.submit(task)），所有线程都可访问
//    本地队列	每个工作线程独有，存储该线程fork出的子任务，优先级高于全局队列



//    （2）WorkQueue 关键特性
//    底层是数组实现的双端队列，支持从「头部（LIFO）」和「尾部（FIFO）」操作；
//    用CAS操作保证并发安全（因为窃取线程会操作其他线程的队列尾部）；
//    无界队列（默认），但拆分过深会导致 OOM，需控制拆分粒度。



//    2.2 核心机制：工作窃取（Work Stealing）
//    这是ForkJoinPool最核心的底层逻辑，目的是最大化 CPU 利用率：
//            （1）窃取规则（核心细节）
//    本地线程：从自己的WorkQueue头部取任务执行（LIFO，栈式）—— 因为递归拆分的子任务最后提交的先执行，局部性更好，缓存命中率高；
//    窃取线程：从被窃取线程的WorkQueue尾部取任务执行（FIFO）—— 避免和被窃取线程的 “头部取任务” 操作竞争，减少 CAS 冲突；
//    窃取优先级：线程空闲时，先检查自己的本地队列 → 再检查全局队列 → 最后随机找其他线程的本地队列偷任务。
//            （2）窃取触发时机
//    工作线程完成自己本地队列的所有任务；
//    调用join()等待子任务完成时（未完成则去偷任务，而非阻塞）。


//    graph TD
//    A[提交大任务：计算数组[0,10000)的和] --> B[Fork：拆分成两个子任务[0,5000)、[5000,10000)]
//    B --> C[子任务提交到当前线程的本地队列]
//    C --> D{线程是否空闲？}
//    D -->|空闲| E[执行本地队列头部任务]
//    D -->|忙碌| F[其他线程窃取本地队列尾部的子任务]
//    E --> G{子任务是否可拆分？}
//    G -->|是| B
//    G -->|否| H[执行小任务，返回结果]
//    F --> G
//    H --> I[Join：合并所有子任务的结果]
//    I --> J[返回最终结果]
//
//
//
//
//
//
//
//
//
//
//
//            （3）Join 方法的底层逻辑（关键细节）
//    join()不是简单的 “阻塞等待”，而是主动偷任务，避免线程空闲：
//    检查子任务是否已完成：完成则直接返回结果；
//    未完成时，若当前线程是ForkJoinWorkerThread：去偷其他任务执行，直到子任务完成；
//    若当前线程是普通线程（非工作线程）：阻塞等待子任务完成。
//            2.4 线程管理机制
//    并行度（Parallelism）：默认等于 CPU 核心数（Runtime.getRuntime().availableProcessors()），表示 “目标并发线程数”，线程池会尽量维持这个数量的工作线程；
//    线程创建：懒加载 —— 只有提交任务时才创建线程，不会提前创建；
//    线程销毁：空闲时会逐步销毁（默认空闲 60 秒后销毁），但至少保留parallelism个核心线程（可通过参数调整）；
//    状态管理：用AtomicInteger存储线程池状态（运行、关闭、终止等），保证并发下的状态一致性。
//    三、核心 API 详解
//3.1 核心类关系
//            plaintext
//    ForkJoinTask（抽象类）
//            ├─ RecursiveTask<T>：有返回值的递归任务（核心）
//            ├─ RecursiveAction：无返回值的递归任务（核心）
//            └─ CountedCompleter<T>：完成后触发回调的高级任务
//    ForkJoinPool是线程池，负责调度执行ForkJoinTask。
//            3.2 ForkJoinPool 的创建
//（1）构造方法
//    构造方法	说明
//    ForkJoinPool()	无参构造，并行度 = CPU 核心数，默认线程工厂，无异常处理器，asyncMode=false
//    ForkJoinPool(int parallelism)	指定并行度
//    ForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory, Thread.UncaughtExceptionHandler handler, boolean asyncMode)	完整构造：
//            - factory：自定义工作线程创建工厂；
//            - handler：未捕获异常处理器；
//            - asyncMode：队列模式（true=FIFO，false=LIFO）
//            （2）公共线程池（常用）
//    java
//            运行
//    // JVM全局共享的公共线程池，减少线程池创建开销
//    ForkJoinPool commonPool = ForkJoinPool.commonPool();
//⚠️ 注意：不要手动关闭commonPool，否则会影响 JVM 其他组件的使用。
//            3.3 ForkJoinPool 核心方法（任务提交 / 执行）
//    方法	作用
//    execute(ForkJoinTask<?> task)	异步执行任务，无返回值，任务提交后立即返回
//    submit(ForkJoinTask<T> task)	异步提交任务，返回ForkJoinTask<T>，可通过get()获取结果
//    invoke(ForkJoinTask<T> task)	同步执行任务，阻塞直到任务完成，直接返回结果
//    invokeAll(ForkJoinTask<?>... tasks)	同步执行多个任务，等待所有任务完成
//    shutdown()	关闭线程池，不再接受新任务，等待已有任务完成
//    shutdownNow()	立即关闭线程池，尝试中断正在执行的任务
//    getParallelism()	获取线程池的并行度
//    getActiveThreadCount()	获取当前活跃的工作线程数
//3.4 ForkJoinTask 核心方法
//    方法	作用
//    fork()	拆分任务：将当前任务提交到当前工作线程的本地队列，异步执行
//    join()	等待任务完成并获取结果，不抛出检查异常（异常封装为 RuntimeException）
//    get()	等待任务完成并获取结果，抛出检查异常（InterruptedException/ExecutionException）
//    isDone()	判断任务是否完成（正常 / 异常 / 取消）
//    cancel(boolean mayInterrupt)	取消任务，参数表示是否中断正在执行的任务
//    getException()	获取任务执行过程中抛出的异常（无异常则返回 null）
//            3.5 常用子类实战示例
//    示例 1：RecursiveTask（有返回值，数组求和）
//    java
//            运行
//import java.util.concurrent.ForkJoinPool;
//import java.util.concurrent.RecursiveTask;
//
//    // 计算大数组的和（分治）
//    class SumTask extends RecursiveTask<Long> {
//        // 拆分阈值：小于该值直接计算，不拆分（控制拆分粒度）
//        private static final int THRESHOLD = 1000;
//        private long[] array;
//        private int start;
//        private int end;
//
//        public SumTask(long[] array, int start, int end) {
//            this.array = array;
//            this.start = start;
//            this.end = end;
//        }
//
//        // 核心方法：计算/拆分任务
//        @Override
//        protected Long compute() {
//            // 任务足够小，直接计算
//            if (end - start <= THRESHOLD) {
//                long sum = 0;
//                for (int i = start; i < end; i++) {
//                    sum += array[i];
//                }
//                return sum;
//            }
//
//            // 拆分任务
//            int mid = (start + end) / 2;
//            SumTask leftTask = new SumTask(array, start, mid);
//            SumTask rightTask = new SumTask(array, mid, end);
//
//            // 异步执行子任务（fork）
//            leftTask.fork();
//            rightTask.fork();
//
//            // 合并结果（join）
//            return leftTask.join() + rightTask.join();
//        }
//
//        public static void main(String[] args) {
//            // 初始化10000个元素的数组
//            long[] array = new long[10000];
//            for (int i = 0; i < array.length; i++) {
//                array[i] = i + 1;
//            }
//
//            // 1. 使用公共线程池
//            ForkJoinPool pool = ForkJoinPool.commonPool();
//            // 2. 提交任务并获取结果
//            Long result = pool.invoke(new SumTask(array, 0, array.length));
//            System.out.println("数组和：" + result); // 输出：50005000
//
//            // 3. 关闭线程池（公共线程池不建议关，这里仅演示）
//            pool.shutdown();
//        }
//    }
//    示例 2：RecursiveAction（无返回值，数组元素翻倍）
//    java
//            运行
//import java.util.concurrent.ForkJoinPool;
//import java.util.concurrent.RecursiveAction;
//
//    class DoubleTask extends RecursiveAction {
//        private static final int THRESHOLD = 1000;
//        private int[] array;
//        private int start;
//        private int end;
//
//        public DoubleTask(int[] array, int start, int end) {
//            this.array = array;
//            this.start = start;
//            this.end = end;
//        }
//
//        @Override
//        protected void compute() {
//            if (end - start <= THRESHOLD) {
//                // 直接执行：元素翻倍
//                for (int i = start; i < end; i++) {
//                    array[i] *= 2;
//                }
//            } else {
//                int mid = (start + end) / 2;
//                DoubleTask left = new DoubleTask(array, start, mid);
//                DoubleTask right = new DoubleTask(array, mid, end);
//                // 执行所有子任务
//                invokeAll(left, right);
//            }
//        }
//
//        public static void main(String[] args) {
//            int[] array = new int[10000];
//            for (int i = 0; i < array.length; i++) {
//                array[i] = i;
//            }
//
//            ForkJoinPool pool = new ForkJoinPool();
//            pool.invoke(new DoubleTask(array, 0, array.length));
//            System.out.println("第一个元素：" + array[0]); // 0
//            System.out.println("最后一个元素：" + array[9999]); // 19998
//            pool.shutdown();
//        }
//    }
//    四、关键细节与注意事项
//4.1 拆分粒度的控制（核心细节）
//    太粗：任务拆分不足，无法充分利用多核 CPU，并行度不够；
//    太细：任务调度开销（fork/join、CAS 操作）大于计算开销，反而变慢；
//    最佳实践：拆分阈值设为 “单个任务执行时间 1~10ms”（经验值），比如数组求和的阈值可设为 1000~10000（根据数组元素计算复杂度调整）。
//            4.2 异常处理
//    ForkJoinTask的异常不会直接抛出，需主动获取：
//    java
//            运行
//    SumTask task = new SumTask(array, 0, array.length);
//pool.submit(task);
//// 获取异常
//if (task.isCompletedExceptionally()) {
//        Throwable e = task.getException();
//        e.printStackTrace();
//    }
//    创建ForkJoinPool时可指定异常处理器：
//    java
//            运行
//    ForkJoinPool pool = new ForkJoinPool(4,
//            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
//            (thread, e) -> {
//                // 自定义未捕获异常处理逻辑
//                System.err.println("线程" + thread.getName() + "抛出异常：" + e.getMessage());
//            },
//            false);
//4.3 asyncMode 的选择
//    asyncMode=false（默认）：本地队列用 LIFO（栈式），适合计算密集型任务（递归拆分的任务局部性好）；
//    asyncMode=true：本地队列用 FIFO（队列式），适合事件驱动型任务（比如流水线处理、任务按提交顺序执行）。
//            4.4 适用场景与不适用场景
//    适用场景	不适用场景
//    计算密集型任务（排序、求和、过滤）	IO 密集型任务（网络 / 文件 IO 会阻塞线程）
//    可递归拆分的大任务	短时间内大量极小任务（调度开销大）
//    无状态的任务	有状态的任务（线程安全问题）
//            4.5 公共线程池（commonPool）的坑
//    commonPool的并行度可通过 JVM 参数调整：-Djava.util.concurrent.ForkJoinPool.common.parallelism=8；
//    commonPool的任务会和 JVM 其他组件（如 Stream.parallel ()）共享线程，任务过多会互相阻塞；
//            不要手动调用commonPool.shutdown()，否则会导致依赖它的代码（如 Stream 并行流）报错。
//    五、总结
//            关键点回顾
//    核心原理：ForkJoinPool基于 “分治 + 工作窃取”，工作线程从本地队列头部取任务，空闲线程从其他队列尾部偷任务，最大化 CPU 利用率；
//    核心 API：
//    线程池：ForkJoinPool.commonPool()（常用）、invoke()（同步执行）、submit()（异步执行）；
//    任务：RecursiveTask（有返回值）、RecursiveAction（无返回值），核心方法fork()（拆分）、join()（合并）；
//    关键细节：控制任务拆分粒度（1~10ms / 任务）、避免在ForkJoinPool中执行 IO 密集型任务、不要关闭公共线程池。

}
