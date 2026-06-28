package com.zhh.handsome.Demo4;

public class 设计模式 {

   /* 设计模式：保护性暂停（Guarded Suspension）详解
    保护性暂停（Guarded Suspension）是线程协作领域的基础设计模式，核心解决 “一个线程需要等待另一个线程完成特定任务（如生成结果、加载资源）后才能继续执行” 的问题。它通过 “条件判断 + 等待 - 唤醒” 机制，确保等待线程仅在 “结果就绪” 时才继续执行，避免无效轮询或拿到未就绪的无效数据 —— 这也是 “保护性” 一词的由来：保护等待线程不被无效数据干扰，同时通过 “暂停” 避免资源浪费。
    一、核心概念与本质
1. 解决的核心问题
    在多线程协作中，经常存在 “依赖线程（等待方） ” 需要依赖 “执行线程（生产方） ” 的输出（如计算结果、网络响应、文件数据）的场景。若不做控制，会出现两种问题：
    轮询浪费：依赖线程反复检查 “结果是否就绪”，占用 CPU 资源；
    数据无效：依赖线程在结果未就绪时强行获取，拿到空值或错误数据。
    保护性暂停的本质是：让依赖线程在 “结果未就绪” 时主动暂停（阻塞），在 “结果就绪” 时被唤醒，高效且安全地获取结果。
            2. 关键特征
    一对一协作：通常是 “一个依赖线程等待一个执行线程”（区别于生产者 - 消费者的 “多对多 / 一对多”）；
    结果中心化：通过一个 “结果持有者” 对象管理结果状态，统一控制等待和唤醒逻辑；
    条件驱动：等待 / 唤醒的触发条件是 “结果是否就绪”，而非线程是否终止；
    支持超时：可避免依赖线程无限期阻塞（核心优化点）。

    二、模式结构与角色
    保护性暂停模式包含 3 个核心角色，职责清晰且解耦，结构如下：
    角色名称	核心职责	示例（Java 场景）
    Guarded Object（结果持有者）	1. 存储执行线程的结果；
            2. 提供 “获取结果” 方法（供依赖线程调用，可能阻塞）；
            3. 提供 “设置结果” 方法（供执行线程调用，唤醒等待）；
            4. 维护 “结果是否就绪” 的条件。	自定义的GuardedResult类
    Dependent Thread（依赖线程 / 等待方）	1. 发起任务（如启动执行线程）；
            2. 调用 Guarded Object 的 “获取结果” 方法，等待结果；
            3. 拿到结果后继续执行后续逻辑。	主线程（Main Thread）
    Executor Thread（执行线程 / 生产方）	1. 执行具体任务（如计算、下载、加载）；
            2. 任务完成后，调用 Guarded Object 的 “设置结果” 方法，传递结果；
            3. 唤醒阻塞的依赖线程。	子线程（Worker Thread）
    三、工作流程（以 Java 为例）
    保护性暂停的执行逻辑可拆解为 5 个步骤，核心是Guarded Object 的 “条件判断 - 等待 - 唤醒” 闭环：
    初始化结果持有者：依赖线程创建Guarded Object实例，用于后续传递结果；
    启动执行线程：依赖线程启动执行线程，并将Guarded Object传递给执行线程（让执行线程知道结果该存哪里）；
    依赖线程等待结果：依赖线程调用Guarded Object的getResult()方法，检查 “结果是否就绪”：
    若未就绪：依赖线程调用wait()方法，释放对象锁，进入阻塞状态（暂停）；
    若已就绪：直接返回结果，继续执行；
    执行线程完成任务并唤醒：执行线程完成任务后，调用Guarded Object的setResult()方法，设置结果，并调用notify()（或notifyAll()）唤醒阻塞的依赖线程；
    依赖线程恢复执行：依赖线程被唤醒后，重新获取对象锁，再次检查 “结果是否就绪”（避免虚假唤醒），确认就绪后获取结果，继续执行后续逻辑。



    四、代码示例（Java 实现）
    下面通过 “主线程等待子线程下载图片” 的场景，实现保护性暂停模式，包含超时机制和虚假唤醒处理（工业级实现的关键）。
            1. 第一步：定义 Guarded Object（结果持有者）
    java
            运行
    *//**
     * 结果持有者：管理图片下载结果，控制等待和唤醒
     *//*
    public class GuardedImage {
        // 存储执行线程的结果（这里是图片字节数组）
        private byte[] imageData;
        // 标记结果是否就绪
        private boolean isReady = false;

        *//**
         * 依赖线程调用：获取图片结果（带超时）
         * @param timeout 超时时间（毫秒）
         * @return 图片字节数组，超时返回null
         * @throws InterruptedException 线程被中断时抛出
         *//*
        public synchronized byte[] getImage(long timeout) throws InterruptedException {
            // 1. 记录开始等待的时间（用于计算超时）
            long start = System.currentTimeMillis();
            // 2. 剩余等待时间（初始为总超时时间）
            long remaining = timeout;

            // 3. 循环判断条件（处理虚假唤醒：即使被唤醒，也要重新检查结果是否就绪）
            while (!isReady) {
                // 若剩余时间<=0，超时返回null
                if (remaining <= 0) {
                    System.out.println("等待超时，未获取到图片");
                    return null;
                }

                // 等待剩余时间（释放锁，进入阻塞）
                wait(remaining);

                // 4. 被唤醒后，更新剩余等待时间
                remaining = timeout - (System.currentTimeMillis() - start);
            }

            // 5. 结果就绪，返回结果（并重置状态，支持重复使用）
            byte[] result = this.imageData;
            this.imageData = null;
            this.isReady = false;
            return result;
        }

        *//**
         * 执行线程调用：设置图片结果，唤醒等待线程
         * @param data 下载好的图片字节数组
         *//*
        public synchronized void setImage(byte[] data) {
            // 1. 设置结果
            this.imageData = data;
            this.isReady = true;

            // 2. 唤醒等待的依赖线程（若有多个等待线程，用notifyAll()）
            notify();
            System.out.println("图片已下载完成，唤醒等待线程");
        }
    }

    2. 第二步：定义执行线程（图片下载线程）
    java
            运行
    *//**
     * 执行线程：模拟图片下载任务
     *//*
    public class ImageDownloader extends Thread {
        // 持有结果持有者的引用（用于设置结果）
        private final GuardedImage guardedImage;
        // 模拟下载地址
        private final String imageUrl;

        public ImageDownloader(GuardedImage guardedImage, String imageUrl) {
            this.guardedImage = guardedImage;
            this.imageUrl = imageUrl;
        }

        @Override
        public void run() {
            try {
                // 模拟下载耗时（2秒）
                System.out.println("开始下载图片：" + imageUrl);
                Thread.sleep(2000);

                // 模拟下载结果（实际场景是HTTP请求获取字节数组）
                byte[] mockImage = "模拟图片数据".getBytes();

                // 下载完成，设置结果并唤醒等待线程
                guardedImage.setImage(mockImage);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("下载线程被中断");
            }
        }
    }
3. 第三步：依赖线程（主线程）调用
            java
    运行
    *//**
     * 依赖线程（主线程）：等待图片下载完成后，处理图片
     *//*
    public class MainThread {
        public static void main(String[] args) throws InterruptedException {
            // 1. 创建结果持有者
            GuardedImage guardedImage = new GuardedImage();

            // 2. 启动执行线程（下载图片）
            ImageDownloader downloader = new ImageDownloader(guardedImage, "https://example.com/image.jpg");
            downloader.start();

            // 3. 主线程等待结果（超时时间3秒）
            System.out.println("主线程等待图片下载...");
            byte[] image = guardedImage.getImage(3000);

            // 4. 处理结果
            if (image != null) {
                System.out.println("主线程拿到图片，长度：" + image.length + "，内容：" + new String(image));
            } else {
                System.out.println("主线程未拿到图片，继续执行其他逻辑");
            }
        }
    }
4. 执行结果
            plaintext
    主线程等待图片下载...
    开始下载图片：https://example.com/image.jpg
    图片已下载完成，唤醒等待线程
    主线程拿到图片，长度：6，内容：模拟图片数据
    五、关键细节解析
1. 为什么用while而不是if判断条件？（虚假唤醒处理）
    Java 的wait()可能会出现虚假唤醒（spurious wakeup）：即使没有调用notify()/notifyAll()，线程也可能被操作系统唤醒（如系统调度原因）。若用if判断：
    java
            运行
// 错误写法：无法处理虚假唤醒
if (!isReady) {
        wait();
    }
    虚假唤醒后，线程会直接跳过判断，获取到未就绪的imageData（null），导致错误。而while循环会在唤醒后重新检查条件，确保只有 “结果真的就绪” 时才继续执行，是工业级实现的必须步骤。
            2. 超时机制的作用
    代码中getImage(long timeout)支持超时，避免依赖线程无限期阻塞：
    若执行线程因异常（如网络故障）无法完成任务，依赖线程会在超时后自动退出等待，继续执行其他逻辑（如重试、返回默认值）；
    超时时间计算逻辑：remaining = timeout - (当前时间 - 开始时间)，确保等待总时长不超过设定值（避免wait(timeout)被唤醒后，累计等待时间超过预期）。
            3. synchronized的作用
    getImage()和setImage()都用synchronized修饰，原因是：
    wait()/notify()必须在持有对象锁的情况下调用，否则会抛出IllegalMonitorStateException；
    保证isReady和imageData的线程可见性：执行线程修改isReady后，依赖线程能立即看到最新值（避免 CPU 缓存导致的 “可见性问题”）；
    保证操作的原子性：避免 “依赖线程检查isReady为 false 后，还没来得及wait()，执行线程就已经setImage()并notify()” 的竞态条件。




    九、总结
保护性暂停是最基础的线程协作模式之一，它的核心价值在于：
解决 “一对一” 线程间的 “结果依赖” 问题，避免无效轮询和数据无效；
通过 “等待 - 唤醒” 机制，高效利用 CPU 资源（等待时释放锁，不占用 CPU）；
支持超时和中断，避免线程无限期阻塞，提高系统稳定性。
理解保护性暂停，是掌握Future、线程池、RPC 调用等高级并发工具的基础 —— 它的 “结果持有者 + 等待 - 唤醒” 思想，贯穿了大部分并发协作场景。*/



}
