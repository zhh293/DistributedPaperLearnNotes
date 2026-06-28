package Netty;

public class ByteBufDemo2 {
/*    一、引用计数的本质：共享资源的「使用许可证」
    核心思想：每个 ByteBuf 实例都有一个计数器（初始值为 1），表示有多少个对象正在使用它。当计数器归零时，资源被释放。
    类比理解
    假设你从图书馆借了一本书（ByteBuf）：

    retain()：相当于复印了这本书的使用权给朋友，两人同时使用（计数 + 1）。
    release()：相当于归还这本书的使用权。当所有人都归还后（计数 = 0），书被放回书架（内存池）。
    池化的关系：如果书是从图书馆（内存池）借的，归还后可以被其他人复用；如果是自己买的书（非池化），归还后直接扔掉。
    二、为什么需要引用计数？
    在高性能网络编程中，频繁创建和销毁 ByteBuf 会导致严重的性能问题：

    池化 ByteBuf：从内存池分配，必须手动回收才能复用。
    非池化 ByteBuf：虽然由 GC 回收，但频繁分配会触发 GC，影响吞吐量。

    引用计数通过精确控制资源生命周期，避免了这些问题。
    retain()：增加引用计数
java
ByteBuf buf = ctx.alloc().buffer(1024); // 初始计数=1
buf.retain(); // 计数+1 → 2

使用场景：

当需要将 ByteBuf 传递给多个处理器或线程时。
在异步操作中，确保 ByteBuf 在操作完成前不会被释放。
2. release()：减少引用计数
java
if (buf.release()) { // 计数-1 → 0，返回true
    // 资源已释放，不能再使用buf
} else {
    // 计数>0，继续使用
}

使用场景：

处理完 ByteBuf 后，必须调用release()。
在finally块中调用，确保资源释放。
3. refCnt()：获取当前引用计数
java
int count = buf.refCnt(); // 查看当前计数

调试用途：

检查引用计数是否异常（例如泄漏时计数始终 > 0）。
四、与池化的深度关系
1. 池化 ByteBuf 的生命周期
java
// 从内存池分配，初始计数=1
ByteBuf pooledBuf = PooledByteBufAllocator.DEFAULT.buffer(1024);

// 使用后释放，计数归0，内存返回池中
pooledBuf.release(); // 资源被池回收，可复用

// 错误示例：释放后继续使用
pooledBuf.writeInt(123); // 报错：refCnt=0，已释放
2. 非池化 ByteBuf 的生命周期
java
// 非池化分配，初始计数=1
ByteBuf unpooledBuf = Unpooled.buffer(1024);

// 释放后，内存由GC回收（但引用计数机制仍适用）
unpooledBuf.release(); // 计数=0，对象等待GC


3. 引用计数是池化的基础
复用前提：只有当所有使用者都release()后，ByteBuf 才能回到池中复用。
内存安全：若忘记release()，池中的资源会逐渐耗尽，导致内存泄漏。*/



/*    在实际写 Netty 代码时，核心遵循一个原则：谁是 ByteBuf 的 “最终使用者”，谁负责释放；如果需要让 ByteBuf “活得更久”（超出当前处理流程），就用 retain ()。

    下面分场景给出具体操作指南，结合代码示例，一看就懂：
    一、最常见场景：业务 Handler 作为 “最终使用者” → 必须手动释放
    当你的业务 Handler 是责任链的最后一环（不再把 ByteBuf 传递给下一个 Handler），或者你已经把 ByteBuf 的数据提取完（比如转成 String、对象），此时你就是 “最终使用者”，必须手动释放。
    正确写法（用 try-finally 确保释放）：
    java
    public class MyBusinessHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 先判断msg是不是ByteBuf（可能经过解码器转换）
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                try {
                    // 提取数据（比如转成字符串）
                    String content = buf.toString(StandardCharsets.UTF_8);
                    System.out.println("收到数据：" + content);
                    // 这里已经处理完，buf没用了 → 最终使用者
                } finally {
                    // 必须释放！用ReferenceCountUtil更安全（非ByteBuf也不报错）
                    ReferenceCountUtil.release(buf);
                }
            } else {
                // 如果不是ByteBuf（比如解码器转后的对象），通常不需要释放
                // 除非这个对象内部包含ByteBuf，那要按其规则处理
            }
        }
    }


    为什么必须释放？
    如果不释放，ByteBuf 的引用计数永远不为 0，池化的内存无法回收，会导致内存泄漏。


    二、场景二：Handler 只是 “传递者” → 不释放，也不用 retain
如果你的 Handler 只是对 ByteBuf 做简单处理（比如过滤、修改），然后通过ctx.fireChannelRead(msg)传递给下一个 Handler，此时你不是 “最终使用者”，不需要释放，也不需要 retain（默认引用计数足够支撑传递）。
正确写法：
java
public class LogHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 简单打印长度，不消费数据
            System.out.println("收到数据长度：" + buf.readableBytes());
        }
        // 传递给下一个Handler，不释放
        ctx.fireChannelRead(msg);
    }
}
为什么不释放？
因为下一个 Handler 可能还需要使用这个 ByteBuf，提前释放会导致下一个 Handler 拿到 “已释放” 的 ByteBuf，抛出异常。
三、场景三：异步处理 ByteBuf → 必须先 retain ()，处理完再释放
如果需要把 ByteBuf 交给另一个线程（比如线程池异步处理），当前 Handler 的处理流程会先结束，此时必须用retain()增加引用计数，否则当前流程结束后 ByteBuf 可能被提前释放（异步线程还没处理完）。
正确写法：
java
public class AsyncHandler extends ChannelInboundHandlerAdapter {
    // 假设存在一个业务线程池
    private ExecutorService businessPool = Executors.newFixedThreadPool(10);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 1. 异步处理前，必须retain()！否则当前Handler结束后可能被释放
            buf.retain(); // 引用计数+1，现在计数是2（默认初始是1）

            businessPool.submit(() -> {
                try {
                    // 异步处理数据（比如复杂计算）
                    String content = buf.toString(StandardCharsets.UTF_8);
                    System.out.println("异步处理结果：" + content);
                } finally {
                    // 2. 异步处理完，必须释放！引用计数-1（回到1，等待原流程处理）
                    ReferenceCountUtil.release(buf);
                }
            });

            // 3. 当前Handler继续传递（如果需要），此时引用计数是1（因为异步处理完后减了1）
            ctx.fireChannelRead(msg);
        }
    }
}





为什么要 retain ()？
默认 ByteBuf 的引用计数是 1，当前 Handler 处理完如果不 retain，一旦传递给下一个 Handler 或流程结束，计数可能减为 0 被释放。异步线程拿到的就是 “已释放” 的 ByteBuf，会报错。*/
}
