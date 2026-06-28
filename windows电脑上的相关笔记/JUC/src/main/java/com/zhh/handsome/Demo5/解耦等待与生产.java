package com.zhh.handsome.Demo5;

public class 解耦等待与生产 {


    // 1. 中间载体：负责存储结果、等待结果、通知结果——解耦的核心！
    static class ResultHolder {
        private Integer result; // 存储生产方的结果（用Integer可以为null）
        private boolean isReady = false; // 结果是否准备好

        // 等待方调用：等待结果（支持超时）
        public synchronized Integer waitResult(long timeout) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            long elapsed = 0;

            // 循环检查结果是否准备好（必须用while防虚假唤醒）
            while (!isReady) {
                long remaining = timeout - elapsed;
                if (remaining <= 0) {
                    throw new InterruptedException("等待超时！");
                }
                // 没准备好就等待，释放锁让生产方可以设置结果
                this.wait(remaining);
                elapsed = System.currentTimeMillis() - startTime;
            }
            return result; // 返回结果给等待方
        }

        // 生产方调用：设置结果并通知等待方
        public synchronized void setResult(int result) {
            if (isReady) {
                return; // 防止重复设置结果
            }
            this.result = result; // 存储结果
            this.isReady = true; // 标记结果已准备好
            this.notifyAll(); // 唤醒所有等待的线程
        }
    }

    // 2. 生产方：只依赖中间载体，完全不知道等待方是谁
    static class Worker extends Thread {
        private ResultHolder resultHolder; // 只和载体交互

        // 构造方法只需要载体，不需要知道等待方
        public Worker(ResultHolder resultHolder) {
            this.resultHolder = resultHolder;
        }

        @Override
        public void run() {
            try {
                System.out.println("生产方：开始计算...");
                Thread.sleep(2000); // 模拟耗时计算
                int result = 1 + 1; // 计算结果
                // 只需把结果设置到载体，不用关心谁在等
                resultHolder.setResult(result);
                System.out.println("生产方：计算完成，结果已放入载体");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 3. 等待方：只依赖中间载体，完全不知道生产方是谁
    static class MainThread extends Thread {
        private ResultHolder resultHolder; // 只和载体交互

        // 构造方法只需要载体，不需要知道生产方
        public MainThread(ResultHolder resultHolder) {
            this.resultHolder = resultHolder;
        }

        @Override
        public void run() {
            try {
                System.out.println("等待方：开始等待结果...");
                // 只需从载体等结果，不用关心谁在生产
                int result = resultHolder.waitResult(3000); // 最多等3秒
                System.out.println("等待方：从载体拿到结果：" + result);
            } catch (InterruptedException e) {
                System.out.println("等待方：等待超时");
            }
        }
    }

    // 4. 主线程：负责组装对象（依赖注入），等待方和生产方完全解耦
    public static void main(String[] args) {
        // 创建中间载体
        ResultHolder resultHolder = new ResultHolder();

        // 分别创建等待方和生产方，只传入载体，两者互不认识
        MainThread mainThread = new MainThread(resultHolder);
        Worker worker = new Worker(resultHolder);

        // 启动线程
        mainThread.start();
        worker.start();
    }
}

