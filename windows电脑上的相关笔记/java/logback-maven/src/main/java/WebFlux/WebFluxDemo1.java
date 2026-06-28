package WebFlux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;

@PropertySource("classpath:application.properties")
@SpringBootApplication
public class WebFluxDemo1 {
    public static void main(String[] args) {

        SpringApplication.run(WebFluxDemo1.class,args);
    }
}
/*
哦哦哦哦，也就是说查询数据库的操作跟线程是分离的，这个线程执行到查询操作之后就跟它没有关系了，剩下的就是数据库本身的线程操作了，这段时间线程可以去干其他的事？
完全正确！你已经抓住了响应式编程的核心要点。我用一个生活中的例子帮你巩固理解：
一、用餐厅点餐理解线程分离
传统阻塞模式（Spring MVC）
场景：顾客（请求）向服务员（线程）点餐，服务员必须站在厨房门口等待厨师（数据库）做好菜，期间不能接待其他顾客。
问题：如果有 100 个顾客同时点餐，需要 100 个服务员（线程），餐厅成本高。
响应式非阻塞模式（WebFlux）
场景：顾客点餐，服务员记录后立即返回接待其他顾客。厨师做好菜后，通过广播（回调）通知服务员上菜。
优势：1 个服务员可以同时服务 100 个顾客，餐厅效率大幅提升。
二、技术细节再梳理
1. 线程分离的关键
HTTP 请求线程：处理请求的线程（如reactor-http-nio-1）
发起数据库查询后立即返回，继续处理其他请求
数据库操作线程：响应式驱动内部的线程池（如 R2DBC 的 Netty 线程）
负责实际的 SQL 执行和网络通信
数据就绪后通过回调通知 Reactor 框架
2. 执行流程对比
步骤	传统阻塞模式	响应式非阻塞模式
1. 接收 HTTP 请求	线程 A 接收请求	线程 A 接收请求
2. 发起数据库查询	线程 A 阻塞等待结果	线程 A 发起查询后立即返回
3. 数据库操作	数据库线程执行查询	数据库线程执行查询
4. 返回结果	线程 A 继续处理结果	驱动通知 Reactor 工作线程 B 处理结果
5. 返回 HTTP 响应	线程 A 返回响应	线程 B 返回响应
三、关键验证代码
通过以下代码可以清晰观察线程分离：

java
@GetMapping("/test")
public Mono<String> testReactiveThreading() {
    log.info("请求处理线程: {}", Thread.currentThread().getName());

    return Mono.just("开始数据库查询")
            .flatMap(s -> {
                // 模拟数据库查询（实际应调用响应式数据库驱动）
                return Mono.fromCallable(() -> {
                            log.info("数据库操作线程: {}", Thread.currentThread().getName());
                            Thread.sleep(1000); // 模拟查询耗时
                            return "查询结果";
                        })
                        .subscribeOn(Schedulers.boundedElastic()); // 使用响应式调度器
            })
            .doOnNext(result -> log.info("结果处理线程: {}", Thread.currentThread().getName()));
}

典型日志输出：

plaintext
2025-06-14 11:00:00 [reactor-http-nio-1] INFO  请求处理线程: reactor-http-nio-1
        2025-06-14 11:00:00 [boundedElastic-1] INFO  数据库操作线程: boundedElastic-1
        2025-06-14 11:00:01 [parallel-2] INFO  结果处理线程: parallel-2
四、常见误区澄清
误区 1：“响应式完全不需要线程”
真相：响应式仍然需要线程执行实际操作，但通过异步 IO 和回调机制，大幅减少了线程等待时间。
误区 2：“只有数据库操作能异步”
真相：任何 IO 操作都可以异步化，包括：
文件读写
远程服务调用（如 REST API、消息队列）
缓存操作（如 Redis）
误区 3：“响应式一定比阻塞快”
真相：在 IO 密集型、高并发场景下，响应式性能显著优于阻塞；但在低并发或 CPU 密集型场景下，传统模式可能更简单直接。
五、何时应该使用响应式？
推荐在以下场景使用 WebFlux + 响应式编程：

高并发场景：如微服务网关、API 聚合层
IO 密集型操作：大量数据库查询、远程服务调用
实时数据流处理：如 WebSocket、Server-Sent Events
资源受限环境：需要用有限的资源处理大量请求*/





/*
Reactor 响应式类型
Mono<T>：0-1 个元素的异步序列
        java
Mono.just("data");           // 创建包含单个元素的Mono
Mono.empty();                // 空Mono
Mono.error(new Exception()); // 包含错误的Mono

Flux<T>：0-N 个元素的异步序列
java
Flux.just("a", "b", "c");    // 创建包含多个元素的Flux
Flux.range(1, 10);           // 生成1到10的整数序列
Flux.interval(Duration.ofSeconds(1)); // 每秒生成一个递增数字

        2. 调度器（Schedulers）
控制异步操作的执行上下文：

        java
        .subscribeOn(Schedulers.boundedElastic()); // 在弹性线程池执行
        .publishOn(Schedulers.parallel());         // 在并行线程池发布
二、函数式端点（RouterFunction）
        1. 路由定义
        java
@Configuration
public class RouterConfig {

    @Bean
    public RouterFunction<ServerResponse> route(UserHandler handler) {
        return RouterFunctions.route()
                .GET("/users", handler::getAllUsers)
                .GET("/users/{id}", handler::getUserById)
                .POST("/users", handler::createUser)
                .PUT("/users/{id}", handler::updateUser)
                .DELETE("/users/{id}", handler::deleteUser)
                .build();
    }
}
2. 处理器（Handler）
java
@Component
public class UserHandler {

    private final UserService userService;

    public UserHandler(UserService userService) {
        this.userService = userService;
    }

    public Mono<ServerResponse> getUserById(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return userService.getUserById(id)
                .flatMap(user -> ServerResponse.ok().bodyValue(user))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> createUser(ServerRequest request) {
        Mono<User> userMono = request.bodyToMono(User.class);
        return userService.saveUser(userMono)
                .flatMap(user -> ServerResponse.created(URI.create("/users/" + user.getId()))
                        .bodyValue(user));
    }
}*/
