package org.example.ClassDemo1.System;

public class SystemDemo1 {
    public static void main(String[] args) {
        //只演示拷贝数组了
        int[] arr1 = {1, 2, 3, 4, 5};
        int[] arr2 = new int[5];
        System.arraycopy(arr1, 0, arr2, 0, arr1.length);
        System.err.println(10000);
        //解释一下参数
        //源数组，源数组的起始索引，目标数组，目标数组的起始索引，要复制的元素个数
    }
}
/*
1. System.out.println()
这个方法主要用于将文本输出到标准输出流（也就是控制台），并且在输出完成后会换行。

java
System.out.println("Hello, World!"); // 输出 Hello, World! 后换行
int num = 42;
System.out.println("The answer is: " + num); // 可以输出变量

细节说明：

System.out 属于 PrintStream 类型。
println() 方法在输出内容后会自动换行，若不想换行，可使用 print() 方法。
该方法能接受任意数据类型的参数，它会调用参数的 toString() 方法来进行输出。
        2. System.currentTimeMillis()
此方法的作用是返回当前系统时间与 1970 年 1 月 1 日 00:00:00 UTC 之间的时间差，单位为毫秒，常被用于计算程序的执行时间。

java
long startTime = System.currentTimeMillis();
// 执行一些代码
for (int i = 0; i < 1000; i++) {
        // 循环操作
        }
long endTime = System.currentTimeMillis();
System.out.println("执行时间: " + (endTime - startTime) + " 毫秒");

细节说明：

返回的是一个 long 类型的值。
其时间精度取决于底层操作系统，有可能无法精确到毫秒级别。
可用于性能测试和定时任务，但要是涉及到日期处理，使用 java.time 包会更合适。
        3. System.exit(int status)
该方法用于终止当前正在运行的 Java 虚拟机（JVM），status 参数是返回给操作系统的退出状态码，一般用 0 表示正常退出，非零值表示异常退出。

java
public static void main(String[] args) {
    // 检查命令行参数
    if (args.length == 0) {
        System.err.println("错误: 缺少参数");
        System.exit(1); // 异常退出
    }
    // 正常执行代码
    System.out.println("参数: " + args[0]);
    System.exit(0); // 正常退出（这一行其实可以省略）
}

细节说明：

调用此方法会让 JVM 立即终止，并且不会执行后续的代码。
若程序是正常结束的，通常不需要显式调用 System.exit()。
可以使用 Runtime.getRuntime().addShutdownHook() 方法来注册一个关闭钩子，在 JVM 关闭前执行一些清理操作。
其他常用方法
System.arraycopy()：能够高效地复制数组。
        System.gc()：会请求 JVM 进行垃圾回收。
        System.getProperty()：可获取系统属性。*/
