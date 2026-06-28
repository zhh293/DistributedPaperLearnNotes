package Netty;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class FuturePromise {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup(4);
        EventLoop next = eventExecutors.next();
        Future<?> future = next.submit(()->{
            try {
                log.debug("nihaoayudghjkas");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("hahah");
        });
        System.out.println(future.get());
        future.addListener((future1)->{
            if(future1.isSuccess()){
                System.out.println(future1.getNow());
            }
            if(future1.isCancelled()){
                System.out.println("任务取消");
            }
        });
        eventExecutors.shutdownGracefully();






        /*在异步处理时，经常用到这两个接口


        首先要说明 netty 中的 Future 与jdk 中的 Future 同名，但是是两个接口，netty的 Future 继承自 jdk 的

        Future，而 Promise 又对 netty Future 进行了扩展


        jdk Future 只能同步等待任务结束【或成功、或失败)才能得到结果


。netty Future 可以同步等待任务结束得到结果，也可以异步方式得到结果，但都是要等任务结束

        netty Promise 不仅有 netty Future 的功能，而且脱离了任务独立存在，只作为两个线程间传递结果的容器*/



        //jdkFuture
       /* ExecutorService executorService=Executors.newFixedThreadPool(2);

        Future<?> future= executorService.submit(()->{
            try {
                log.debug("nihaoayudghjkas");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("hahah");
        });
        System.out.println(future.get());*/

        /*NioEventLoopGroup group=new NioEventLoopGroup();
        NioEventLoop next = (NioEventLoop) group.next();
        Future<Integer>future =next.submit(()->{
            try {
                log.debug("nihaoayudghjkas");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("hahah");
        });
      // System.out.println(future.get());
        future.addListener((future1)->{
            if(future1.isSuccess()){
                System.out.println(future1.getNow());
            }
            if(future1.isCancelled()){
                System.out.println("任务取消");
            }
        });*/



    }
}
/*
JDK Future
用法
JDK Future 是 Java 5 引入的接口，用于表示异步计算的结果。你可以通过它检查计算是否完成、等待完成并获取结果。

java
import java.util.concurrent.*;

public class JdkFutureExample {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 提交任务获取 Future
        Future<Integer> future = executor.submit(() -> {
            Thread.sleep(2000);
            return 42;
        });

        // 检查任务是否完成
        System.out.println("任务完成? " + future.isDone());

        // 获取结果（阻塞）
        Integer result = future.get();
        System.out.println("结果: " + result);

        // 带超时的获取
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("获取超时");
        }

        executor.shutdown();
    }
}
细节
阻塞特性：get() 方法会阻塞直到任务完成。
取消任务：可通过 future.cancel(true) 尝试取消任务。
局限性：缺乏非阻塞 API、无法链式调用、不支持回调。
Netty Future
用法
Netty Future 是对 JDK Future 的增强，支持异步操作完成时的回调。

java
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
        import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class NettyFutureExample {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 初始化通道
                        }
                    });

            // 连接操作返回 ChannelFuture
            ChannelFuture connectFuture = b.connect("example.com", 80);

            // 添加监听器（非阻塞）
            connectFuture.addListener(future -> {
                if (future.isSuccess()) {
                    System.out.println("连接成功");
                } else {
                    System.out.println("连接失败: " + future.cause());
                }
            });

            // 或者阻塞等待
            connectFuture.sync();

        } finally {
            group.shutdownGracefully();
        }
    }
}
细节
异步非阻塞：通过 addListener() 添加回调，不阻塞当前线程。
可组合性：多个操作可通过链式调用组合。
继承关系：ChannelFuture 是 Netty 特有的接口，继承自 Future。
Promise
        用法
Netty Promise 是可完成的 Future，允许外部设置操作结果。

java
import io.netty.channel.*;
        import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

public class PromiseExample {
    public static void main(String[] args) {
        EventExecutor executor = new DefaultEventExecutor();
        Promise<String> promise = executor.newPromise();

        // 添加监听器
        promise.addListener(future -> {
            if (future.isSuccess()) {
                System.out.println("结果: " + promise.get());
            } else {
                System.out.println("失败: " + future.cause());
            }
        });

        // 模拟异步操作
        executor.execute(() -> {
            try {
                Thread.sleep(1000);
                promise.setSuccess("操作完成"); // 设置成功结果
                // promise.setFailure(new Exception("模拟失败")); // 设置失败结果
            } catch (InterruptedException e) {
                promise.setFailure(e);
            }
        });
    }
}


细节
可写操作：与普通 Future 不同，Promise 可通过 setSuccess() 或 setFailure() 设置结果。
线程安全：Promise 的状态变更操作是线程安全的。
使用场景：适合需要将异步操作结果传递给其他组件的场景。*/
