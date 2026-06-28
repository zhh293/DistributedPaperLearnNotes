package com.zhanghonghao.normalclass.IOstream;

public class writer {
    public static void main(String[] args) {
        /*创建字符输出流对象
细节 1：构造函数的参数既可以是字符串表示的路径，也可以是File对象。
细节 2：若指定文件不存在，会创建一个新文件，但必须保证父级路径存在。例如，若想在C:\new_folder\test.txt创建文件，C:\new_folder 文件夹需事先存在。
细节 3：若文件已存在，默认会清空文件内容。若不想清空，可开启续写模式。
写数据
细节：当write方法的参数为整数时，写入本地文件的是该整数在字符集上对应的字符。比如write(97)，实际写入文件的是字符'a'，因为 97 在 ASCII 字符集中对应字符'a'。
释放资源
细节：每次使用完流之后都要释放资源，通常是调用流对象的close方法关闭流，以避免资源占用和潜在的 I/O 错误等问题。*/
        /*第一步：创建对象
提供了 4 种构造方法来创建FileWriter对象：
public FileWriter(File file)：根据给定的File对象创建字符输出流，用于关联本地文件。
public FileWriter(String pathname)：通过文件路径名创建字符输出流，关联本地文件。
public FileWriter(File file, boolean append)：以File对象指定文件创建字符输出流，append参数为true时表示启用续写模式，即不会清空原文件内容，而是在文件末尾追加数据。
public FileWriter(String pathname, boolean append)：根据文件路径名创建字符输出流，append参数功能同上，决定是否启用续写模式。
第二步：写入数据
包含 5 种write方法用于写入数据：
void write(int c)：写入一个字符，实际写入的是该整数在字符集上对应的字符。
void write(String str)：写入一个字符串。
void write(String str, int off, int len)：写入字符串的一部分，从索引off开始，长度为len。
void write(char[] cbuf)：写入一个字符数组。
void write(char[] cbuf, int off, int len)：写入字符数组的一部分，从索引off开始，长度为len。
第三步：释放资源
public void close()：用于关闭流并释放相关资源，每次使用完流后都应调用该方法。*/
    }
}
