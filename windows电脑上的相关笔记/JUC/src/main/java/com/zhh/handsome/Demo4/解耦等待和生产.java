package com.zhh.handsome.Demo4;

import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class 解耦等待和生产 {
    private static final ReentrantLock lock = new ReentrantLock();
    private static final Condition conditionA=lock.newCondition();
    private static final Condition conditionB=lock.newCondition();
    private static final Condition conditionC=lock.newCondition();
    private static final Object lockObject=new Object();
    public static void main(String[] args) {
        Random random=new Random();
        BlockingQueue<Integer>blockingQueue=new LinkedBlockingQueue<>(100);
        new Thread(()->{
           while (true){
               while (blockingQueue.size()==100){
                   try {
                       conditionA.await();
                   } catch (InterruptedException e) {
                       throw new RuntimeException(e);
                   }
               }
               //随机生成一个数字
               int i;
               synchronized (lockObject) {
                    i = random.nextInt(100);
               }
               boolean offer = blockingQueue.offer(i);
               if (!offer) {
                   System.out.println("队列已满");
               }
               conditionC.signal();
               conditionB.signal();
           }
        }).start();

        new Thread(()->{
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (true){
                while (blockingQueue.isEmpty()){
                    try {
                        conditionB.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                Integer take = null;
                try {
                    take = blockingQueue.take();
                    conditionA.signal();
                    System.out.println(take);
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
            }
        }).start();


        new Thread(()->{
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (true){
                while (blockingQueue.isEmpty()){
                    try {
                        conditionC.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                Integer take = null;
                try {
                    take = blockingQueue.take();
                    conditionA.signal();
                    System.out.println(take);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();

















//        new Thread(() -> {
//            while(true){
//                while (blockingQueue.isEmpty()){
//                    //消费者停止消费
//                    try {
//                        conditionB.await();
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//                Integer take = null;
//                try {
//                    take = blockingQueue.take();
//                    conditionA.signal();
//                    System.out.println(take);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        });
//
//        new Thread(() -> {
//            while(true){
//                while (blockingQueue.isEmpty()){
//                    try {
//                        conditionC.await();
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//                Integer take = null;
//                try {
//                    take = blockingQueue.take();
//                    conditionA.signal();
//                    System.out.println(take);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        });
//
//        new Thread(() -> {
//            while(true){
//                while (blockingQueue.size()==100){
//                    try {
//                        conditionA.await();
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//                boolean offer = blockingQueue.offer(1);
//                if (!offer) {
//                    System.out.println("队列已满");
//                }
//                conditionB.signal();
//                  conditionC.signal();
//            }
//        });
    }


    /*一、先搞懂：什么是 “等待方” 和 “生产方”？
    在多线程场景里，这两者的关系很明确：
    生产方：负责 “干活” 产生结果的线程（比如外卖员做外卖、接口调用线程获取数据）。
    等待方：需要生产方的结果才能继续干活的线程（比如顾客等外卖吃饭、主线程等接口数据渲染页面）。
    如果没有解耦，它们的关系会像 “顾客直接盯着外卖员”—— 顾客必须知道外卖员是谁、电话多少，外卖员也必须记得给这个顾客送，一方变了，另一方就得跟着改，这就是 “紧耦合”。
    二、未解耦的坑：等待方和生产方直接绑定
    先看一个未解耦的反面例子，感受下有多麻烦：
    java
            运行
    // 生产方：外卖员线程，直接持有等待方（顾客）的引用
    class DeliveryMan extends Thread {
        private Customer customer; // 直接依赖顾客！

        public DeliveryMan(Customer customer) {
            this.customer = customer;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(2000); // 模拟做外卖2秒
                String food = "汉堡套餐";
                // 直接通知顾客：我送来了！
                synchronized (customer) {
                    customer.setFood(food);
                    customer.notify(); // 唤醒顾客
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 等待方：顾客线程，直接依赖生产方（外卖员）
    class Customer extends Thread {
        private String food;

        @Override
        public void run() {
            DeliveryMan deliveryMan = new DeliveryMan(this); // 顾客要自己创建外卖员！
            deliveryMan.start();

            // 等待外卖，直接用自己当锁
            synchronized (this) {
                try {
                    this.wait(); // 等外卖员notify
                    System.out.println("吃到了：" + food);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void setFood(String food) {
            this.food = food;
        }
    }

    // 测试：启动顾客线程
    public class Test {
        public static void main(String[] args) {
            new Customer().start();
        }
    }
    这个例子里，耦合问题非常严重：
    顾客必须自己创建外卖员，还得把自己的引用传给外卖员（new DeliveryMan(this)）—— 如果换了外卖平台（比如从美团换成饿了么），顾客代码得全改。
    外卖员必须知道 “要给哪个顾客送”（持有Customer引用）—— 如果一次要给 10 个顾客送，外卖员得持有 10 个顾客引用，代码直接爆炸。
    锁对象是顾客自己 —— 如果顾客线程出问题，外卖员的notify也会失效，两者死死绑定。
    三、保护性暂停的解耦魔法：引入 “中间载体”
    保护性暂停的核心，就是在等待方和生产方之间加一个 **“结果载体”（Guard Object）** —— 它像一个 “外卖柜”：
    生产方（外卖员）不用找顾客，只要把结果（外卖）放进外卖柜就行。
    等待方（顾客）不用盯外卖员，只要去外卖柜等结果（外卖）就行。
    两者从此互不认识，只和 “外卖柜” 打交道 —— 这就是解耦！
            1. 解耦后的代码实现（核心是 “结果载体”）
    我们先定义这个 “外卖柜”（结果载体），它只负责两件事：
    让等待方 “等结果”（waitResult方法）；
    让生产方 “放结果”（setResult方法）。
    java
            运行
    // 1. 中间载体：结果柜（外卖柜）——解耦的关键！
    class ResultHolder {
        private Object result; // 存生产方的结果（外卖）
        private boolean isReady = false; // 结果是否准备好

        // 等待方调用：等结果（最多等timeout毫秒）
        public synchronized Object waitResult(long timeout) throws InterruptedException {
            long startTime = System.currentTimeMillis();
            long elapsed = 0;

            // 循环检查结果是否准备好（防虚假唤醒）
            while (!isReady) {
                long remaining = timeout - elapsed;
                if (remaining <= 0) {
                    throw new InterruptedException("等超时了！");
                }
                this.wait(remaining); // 没结果就等，释放锁
                elapsed = System.currentTimeMillis() - startTime;
            }

            // 结果准备好了，返回给等待方
            return result;
        }

        // 生产方调用：放结果
        public synchronized void setResult(Object result) {
            if (isReady) {
                return; // 防止重复放结果
            }
            this.result = result; // 把结果放进柜子
            this.isReady = true; // 标记结果已准备好
            this.notifyAll(); // 唤醒所有等结果的等待方
        }
    }

    // 2. 生产方：外卖员线程——只依赖ResultHolder，不认识顾客！
    class DeliveryMan extends Thread {
        private ResultHolder resultHolder; // 只和“外卖柜”交互

        public DeliveryMan(ResultHolder resultHolder) {
            this.resultHolder = resultHolder;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(2000); // 做外卖2秒
                String food = "汉堡套餐";
                resultHolder.setResult(food); // 把外卖放进柜子，完事！
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 3. 等待方：顾客线程——只依赖ResultHolder，不认识外卖员！
    class Customer extends Thread {
        private ResultHolder resultHolder; // 只和“外卖柜”交互

        public Customer(ResultHolder resultHolder) {
            this.resultHolder = resultHolder;
        }

        @Override
        public void run() {
            try {
                // 去柜子等结果，最多等3秒
                Object food = resultHolder.waitResult(3000);
                System.out.println("吃到了：" + food);
            } catch (InterruptedException e) {
                System.out.println("等超时了，没吃到！");
            }
        }
    }

    // 4. 测试：主线程负责“搭线”——把载体传给双方
    public class Test {
        public static void main(String[] args) {
            // 创建一个外卖柜
            ResultHolder resultHolder = new ResultHolder();

            // 外卖员和顾客都只拿到外卖柜，互相不认识
            new DeliveryMan(resultHolder).start();
            new Customer(resultHolder).start();
        }
    }
2. 解耦体现在哪里？（3 个核心变化）
    对比未解耦的代码，现在的逻辑完全不一样了：
    对比维度	未解耦（直接绑定）	保护性暂停（中间载体）
    依赖关系	顾客依赖外卖员，外卖员依赖顾客	顾客依赖载体，外卖员依赖载体，双方无依赖
    代码修改成本	换外卖员 / 换顾客，两边代码都要改	换外卖员 / 换顾客，只改对应线程，载体不变
    扩展性	1 个外卖员只能对应 1 个顾客	1 个载体可对应多个等待方（比如一家人等 1 份外卖）
    举个具体的扩展场景：如果现在有 3 个顾客要等同一份外卖（比如一家三口），未解耦的代码需要外卖员持有 3 个顾客引用，逐个notify；而保护性暂停只要让 3 个顾客都调用同一个ResultHolder的waitResult，外卖员放一次结果，notifyAll就能唤醒所有人 —— 代码几乎不用改！
    四、解耦的本质：依赖 “接口” 而非 “具体实现”
    再往深一层说，保护性暂停的解耦，其实是遵循了 **“依赖倒置原则”**：
    等待方和生产方不再依赖对方的 “具体是谁”（比如Customer类、DeliveryMan类），而是依赖 “载体的接口”（waitResult和setResult方法）。
    只要载体的方法不变，不管等待方是 “顾客” 还是 “快递收件人”，不管生产方是 “外卖员” 还是 “快递员”，代码都能正常工作。
    就像现实中的外卖柜：不管是美团、饿了么的外卖员，都知道 “把外卖放进柜子”；不管是老人、小孩，都知道 “从柜子取外卖”—— 外卖柜的 “存 / 取” 规则（接口）不变，双方就不用管对方是谁。
    五、总结：保护性暂停如何解耦？
    一句话说清核心逻辑：通过引入 “结果载体”，将等待方 “等结果” 和生产方 “产结果” 的直接交互，拆成 “等待方 - 载体” 和 “生产方 - 载体” 的两个独立交互，让双方从 “互相绑定” 变成 “各自依赖中间件”，从而实现解耦。
    这种模式的好处很明显：代码更灵活、更容易维护、扩展性更强 —— 这也是它在多线程编程中（比如异步任务等待、RPC 调用等待）被广泛使用的原因。*/



}
