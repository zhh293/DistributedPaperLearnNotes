package org.example.ClassDemo1.函数式编程;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public class CompletableDemo1 {
    CompletableFuture<String> completableFuture;
    public CompletableDemo1() {
        completableFuture = new CompletableFuture<>();
    }
    public CompletableFuture<String> getCompletableFuture() {
        return completableFuture;
    }
    public void handle(){
        completableFuture.thenApply(new Function<String, Object>() {
            @Override
            public Object apply(String s){
                return s.toLowerCase();
            }
        }).thenAccept(new Consumer<Object>() {
            @Override
            public void accept(Object o) {
                System.out.println(o);
            }
        });
    }



}

    /*二、核心 API 分类与用法
1. 任务创建：runAsync() vs supplyAsync()
    方法	作用	场景
    runAsync(Runnable task)	执行无返回值的异步任务，默认使用 ForkJoinPool.commonPool()	纯副作用操作（如日志）
    runAsync(Runnable task, Executor executor)	指定线程池执行无返回值任务	需隔离线程池的场景
    supplyAsync(Supplier<T> task)	执行有返回值的异步任务，默认使用 ForkJoinPool.commonPool()	需返回结果的操作（如查询）
    supplyAsync(Supplier<T> task, Executor executor)	指定线程池执行有返回值任务	需隔离线程池且返回结果

    示例：

    java
    // 无返回值任务
    CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> System.out.println("Hello"));

    // 有返回值任务（默认线程池）
    CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> "World");

    // 自定义线程池（推荐）
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CompletableFuture<Integer> f3 = CompletableFuture.supplyAsync(() -> 100, pool);
2. 链式操作：结果转换与消费
    CompletableFuture 的链式方法允许将多个异步任务串联，自动传递结果。

    方法家族	作用	返回值类型
    thenApply(Function<T, U> fn)	任务完成后，将结果转换为新类型（同线程池）	CompletableFuture<U>
    thenApplyAsync(Function<T, U> fn)	任务完成后，在新线程（默认或自定义线程池）转换结果	CompletableFuture<U>
    thenAccept(Consumer<T> action)	任务完成后，消费结果（无返回值）	CompletableFuture<Void>
    thenRun(Runnable action)	任务完成后，执行一段无参代码（无返回值）	CompletableFuture<Void>
    thenCompose(Function<T, CompletionStage<U>> fn)	任务完成后，串联另一个 CompletableFuture（解决回调地狱）	CompletableFuture<U>
    thenCombine(CompletionStage<U> other, BiFunction<T, U, V> fn)	等待两个任务完成后，合并结果	CompletableFuture<V>

    示例：

    java
    // thenApply：转换结果
    CompletableFuture<Integer> f = CompletableFuture.supplyAsync(() -> 10)
            .thenApply(i -> i * 2); // 结果：20

    // thenCompose：串联任务（避免嵌套）
    CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "Hello")
            .thenCompose(s -> CompletableFuture.supplyAsync(() -> s + " World")); // 结果：Hello World

    // thenCombine：合并两个任务结果
    CompletableFuture<Integer> f2 = CompletableFuture.supplyAsync(() -> 100);
    CompletableFuture<Integer> f3 = CompletableFuture.supplyAsync(() -> 200);
    CompletableFuture<Integer> sum = f2.thenCombine(f3, (a, b) -> a + b); // 结果：300
3. 组合操作：allOf() 与 anyOf()
    用于等待多个任务全部完成或任意一个完成。

    方法	作用	返回值类型
    allOf(CompletableFuture<?>... cfs)	等待所有任务完成（无返回值，需手动获取各任务结果）	CompletableFuture<Void>
    anyOf(CompletableFuture<?>... cfs)	等待任意一个任务完成（返回最先完成的结果，类型为 Object）	CompletableFuture<Object>

    示例：

    java
    // 并行下载多个文件，全部完成后统计总数
    CompletableFuture<Void> download1 = CompletableFuture.runAsync(() -> downloadFile("a.txt"));
    CompletableFuture<Void> download2 = CompletableFuture.runAsync(() -> downloadFile("b.txt"));
    CompletableFuture<Void> allDone = CompletableFuture.allOf(download1, download2);
allDone.thenRun(() -> System.out.println("所有文件下载完成"));

    // 多任务竞速，取最快结果
    CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "结果1");
    CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> "结果2");
    CompletableFuture<Object> firstDone = CompletableFuture.anyOf(f1, f2);
firstDone.thenAccept(result -> System.out.println("最快结果：" + result));
4. 异常处理：exceptionally() 与 handle()
    方法	作用	返回值类型
    exceptionally(Function<Throwable, T> fn)	任务异常时，返回默认值（仅处理异常）	CompletableFuture<T>
    handle(BiFunction<T, Throwable, U> fn)	无论成功 / 失败都执行，可同时处理结果和异常	CompletableFuture<U>

    示例：

    java
    // exceptionally：异常时返回默认值
    CompletableFuture<Integer> f = CompletableFuture.supplyAsync(() -> {
        throw new RuntimeException("出错了");
    }).exceptionally(ex -> {
        System.out.println("捕获异常：" + ex.getMessage());
        return -1; // 默认值
    }); // 结果：-1

    // handle：无论成功/失败都处理
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> "正常结果")
            .handle((result, ex) -> {
                if (ex != null) return "异常：" + ex.getMessage();
                return "成功：" + result;
            }); // 结果：成功：正常结果
    三、实战场景与最佳实践
1. 非阻塞 I/O 操作
    场景：异步读取文件、发送 HTTP 请求，避免主线程阻塞。

    java
    // 异步读取文件内容
    CompletableFuture<String> fileContent = CompletableFuture.supplyAsync(() -> {
        try {
            return new String(Files.readAllBytes(Paths.get("data.txt")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    });

// 主线程可继续做其他事，最后获取结果
fileContent.thenAccept(content -> System.out.println("文件内容：" + content));
2. 并行计算与结果聚合
    场景：多个独立任务并行执行，最终合并结果（如微服务多接口聚合）。

    java
    // 并行调用三个服务
    CompletableFuture<User> userFuture = CompletableFuture.supplyAsync(() -> getUser(1L));
    CompletableFuture<Order> orderFuture = CompletableFuture.supplyAsync(() -> getOrder(101L));
    CompletableFuture<Payment> paymentFuture = CompletableFuture.supplyAsync(() -> getPayment(201L));

    // 等待所有完成后聚合结果
    CompletableFuture<Void> allDone = CompletableFuture.allOf(userFuture, orderFuture, paymentFuture);
allDone.thenRun(() -> {
        User user = userFuture.join();
        Order order = orderFuture.join();
        Payment payment = paymentFuture.join();
        System.out.println("聚合结果：" + user + order + payment);
    });
3. 线程池配置最佳实践
    默认线程池 ForkJoinPool.commonPool() 易引发资源竞争（如与其他并行流共享线程），建议自定义线程池：

    java
    // 计算线程池大小（IO 密集型：CPU 核心数 × 2；CPU 密集型：CPU 核心数 + 1）
    int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
    ExecutorService customPool = new ThreadPoolExecutor(
            corePoolSize,
            corePoolSize * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // 所有异步任务显式绑定线程池
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> "任务1", customPool)
            .thenApplyAsync(s -> s + "任务2", customPool); // 链式调用也传递线程池
    四、注意事项与陷阱
    避免默认线程池：ForkJoinPool.commonPool() 是全局共享线程池，若任务耗时较长（如 I/O），易导致其他并行任务（如 Stream.parallel()）被阻塞。
    链式调用传递线程池：thenApplyAsync()/thenAcceptAsync() 需显式传递线程池，否则可能复用默认线程池，引发资源竞争。
    防止线程泄漏：长期运行的任务需确保线程池被正确关闭（如 customPool.shutdown()），或使用 Spring 的 ThreadPoolTaskExecutor 由容器管理。
    超时控制：使用 orTimeout()/completeOnTimeout() 防止任务长时间阻塞，示例：
    java
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
        // 模拟慢任务
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        return "结果";
    }).orTimeout(3, TimeUnit.SECONDS); // 3秒超时*/








        /*
    // 改进后
    CompletableFuture
            .supplyAsync(() -> monthlySalesReport())
            .thenAccept(map -> {
                for (Map.Entry<YearMonth, Long> e : map.entrySet()) {
                    logger.info(e.toString());
                }
            });
}

private static Map<YearMonth, Long> monthlySalesReport() {
    try (Stream<String> lines = Files.lines(Path.of("./data.txt"))) {
        Map<YearMonth, Long> map = lines.skip(1)
                .map(line -> line.split(","))
                .collect(groupingBy(array -> YearMonth.from(formatter.parse(array[TIME])), TreeMap::new, counting()));
        return map;
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}





}
*/