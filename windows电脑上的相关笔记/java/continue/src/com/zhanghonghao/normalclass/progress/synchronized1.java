package com.zhanghonghao.normalclass.progress;

public class synchronized1 extends Thread {
    static int counter = 0;
    static final Object lock = new Object();
    //锁对象必须是唯一的，否则没有什么用处，不能阻止CPU被抢占的结局，数据又不安全了
    //同步方法
    /*同步方法
就是把synchronized关键字加到方法上
格式:
修饰符 synchronized 返回值类型 方法名(方法参数) {..}
特点1:同步方法是锁住方法里面所有的代码
特点2:
锁对象不能自己指定，非静态方法：this  静态：当前类的字节码文件
ticket属于MyRunnable类，创建的MyRunnable实例放在堆中，对于不同的thread来说，是一个公共的变量
*/
   /* @Override
    public void run() {
        while (true) {
            synchronized (lock) {
                if(counter<100){
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    counter++;
                    System.out.println(this.getName()+":"+counter+"fucking");
                }
                else {
                    break;
                }
            }
        }

    }*/
    @Override
    public void run() {
        while (true) {
            synchronized (lock) {
                if(counter<100){
                    try{
                        Thread.sleep(10);
                    }catch (Exception e){
                        e.printStackTrace();
                        System.out.println(counter);
                    }
                    counter++;
                    System.out.println(counter);
                }else {
                    break;
                }
        }
        }
    }

}
//为什么说stringbuilder是线程不安全的
/*示例：两个线程同时 append ("a")
假设初始状态：value = ['\0', '\0']，count = 0。

时间线：

时间点	线程 A	                 线程 B	          结果
T1	检查容量足够（步骤 1）	检查容量足够（步骤 1）	    两个线程都认为无需扩容
T2	写入字符a到value[0]（步骤 2）		             value = ['a', '\0']
T3		                 写入字符a到value[0]（步骤 2）	   覆盖线程 A 的写入！value = ['a', '\0']
T4	更新计数器count = 1（步骤 3）		              count = 1
T5		              更新计数器count = 1（步骤 3）	    丢失一次追加！最终 count 应为 2

结果：两个线程都执行了append("a")，但实际只记录了一个a，另一个被覆盖。
        4. 更复杂的问题：数组越界
如果涉及扩容操作，问题会更严重：

线程 A 和 B 同时检查容量，发现需要扩容。
线程 A 完成扩容并复制数据。
线程 B 使用旧数组的引用进行写入，导致数组越界（因为它不知道数组已被扩容）。*/
