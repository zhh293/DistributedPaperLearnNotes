package Netty;

public class NettyDemo4 {
    public static void main(String [] args)
    {
        //如果两个handler绑定的是一个线程，那么这两个handler的handle方法会顺序执行
        //否则把调用的代码封装在一个任务对象中，并提交给线程池，由下一个handler的线程来执行
    }
}
