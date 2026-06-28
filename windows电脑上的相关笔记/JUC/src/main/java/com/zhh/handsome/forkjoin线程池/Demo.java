package com.zhh.handsome.forkjoin线程池;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class Demo {
    public static void main(String[] args) {
        ForkJoinPool pool = new ForkJoinPool(2); // 只创建2个线程，方便观察
//        SumTask task = new SumTask(1, 10000);
        long[]array=new long[10001];
        for(int i=0;i<10001;i++){
            array[i]=i;
        }
        FindMax task = new FindMax(0, 10000,array);
        Integer total = pool.invoke(task);
        System.out.println("最终结果：" + total);
        pool.shutdown();
    }
}

class SumTask extends RecursiveTask<Long> {
    // 省略之前的参数和构造方法
    private static final long THRESHOLD = 2500;
    private long start;
    private long end;
    public SumTask(long start, long end) {
        this.start = start;
        this.end = end;
    }

    @Override
    protected Long compute() {
        long length = end - start;
        // 打印当前线程和执行的任务
        System.out.println(Thread.currentThread().getName() + " 处理任务：" + start + "-" + end);

        if (length <= THRESHOLD) {
            long sum = 0;
            for (long i = start; i <= end; i++) {
                sum += i;
            }
            System.out.println(Thread.currentThread().getName() + " 完成计算：" + start + "-" + end + " = " + sum);
            return sum;
        } else {
            long mid = (start + end) / 2;
            SumTask leftTask = new SumTask(start, mid);
            SumTask rightTask = new SumTask(mid + 1, end);

            leftTask.fork();
            rightTask.fork();

            Long leftResult = leftTask.join();
            Long rightResult = rightTask.join();

            System.out.println(Thread.currentThread().getName() + " 汇总：" + start + "-" + mid + " + " + (mid+1) + "-" + end + " = " + (leftResult+rightResult));
            return leftResult + rightResult;
        }
    }
}

class FindMax extends RecursiveTask<Integer> {
    private static final long THRESHOLD = 1000;
    private long start;
    private long end;
    private long[] array;
    public FindMax(long start, long end,long[]array) {
        this.start = start;
        this.end = end;
        this.array = array;
    }
    @Override
    protected Integer compute() {
        if(end - start <= THRESHOLD){
            long max=array[(int) start];
            for(long i=start;i<=end;i++){
                if(array[(int) i]>max){
                    max=array[(int) i];
                }
            }
            return (int) max;
        }else{
            long mid = (start + end) / 2;
            FindMax leftTask = new FindMax(start, mid,array);
            FindMax rightTask = new FindMax(mid + 1, end,array);
            leftTask.fork();
            rightTask.fork();

            Integer leftResult = leftTask.join();
            Integer rightResult = rightTask.join();
            System.out.println(Thread.currentThread().getName() + " 汇总：" + start + "-" + mid + " + " + (mid+1) + "-" + end + " = " + (leftResult+rightResult));
            return Math.max(leftResult,rightResult);
        }
    }
}


//你想让我用八十岁老奶奶能听懂的大白话，讲讲 ForkJoin 线程池到底是个啥，不用任何难懂的专业词，保证你一听就明白 —— 咱就拿生活里做家务、干活的事儿来讲，包你听懂～
//一、先搞懂：线程池就是 “干活的班组”
//咱先把基础说透：不管是之前聊的 Tomcat 线程池，还是今天的 ForkJoin 线程池，本质都是 “一个干活的班组”。
//普通线程池（比如 Tomcat 用的）：班组里有 10 个工人，来了 10 个活儿，每人分 1 个，各自干各自的，干完拉倒；要是来了 11 个活儿，第 11 个就得等有人干完再干。
//ForkJoin 线程池：专门对付 “一个超大的活儿”，比如 “包 1000 个饺子”，它不直接让一个人干，而是先拆成小活儿，再汇总，这就是它的核心。
//二、ForkJoin 的核心：先 “分”（Fork），再 “合”（Join）
//咱用 “包 1000 个饺子” 这个例子，一步一步说清楚：
//        1. Fork（拆分）—— 把大活儿拆成小活儿
//你想包 1000 个饺子，一个人干得干大半天，累得慌。ForkJoin 就像你家的 “主事的”，先把这 1000 个饺子的活儿拆：
//先拆成 4 份，每份 250 个，分给 4 个家人（相当于 4 个线程）；
//这 4 个人觉得 250 个还是多，各自再拆：每人把 250 个拆成 5 份，每份 50 个；
//拆到最后，每个人只需要包 50 个 —— 这个量不累，还能快干完。
//        👉 这就是Fork（分叉 / 拆分）：把一个 “干不动的大活儿”，拆成一堆 “轻松能搞定的小活儿”，直到每个小活儿小到不用再拆为止。
//        2. Join（汇总）—— 把小活儿的结果合起来
//等所有人都把 50 个饺子包完了：
//先把每个人拆的 5 份 50 个汇总，变成 250 个（对应第一步拆的 4 份）；
//再把 4 份 250 个汇总，就凑齐了 1000 个饺子。
//        👉 这就是Join（汇合 / 汇总）：等所有小活儿都干完，把结果一点点合起来，最后得到整个大活儿的结果。
//        3. 最贴心的点：“谁干完谁帮忙”（工作窃取）
//ForkJoin 还有个特别聪明的地方 —— 就像包饺子时，你先包完自己的 50 个，看到老伴还在慢悠悠包，你不会坐着歇着，而是主动拿他的饺子皮帮忙包。在 ForkJoin 里，这个叫 “工作窃取”：
//某个线程（工人）干完自己的小活儿，发现别的线程还没干完，就主动 “偷” 对方没干完的小活儿来干；
//这样就不会有人闲着，所有人都忙到最后，大活儿干得更快。
//三、啥时候用 ForkJoin？啥时候用普通线程池？
//咱用生活例子分清楚，别用混了：
//场景	用普通线程池（比如 Tomcat 的）	用 ForkJoin 线程池
//例子	小区门口收快递（每人收 1 个快递，独立干完）	打扫整个小区卫生（拆成每栋楼、每个单元、每层楼）
//特点	活儿是 “零散的、独立的”，一个人干一个完整的小活儿	活儿是 “超大的、能拆分的”，需要先拆再合
//咱生活里的类比	菜市场摆摊，每人卖一种菜	全家一起包 1000 个饺子、一起打扫全屋卫生
//四、再举个更简单的例子：算 1 到 10000 的和
//普通线程池：派 1 个人从 1 加到 10000，慢；ForkJoin：
//Fork：把 1-10000 拆成 1-2500、2501-5000、5001-7500、7501-10000，4 个人各算一段；
//每个人觉得 2500 个数还是多，再拆：比如 1-2500 拆成 1-625、626-1250…… 直到每人算 100 个数；
//Join：先把每人算的 100 个数的和加起来，再把 2500 个数的和加起来，最后汇总成 1-10000 的总和。
//总结（老奶奶版要点）
//ForkJoin 线程池是 “干大活儿的班组”，核心是先拆（Fork）小活儿，再合（Join）结果；
//它最适合干 “能拆的大活儿”（比如包很多饺子、算大数的和、扫整个小区），不适合干 “零散的小活儿”（比如收快递、处理单个网页请求）；
//它还有个 “勤快特点”：谁干完谁帮忙，不偷懒，效率比普通班组干大活儿高。