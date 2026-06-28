package com.zhanghonghao.normalclass.IOstream;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

public class printIoStream {
    public static void main(String[] args) throws FileNotFoundException {
        String test="hello abc";
        char[] chars=test.toCharArray();
        PrintStream printStream = new PrintStream(new FileOutputStream("test.txt"),true, Charset.forName("UTF-8"));
        printStream.println("Hello World");
        printStream.println(chars);
        printStream.print(true);//不会自动换行
        printStream.println();
        printStream.printf("%s爱上了阿强\n", "Hello World");
        printStream.close();
        /*public PrintWriter(Write/File/String)：关联字节输出流 / 文件 / 文件路径
public PrintWriter(String fileName, Charset charset)：指定字符编码
public PrintWriter(Write, boolean autoFlush)：自动刷新
public PrintWriter(Write out, boolean autoFlush, String encoding)：指定字符编码且自动刷新
成员方法：
public void write(int b)：常规方法，规则跟之前一样，将指定的字节写出
public void println(Xxx xx)：特有方法，打印任意数据，自动刷新，自动换行
public void print(Xxx xx)：特有方法，打印任意数据，不换行
public void printf(String format, Object... args)：特有方法，带有占位符的打印语句，不换行*/
        PrintWriter printWriter = new PrintWriter(new FileOutputStream("test.txt"),true, Charset.forName("UTF-8"));
        printWriter.println(test);
        //打印流不能读，只能写
        //字节打印流和字符打印流
        //打印流只能操作文件目的地，不操作数据源
        //特有的写出方法可以实现，数据原样写出
        //可以实现自动刷新，自动换行
        /*Java 中的打印流（Print Stream/Writer） 是处理输出的便捷工具，用于简化数据输出并提供格式化功能。它们分为两类：
字节打印流：PrintStream
字符打印流：PrintWriter
核心特点：
提供了一系列重载的print()和println()方法，支持各种数据类型的输出。
自动刷新（autoFlush）：可设置在输出换行符（\n）或调用println()时自动刷新缓冲区。
不会抛出IOException：而是提供checkError()方法检测错误。
二、字节打印流：PrintStream
1. 特点
继承关系：PrintStream → FilterOutputStream → OutputStream
处理字节：直接操作字节数据，适用于二进制文件或字节流。
自动刷新：可通过构造函数启用自动刷新（对println()、printf()、format()有效）。
支持系统输出：System.out和System.err均为PrintStream实例。
字节流底层没有缓冲区，开不开自动刷新都一样，直接就写到目的地了，没有停留的
print()/println()     // 打印各种类型数据（自动转换为字符串），相当于你写啥就是啥，而不是像字节输出流，写一个数字，会给你转成ascii码，如果想写出数字，必须写数字字符，char类型的，而且会自动换行，自动刷新
printf()/format()     // 格式化输出（类似C语言的printf）
append()              // 追加字符序列
checkError()          // 检查流是否发生错误
flush()/close()       // 刷新/关闭流
public void println (Xxx xx) 特有方法：打印任意数据，自动刷新，自动换行
public void print (Xxx xx) 特有方法：打印任意数据，不换行
public void printf (String format, Object... args) 特有方法：带有占位符的打印语句，不换行*/






        /*三、字符打印流：PrintWriter
1. 特点
继承关系：PrintWriter → Writer
处理字符：使用字符编码（如 UTF-8），适用于文本数据。
自动刷新：可通过构造函数启用自动刷新（对println()、printf()、format()有效）。
更安全的文本处理：避免了字节流的编码问题（如中文乱码）。
2. 构造方法
java
// 基础构造
PrintWriter(Writer out)
PrintWriter(Writer out, boolean autoFlush)
PrintWriter(OutputStream out)
PrintWriter(OutputStream out, boolean autoFlush)
PrintWriter(String fileName)
PrintWriter(String fileName, String csn)
PrintWriter(File file)
PrintWriter(File file, String csn)

3. 核心方法
与PrintStream类似，但更专注于字符处理：
java
print()/println()     // 打印各种类型数据
printf()/format()     // 格式化输出
append()              // 追加字符序列
checkError()          // 检查流是否发生错误
flush()/close()       // 刷新/关闭流






四、两者对比
特性	PrintStream（字节流）	PrintWriter（字符流）
处理单元	字节（byte）	字符（char）
编码支持	需要显式指定（构造函数中）	自动处理编码（更安全）
适用场景	二进制文件、系统输出（System.out）	文本文件、国际化文本
自动刷新触发条件	println()、printf()、format()	println()、printf()、format()
错误处理	不抛出IOException，通过checkError()	不抛出IOException，通过checkError()
性能	略快（直接操作字节）	略慢（需字符编码转换）*/





        /*一、自动刷新的核心概念
        对于字节打印流（如PrintStream）和字符打印流（如PrintWriter），是否自动刷新需分情况讨论：
字节打印流（PrintStream）
底层没有缓冲区，无论是否开启自动刷新开关，数据都会直接写出到目的地（如文件）。从效果上看，类似所有操作都 “自动刷新”，例如println等方法会直接将数据输出，不存在缓冲区滞留的情况。
字符打印流（PrintWriter）
底层有缓冲区，若不开启自动刷新开关，其特有方法（println、print、printf等）不会自动刷新。此时数据会先存入缓冲区，需手动调用flush()方法，或等待缓冲区满、调用close()关闭流时，才会将数据写入目的地。
1. 缓冲区的作用
打印流通常使用缓冲区（内存区域）暂存数据，减少频繁的 IO 操作，提高性能。
数据先写入缓冲区，当缓冲区满或手动调用flush()时，才会将数据写入底层流。
2. 自动刷新的触发条件
当启用自动刷新（autoFlush = true）时，打印流会在以下情况自动调用flush()：
调用println()：输出换行符并刷新缓冲区。
调用printf()：格式化输出后刷新缓冲区。
调用format()：格式化输出后刷新缓冲区。
二、自动刷新的工作机制
示例 1：启用自动刷新
java
try (PrintWriter pw = new PrintWriter(System.out, true)) { // 启用自动刷新
    pw.println("Hello"); // 输出"Hello"并换行，自动刷新缓冲区
    pw.print("World");   // 输出"World"，但不刷新（需手动调用flush()）
}

示例 2：禁用自动刷新
java
try (PrintWriter pw = new PrintWriter(System.out, false)) { // 禁用自动刷新
    pw.println("Hello"); // 输出"Hello"到缓冲区，但未写入控制台
    pw.flush(); // 手动刷新，数据才会显示在控制台


}

三、为什么需要自动刷新？
1. 避免数据丢失
在某些场景下（如程序崩溃、异常退出），缓冲区的数据可能未及时写入。启用自动刷新可确保关键数据立即输出。
2. 实时显示输出
交互式程序：如命令行工具，需立即显示用户提示。
日志系统：确保日志实时写入文件，便于故障排查。
3. 简化编程
无需频繁调用flush()，减少代码复杂度。*/
    }
}
