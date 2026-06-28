package com.zhh.handsome.CompetableFuture;

import java.util.concurrent.*;

public class Demo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "hello";
        });
        System.out.println("start, waiting for result...");
//        future.thenApply(s -> s + " world").thenAccept(System.out::println)
        ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100));
        Future<?> taskDone = executor.submit(() -> {
            System.out.println("task done");
        });
        System.out.println(taskDone.get());
        future.thenApply(s -> s + " world").thenAccept(System.out::println).thenAccept(s->{
            executor.shutdown();
        });
        System.out.println("end");
    }
  /*  一、CompletableFuture 的核心作用
    在并发编程中，我们常需要执行异步任务（如网络请求、文件 IO 等），并在任务完成后自动触发后续操作（如处理结果、组合多个任务结果等）。CompletableFuture 的核心作用是：
    异步执行任务：无需手动创建线程，可直接提交任务到线程池异步执行。
    非阻塞回调：任务完成后自动触发后续操作（回调），无需阻塞等待结果。
    任务组合：支持多任务的串行、并行或依赖关系组合（如 "A 完成后执行 B，B 和 C 都完成后执行 D"）。
    异常处理：提供专门的 API 处理异步任务中的异常，避免异常被线程池吞噬。
    二、底层原理
    CompletableFuture 的底层实现依赖状态管理、回调链和线程池三个核心机制，我们逐一拆解：
            1. 状态管理：用 volatile 和 CAS 保证线程安全
    CompletableFuture 的核心是维护一个 "完成状态"，用于标识任务是否执行完成（正常完成 / 异常完成）。其内部通过两个 volatile 变量存储状态和结果：
    volatile Object result;：存储任务结果（正常完成时为结果值，异常完成时为 Throwable 对象）。
    volatile int status;：存储状态，核心状态包括：
    NEW（0）：任务未完成；
    COMPLETING（1）：任务即将完成（中间状态，避免并发冲突）；
    NORMAL（2）：任务正常完成；
    EXCEPTIONAL（3）：任务异常完成；
    其他状态（如被取消等）。
    状态转换通过CAS 操作（Compare-And-Swap）保证线程安全：当任务完成时，会先将状态从NEW转为COMPLETING，写入结果后再转为NORMAL或EXCEPTIONAL。
            2. 回调链：用 "Completion" 对象串联任务
    CompletableFuture 的 "链式调用"（如thenApply→thenAccept）依赖内部的回调链机制。每个回调操作（如thenApply）会创建一个Completion子类对象（如UniApply），并将其 "关联" 到当前 CompletableFuture 上。
    当当前任务完成（状态变为NORMAL或EXCEPTIONAL）时，会触发回调链的执行：遍历所有关联的Completion对象，根据当前任务的结果（或异常）执行对应的回调逻辑，并将结果传递给下一个 CompletableFuture。


            CompletableFuture.supplyAsync(() -> "hello")  // 任务1
            .thenApply(s -> s + " world")  // 回调1：创建UniApply对象关联到任务1
            .thenAccept(s -> System.out.println(s));  // 回调2：创建UniAccept对象关联到回调1的结果
    任务 1 完成后，会触发 "回调 1" 执行，回调 1 的结果会触发 "回调 2" 执行，形成链式传递。
            3. 线程池：控制任务执行的载体
    CompletableFuture 的异步任务和回调默认依赖ForkJoinPool.commonPool()（一个公共的线程池），也可通过 API 指定自定义线程池（如supplyAsync(Supplier, Executor)）。
    异步任务（如supplyAsync）的执行线程：由指定的线程池提供。
    回调任务（如thenApply）的执行线程：
    若当前任务已完成，回调会由当前线程（调用者线程）直接执行；
    若当前任务未完成，回调会由执行当前任务的线程（线程池中的线程）执行；
    若通过thenApplyAsync（带Async后缀的方法），回调会提交到指定线程池执行（默认用 commonPool）。

    三、核心 API 分类及用法
    CompletableFuture 的 API 多达数十个，按功能可分为创建任务、转换结果、组合任务、异常处理、等待完成五大类，我们逐个解析：
            1. 创建 CompletableFuture（初始化任务）
    用于创建一个异步执行的 CompletableFuture，核心方法：


    supplyAsync(Supplier<U> supplier)	异步执行有返回值的任务（用 commonPool）	CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "result");
    supplyAsync(Supplier<U> supplier, Executor executor)	异步执行有返回值的任务（用自定义线程池）	CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "result", executor);
    runAsync(Runnable runnable)	异步执行无返回值的任务（用 commonPool）	CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> System.out.println("run"));
    runAsync(Runnable runnable, Executor executor)	异步执行无返回值的任务（用自定义线程池）	CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> System.out.println("run"), executor);
    completedFuture(U value)	创建一个已完成的 CompletableFuture（用于测试或快速初始化）	CompletableFuture<String> cf = CompletableFuture.completedFuture("done");
2.


    转换结果（链式处理任务）
    用于在当前任务完成后，对结果进行处理并传递给下一个任务（链式调用的核心），核心方法：



    方法	功能	特点
    thenApply(Function<? super T,? extends U> fn)	用当前结果执行函数，返回新结果	回调在当前线程或任务线程执行
    thenApplyAsync(Function<? super T,? extends U> fn)	异步执行函数（用 commonPool）	回调在新线程执行
    thenAccept(Consumer<? super T> action)	消费当前结果（无返回值）	用于最终处理（如打印）
    thenRun(Runnable action)	任务完成后执行 Runnable（不关心结果）	仅做后续通知（如日志）
    示例：
    java
            运行
CompletableFuture.supplyAsync(() -> 10)  // 任务1：返回10
        .thenApply(num -> num * 2)  // 处理1：10→20
            .thenAccept(num -> System.out.println(num))  // 处理2：打印20
            .thenRun(() -> System.out.println("done"));  // 处理3：打印"done"





    3. 组合任务（多任务协作）
    用于组合多个 CompletableFuture 的结果（串行、并行或依赖关系），核心方法：
    方法	功能	示例场景
    thenCompose(Function<? super T,? extends CompletionStage<U>> fn)	串行组合：当前任务完成后，用其结果创建新的 CompletableFuture 并执行	先查用户 ID，再用 ID 查用户详情
    thenCombine(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn)	并行组合：当前任务和 other 任务都完成后，用两者结果计算新值	同时调用两个接口，合并返回结果
    allOf(CompletableFuture<?>... cfs)	等待所有任务完成（返回 Void）	并行下载 3 个文件，全部完成后合并
    anyOf(CompletableFuture<?>... cfs)	等待任意一个任务完成（返回第一个结果）	调用多个镜像接口，取最快返回的结果
    示例 1：串行组合（thenCompose）
    java
            运行
    // 任务1：获取用户ID
    CompletableFuture<String> getUserId = CompletableFuture.supplyAsync(() -> "123");
    // 任务2：用ID获取用户详情（依赖任务1的结果）
    CompletableFuture<String> getUserInfo = getUserId.thenCompose(userId ->
            CompletableFuture.supplyAsync(() -> "info of " + userId)
    );
    示例 2：并行组合（thenCombine）
    java
            运行
    // 任务1：查商品价格
    CompletableFuture<Double> price = CompletableFuture.supplyAsync(() -> 99.9);
    // 任务2：查商品库存
    CompletableFuture<Integer> stock = CompletableFuture.supplyAsync(() -> 100);
    // 合并结果：价格*库存=总价值
    CompletableFuture<Double> total = price.thenCombine(stock, (p, s) -> p * s);
4. 异常处理
    异步任务可能抛出异常，CompletableFuture 提供专门的 API 捕获和处理异常：
    方法	功能	特点
    exceptionally(Function<Throwable,? extends T> fn)	当任务异常时，用异常生成一个默认结果	仅处理异常场景（正常完成不执行）
    handle(BiFunction<? super T, Throwable,? extends U> fn)	无论正常或异常，都用结果（或异常）生成新值	同时处理正常和异常场景
    示例：
    java
            运行
CompletableFuture.supplyAsync(() -> {
        if (true) throw new RuntimeException("error");
        return "success";
    })
            .exceptionally(ex -> "default when error: " + ex.getMessage())  // 异常时返回默认值
            .thenAccept(result -> System.out.println(result));  // 输出：default when error: error
5. 等待任务完成（获取结果）
    虽然 CompletableFuture 推荐用回调处理结果，但也支持主动获取结果（类似传统 Future）：
    方法	功能	特点
    get()	阻塞等待结果（可能抛出 InterruptedException/ExecutionException）	需处理 checked 异常
    getNow(T valueIfAbsent)	立即获取结果：已完成则返回结果，否则返回默认值	非阻塞
    join()	阻塞等待结果（异常时抛出 unchecked 异常）	无需处理 checked 异常，适合链式调用
    示例：
    java
            运行
    CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "result");
    String result = cf.join();  // 阻塞获取结果（无需处理checked异常）
    四、与异步回调的关系
    异步回调是指 "任务完成后自动执行预设逻辑" 的机制（避免阻塞等待）。传统的异步回调（如 Java 的Future+ 手动轮询、JavaScript 的回调函数）存在两大问题：
    回调地狱：多任务依赖时，回调嵌套层级过深，代码可读性极差。例如："任务 A→任务 B（依赖 A）→任务 C（依赖 B）" 需要三层嵌套。
    异常处理困难：传统回调的异常无法通过常规try-catch捕获，容易被线程池吞噬。
    CompletableFuture 通过链式调用和内置异常处理 API完美解决了这些问题：
    消除回调地狱：用thenApply、thenCompose等方法将嵌套回调转为链式调用，代码线性展开。例如：
    java
            运行
// 传统回调嵌套（伪代码）
a.async(() -> {
        b.async(aResult -> {
            c.async(bResult -> { ... });
        });
    });

// CompletableFuture链式调用
a.thenCompose(aResult -> b)
            .thenCompose(bResult -> c)
            .thenAccept(cResult -> { ... });
    统一异常处理：通过exceptionally、handle等 API 集中处理整个调用链中的异常，无需在每个回调中单独处理。
    总结
    CompletableFuture 是 Java 异步编程的 "瑞士军刀"，其核心价值在于：
    底层通过状态管理（CAS）和回调链（Completion 对象）实现非阻塞的任务串联与组合；
    提供丰富的 API 支持任务创建、结果转换、多任务协作和异常处理；
    彻底解决了传统异步回调的 "回调地狱" 和异常处理难题，让异步代码更简洁、易维护。
    在实际开发中，CompletableFuture 广泛用于并行任务处理（如批量接口调用）、异步 IO（如文件读写、网络请求）等场景，是提升系统并发能力的核心工具。*/
}

