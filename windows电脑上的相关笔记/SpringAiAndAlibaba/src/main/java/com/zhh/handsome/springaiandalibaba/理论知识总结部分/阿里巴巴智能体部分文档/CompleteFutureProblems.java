package com.zhh.handsome.springaiandalibaba.理论知识总结部分.阿里巴巴智能体部分文档;

public class CompleteFutureProblems {
//    CompletableFuture是 Java 8 引入的异步编程神器，但生产环境中直接 “裸用” 确实极易踩坑 —— 核心问题集中在线程池管理、异常处理、阻塞调用、资源泄漏等方面。下面我会把每个坑拆解得 “掰开揉碎”，结合生产场景讲清楚，确保新手也能理解。
//    一、核心坑点全解析（按踩坑概率排序）
//    坑 1：滥用默认线程池（ForkJoinPool.commonPool ()）—— 生产第一大坑
//    现象 / 问题
//    服务高峰期出现任务堆积、响应超时，甚至整个 JVM 的线程池被耗尽；
//    并行流（Stream.parallel ()）、ForkJoinTask 等依赖commonPool的组件突然卡顿 / 失效；
//    应用日志中出现RejectedExecutionException，但找不到具体来源；
//    线程池线程是守护线程，JVM 退出时未完成的任务会直接中断，导致数据不一致。
//    底层原因
//    CompletableFuture的异步方法（supplyAsync()/runAsync()无线程池参数时），默认使用ForkJoinPool.commonPool()：
//    全局共享：commonPool是 JVM 级别的全局线程池，所有应用代码、框架（如 Spring）、JDK 内置组件都会共用；
//    并行度极低：默认并行度 = CPU 核心数 - 1（比如 8 核 CPU 只有 7 个线程），若任务中有阻塞（哪怕 1ms），线程池瞬间被占满；
//    线程是守护线程：JVM 进程退出时，commonPool的线程会直接终止，未完成的任务会丢失；
//    无隔离性：一个业务的慢任务会拖垮所有依赖commonPool的业务。
//
//
//
//    解决方案（生产必做）
//            ✅ 核心原则：所有 CompletableFuture 异步方法，必须显式指定自定义线程池，绝对不用默认池。
//
//
//            import java.util.concurrent.*;
//
//    /**
//     * 生产环境CompletableFuture专用线程池配置
//     * 区分IO密集型（如DB/网络请求）和计算密集型（如数据处理）
//     */
//    public class CompletableFutureThreadPoolConfig {
//        // === IO密集型线程池（核心：2*CPU核心数 + 队列数）===
//        private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
//        // 核心线程数：IO密集型任务线程数可配置为 2*CPU核心数
//        private static final int IO_CORE_POOL_SIZE = CPU_CORES * 2;
//        // 最大线程数：避免突发流量导致线程过多，一般设为核心数的2~4倍
//        private static final int IO_MAX_POOL_SIZE = CPU_CORES * 4;
//        // 空闲线程存活时间：60秒（避免长期空闲线程占用资源）
//        private static final long IO_KEEP_ALIVE_TIME = 60L;
//        // 任务队列：有界队列（绝对避免无界队列，防止OOM）
//        private static final BlockingQueue<Runnable> IO_QUEUE = new ArrayBlockingQueue<>(1000);
//        // 线程工厂：命名规范，方便问题排查（生产必备）
//        private static final ThreadFactory IO_THREAD_FACTORY = new ThreadFactory() {
//            private int count = 1;
//            @Override
//            public Thread newThread(Runnable r) {
//                Thread thread = new Thread(r);
//                // 命名格式：业务名-类型-序号，比如order-io-1
//                thread.setName("completable-io-" + count++);
//                // 设为非守护线程（避免JVM退出时任务中断）
//                thread.setDaemon(false);
//                // 设置未捕获异常处理器（兜底）
//                thread.setUncaughtExceptionHandler((t, e) -> {
//                    System.err.println("线程" + t.getName() + "抛出未捕获异常：" + e.getMessage());
//                    // 生产中可接入告警（钉钉/短信/日志平台）
//                });
//                return thread;
//            }
//        };
//        // 拒绝策略：生产级（记录日志+告警+兜底执行）
//        private static final RejectedExecutionHandler IO_REJECT_HANDLER = new ThreadPoolExecutor.CallerRunsPolicy() {
//            @Override
//            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
//                // 1. 记录拒绝日志（包含任务信息、线程池状态）
//                System.err.println("IO线程池拒绝任务！线程池状态：" +
//                        "核心数=" + executor.getCorePoolSize() +
//                        "，活跃数=" + executor.getActiveCount() +
//                        "，队列数=" + executor.getQueue().size());
//                // 2. 生产中添加告警（如调用钉钉机器人）
//                // alertService.sendAlert("CompletableFuture IO线程池任务被拒绝！");
//                // 3. 兜底执行（CallerRunsPolicy会让调用线程执行，避免任务丢失）
//                super.rejectedExecution(r, executor);
//            }
//        };
//
//        // 初始化IO密集型线程池（单例，避免重复创建）
//        public static final ExecutorService IO_EXECUTOR = new ThreadPoolExecutor(
//                IO_CORE_POOL_SIZE,
//                IO_MAX_POOL_SIZE,
//                IO_KEEP_ALIVE_TIME,
//                TimeUnit.SECONDS,
//                IO_QUEUE,
//                IO_THREAD_FACTORY,
//                IO_REJECT_HANDLER
//        );
//
//        // === 计算密集型线程池（核心：CPU核心数）===
//        public static final ExecutorService COMPUTE_EXECUTOR = new ThreadPoolExecutor(
//                CPU_CORES,
//                CPU_CORES,
//                60L,
//                TimeUnit.SECONDS,
//                new ArrayBlockingQueue<>(500),
//                new ThreadFactory() {
//                    private int count = 1;
//                    @Override
//                    public Thread newThread(Runnable r) {
//                        Thread thread = new Thread(r);
//                        thread.setName("completable-compute-" + count++);
//                        thread.setDaemon(false);
//                        return thread;
//                    }
//                },
//                IO_REJECT_HANDLER
//        );
//
//        // 优雅关闭线程池（应用关闭时调用）
//        public static void shutdown() {
//            // 关闭IO线程池
//            IO_EXECUTOR.shutdown();
//            try {
//                if (!IO_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
//                    IO_EXECUTOR.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                IO_EXECUTOR.shutdownNow();
//            }
//            // 关闭计算线程池
//            COMPUTE_EXECUTOR.shutdown();
//            try {
//                if (!COMPUTE_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
//                    COMPUTE_EXECUTOR.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                COMPUTE_EXECUTOR.shutdownNow();
//            }
//        }
//    }

//
//    步骤 2：使用自定义线程池调用 CompletableFuture
//    // 错误写法（用默认commonPool）
//    CompletableFuture<String> badFuture = CompletableFuture.supplyAsync(() -> {
//        // 模拟DB查询（IO密集型）
//        try { Thread.sleep(100); } catch (InterruptedException e) { throw new RuntimeException(e); }
//        return "查询结果";
//    });
//
//    // 正确写法（显式指定IO密集型线程池）
//    CompletableFuture<String> goodFuture = CompletableFuture.supplyAsync(() -> {
//        // 模拟DB查询（IO密集型）
//        try { Thread.sleep(100); } catch (InterruptedException e) { throw new RuntimeException(e); }
//        return "查询结果";
//    }, CompletableFutureThreadPoolConfig.IO_EXECUTOR);
//
//
//    步骤 3：兜底配置（若必须用 commonPool）
//    若因特殊原因必须用commonPool，需通过 JVM 参数调整并行度：
//    bash
//            运行
//# JVM启动参数：设置commonPool并行度为16（根据服务器配置调整）
//            -Djava.util.concurrent.ForkJoinPool.common.parallelism=16
//
//
//
//
//    坑 2：异步任务中执行阻塞操作（IO / 锁等待）—— 线程池瞬间耗尽
//    现象 / 问题
//    线程池所有线程被阻塞，新任务无法执行，服务响应超时；
//    线程池活跃数长期等于最大线程数，队列持续满负载；
//    监控中看到 CPU 利用率低，但线程数占满，服务吞吐量暴跌。
//    底层原因
//    CompletableFuture的线程池若按 “计算密集型” 配置（线程数 = CPU 核心数），但任务是 IO 密集型（DB 查询、Redis 请求、HTTP 调用、锁等待），线程会陷入阻塞；
//    每个阻塞的线程都会占用线程池资源，新任务只能排队，最终导致线程池 “假死”。
//    解决方案
//✅ 核心原则：IO 密集型任务和计算密集型任务用不同的线程池，线程数配置差异化。
//    线程池参数区分：
//    计算密集型：线程数 = CPU 核心数（最大化利用 CPU，避免上下文切换）；
//    IO 密集型：线程数 = 2CPU 核心数～4CPU 核心数（弥补阻塞时间，提高吞吐量）；
//    避免在 CompletableFuture 中执行长时间阻塞操作：
//            比如禁止在异步任务中调用Thread.sleep()、CountDownLatch.await()、synchronized重量级锁等；
//    若必须阻塞，改用非阻塞方案（如 Netty 的异步 IO、Redis 的异步客户端）；
//    监控线程池状态：
//    生产中必须监控线程池的activeCount、queueSize、completedTaskCount等指标，超过阈值及时告警。
//    坑 3：异常吞噬 —— 故障无感知、数据不一致
//    现象 / 问题
//    异步任务执行失败，但日志中无任何报错，程序看似正常运行但结果错误；
//    调用get()/join()时突然抛出CompletionException，但无法定位具体出错的任务；
//    部分任务静默失败，导致业务数据不一致（比如下单成功但支付异步任务失败，却无告警）。
//    底层原因
//    CompletableFuture的异常不会主动抛出，属于 “静默异常”：
//    若未通过exceptionally()/handle()/whenComplete()等方法处理异常，异常会被封装在CompletableFuture内部；
//    只有调用get()/join()时才会抛出CompletionException（底层是原异常）；
//    若从未调用get()/join()，异常会永远 “潜伏”，无法被发现。
//
//
//
//
//    坑 3：异常吞噬 —— 故障无感知、数据不一致
//    现象 / 问题
//    异步任务执行失败，但日志中无任何报错，程序看似正常运行但结果错误；
//    调用get()/join()时突然抛出CompletionException，但无法定位具体出错的任务；
//    部分任务静默失败，导致业务数据不一致（比如下单成功但支付异步任务失败，却无告警）。
//    底层原因
//    CompletableFuture的异常不会主动抛出，属于 “静默异常”：
//    若未通过exceptionally()/handle()/whenComplete()等方法处理异常，异常会被封装在CompletableFuture内部；
//    只有调用get()/join()时才会抛出CompletionException（底层是原异常）；
//    若从未调用get()/join()，异常会永远 “潜伏”，无法被发现。
//    解决方案
//✅ 核心原则：每个 CompletableFuture 必须显式处理异常，且日志要记录完整的异常栈和任务标识。
//    方案 1：使用whenComplete()（推荐，不改变返回值）
//    java
//            运行
//    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//                // 模拟业务异常
//                if (1 == 1) {
//                    throw new RuntimeException("DB查询失败：用户ID不存在");
//                }
//                return "业务结果";
//            }, CompletableFutureThreadPoolConfig.IO_EXECUTOR)
//// 必加：异常处理+日志记录
//            .whenComplete((result, ex) -> {
//                if (ex != null) {
//                    // 生产级日志：记录完整异常栈+任务上下文（如用户ID、订单号）
//                    System.err.println("异步任务执行失败！上下文：用户ID=10086，异常：", ex);
//                    // 接入告警系统
//                    // alertService.sendAlert("异步任务失败：" + ex.getMessage());
//                } else {
//                    // 可选：记录任务成功日志
//                    System.out.println("异步任务执行成功，结果：" + result);
//                }
//            });
//    方案 2：使用exceptionally()（兜底返回默认值）
//    java
//            运行
//    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//                throw new RuntimeException("调用第三方接口失败");
//            }, CompletableFutureThreadPoolConfig.IO_EXECUTOR)
//// 异常时返回兜底值，同时记录日志
//            .exceptionally(ex -> {
//                System.err.println("异步任务失败，返回兜底值：", ex);
//                return "默认值"; // 比如降级返回空数据、缓存数据等
//            });
//    方案 3：使用handle()（处理结果 + 异常，灵活）
//    java
//            运行
//    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//                return "业务结果";
//            }, CompletableFutureThreadPoolConfig.IO_EXECUTOR)
//// 同时处理成功结果和异常
//            .handle((result, ex) -> {
//                if (ex != null) {
//                    System.err.println("任务失败：", ex);
//                    return "兜底值";
//                }
//                return result + "（处理后）";
//            });
//    避坑提醒：
//    禁止只捕获异常不记录日志；
//            禁止用e.getMessage()代替完整异常栈（会丢失关键调用信息）；
//    链式调用中，每个环节都要处理异常（比如thenApplyAsync()后也要加异常处理）。
//    坑 4：无脑使用 get ()/join () 阻塞 —— 线程池耗尽、死锁
//    现象 / 问题
//    Tomcat/Netty 等容器线程池被耗尽，服务无法处理新请求；
//    出现死锁：commonPool线程调用join()等待另一个commonPool任务，导致所有线程被阻塞；
//    调用get()无超时，任务卡死导致调用线程永久阻塞。
//    底层原因
//    get()：无参版本是无限阻塞，直到任务完成 / 失败；
//    join()：和get()类似，但不抛检查异常，且如果在commonPool线程中调用，极易导致死锁；
//    若在 Tomcat 的业务线程中调用get()阻塞，会占用容器线程，高并发下直接拖垮服务。
//    解决方案
//✅ 核心原则：get () 必须加超时，避免在异步线程中嵌套阻塞调用，优先使用非阻塞链式调用。
//    方案 1：get () 强制加超时（生产必备）
//    java
//            运行
//    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//        // 模拟慢任务
//        try { Thread.sleep(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
//        return "结果";
//    }, CompletableFutureThreadPoolConfig.IO_EXECUTOR);
//
//// 错误：无超时get()
//// String result = future.get();
//
//// 正确：加超时（根据业务场景设置，比如1秒）
//try {
//        String result = future.get(1, TimeUnit.SECONDS);
//    } catch (TimeoutException e) {
//        // 超时处理：取消任务+记录日志+告警
//        future.cancel(true); // 取消任务
//        System.err.println("异步任务超时（1秒），已取消", e);
//        // 降级处理（比如返回缓存数据）
//    } catch (InterruptedException | ExecutionException e) {
//        // 其他异常处理
//        System.err.println("异步任务执行失败", e);
//    }
//    方案 2：避免在异步线程中调用 join ()/get ()
//    java
//            运行
//// 危险：在commonPool线程中嵌套join()，极易死锁
//CompletableFuture.supplyAsync(() -> {
//        // 内部又创建一个CompletableFuture
//        CompletableFuture<String> innerFuture = CompletableFuture.supplyAsync(() -> "内部结果");
//        // 调用join()，若外部线程是commonPool，内部任务也用commonPool，会导致死锁
//        String innerResult = innerFuture.join(); // 坑！
//        return innerResult;
//    });
//
//    // 正确：改用非阻塞链式调用（thenComposeAsync）
//    CompletableFuture<String> outerFuture = CompletableFuture.supplyAsync(() -> {
//                return "外部结果";
//            }, CompletableFutureThreadPoolConfig.IO_EXECUTOR)
//// 非阻塞嵌套异步任务
//            .thenComposeAsync(outerResult -> {
//                // 内部任务也用自定义线程池
//                return CompletableFuture.supplyAsync(() -> outerResult + "-内部结果",
//                        CompletableFutureThreadPoolConfig.IO_EXECUTOR);
//            }, CompletableFutureThreadPoolConfig.IO_EXECUTOR)
//// 异常处理
//            .whenComplete((result, ex) -> {
//                if (ex != null) {
//                    System.err.println("链式任务失败", ex);
//                }
//            });
//
//
//
//
//
//
//
//    坑 6：忽略拒绝策略 —— 任务丢失、无告警
//    现象 / 问题
//    高并发下部分任务 “凭空消失”，业务流程中断，但无任何报错日志；
//    监控中看到请求量远大于处理量，但线程池无异常。
//    底层原因
//    自定义线程池时，若队列满 + 线程数达最大，默认拒绝策略是AbortPolicy（抛出RejectedExecutionException）；
//    CompletableFuture的supplyAsync()会捕获这个异常，并封装为CompletionException，若未处理则任务丢失；
//    若使用DiscardPolicy（直接丢弃任务），则完全无感知。
//    解决方案
//✅ 核心原则：自定义拒绝策略，必须包含 “日志记录 + 告警 + 兜底执行”，避免任务静默丢失。
//    参考 “坑 1” 中的线程池配置，拒绝策略使用CallerRunsPolicy（兜底让调用线程执行），并在拒绝时记录日志、触发告警：
//    java
//            运行
//    // 自定义拒绝策略（生产级）
//    RejectedExecutionHandler rejectHandler = new ThreadPoolExecutor.CallerRunsPolicy() {
//        @Override
//        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
//            // 1. 记录详细日志（线程池状态+任务信息）
//            String threadPoolStatus = String.format(
//                    "核心数：%d，最大数：%d，活跃数：%d，队列数：%d，已完成任务数：%d",
//                    executor.getCorePoolSize(),
//                    executor.getMaximumPoolSize(),
//                    executor.getActiveCount(),
//                    executor.getQueue().size(),
//                    executor.getCompletedTaskCount()
//            );
//            System.err.println("任务被拒绝！线程池状态：" + threadPoolStatus,
//                    new RejectedExecutionException("任务拒绝：" + r.toString()));
//            // 2. 触发告警（生产中接入监控平台）
//            // alertService.sendAlert("CompletableFuture线程池任务拒绝！状态：" + threadPoolStatus);
//            // 3. 兜底执行（CallerRunsPolicy让调用线程执行，避免任务丢失）
//            super.rejectedExecution(r, executor);
//        }
//    };
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
//
//    坑 7：未取消任务 / 内存泄漏 —— 资源浪费、OOM
//    现象 / 问题
//    异步任务执行缓慢 / 卡死，CompletableFuture对象一直占用内存，最终导致 OOM；
//    线程池线程被无效任务占用，无法处理新请求；
//    业务流程中断（如用户取消订单），但异步任务仍在执行，浪费资源。
//    底层原因
//    CompletableFuture没有自动取消机制，只要任务提交，就会一直执行直到完成 / 失败；
//    若任务执行时间过长，CompletableFuture对象及其引用的资源（如连接、缓存）无法被 GC 回收；
//    cancel(true)方法若调用不及时，线程池资源会被无效占用。
//    解决方案
//✅ 核心原则：任务超时 / 业务中断时主动取消，避免无用任务占用资源。
//    java
//            运行
//    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//        // 模拟长时间执行的任务
//        try { Thread.sleep(10000); } catch (InterruptedException e) {
//            // 取消时会抛出InterruptedException，需处理
//            System.out.println("任务被取消，线程中断");
//            return null;
//        }
//        return "结果";
//    }, CompletableFutureThreadPoolConfig.IO_EXECUTOR);
//
//// 场景1：超时取消
//try {
//        String result = future.get(1, TimeUnit.SECONDS);
//    } catch (TimeoutException e) {
//        // 超时后主动取消任务（参数true表示中断线程）
//        boolean isCancelled = future.cancel(true);
//        System.err.println("任务超时，已取消：" + isCancelled, e);
//    } catch (Exception e) {
//        System.err.println("任务执行失败", e);
//    }
//
//    // 场景2：业务中断取消（如用户取消请求）
//    public void cancelTask(CompletableFuture<String> future) {
//        if (!future.isDone()) {
//            future.cancel(true);
//            System.out.println("业务中断，主动取消任务");
//        }
//    }










//    坑 8：上下文丢失 —— 日志无 traceId、ThreadLocal 失效
//    现象 / 问题
//    分布式追踪中，异步任务的日志丢失 traceId，无法追踪请求链路；
//    ThreadLocal中的用户信息、请求上下文在异步任务中获取不到，导致权限校验失败、数据错误。
//    底层原因
//    CompletableFuture切换线程池时，ThreadLocal的上下文无法自动传递；
//    默认commonPool和自定义线程池都不会处理上下文传递，导致链路追踪、权限信息丢失。
//    解决方案
//✅ 核心原则：使用支持上下文传递的 ThreadLocal（如 TTL），自定义线程工厂传递上下文。
//
//
//
//    步骤 1：引入 TTL 依赖（阿里开源，解决 ThreadLocal 传递问题）
//    xml
//            <!-- Maven依赖 -->
//<dependency>
//    <groupId>com.alibaba</groupId>
//    <artifactId>transmittable-thread-local</artifactId>
//    <version>2.14.2</version>
//</dependency>
//    步骤 2：使用 TTL 封装 ThreadLocal
//    java
//            运行
//    // 替换原生ThreadLocal为TTL
//    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
//
//    // 设置traceId（入口处，如过滤器）
//    public void setTraceId(String traceId) {
//        TRACE_ID.set(traceId);
//    }





}

