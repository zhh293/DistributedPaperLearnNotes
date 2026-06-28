package com.zhanghonghao.normalclass.IOstream;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

public class changeIO {
    public static void main(String[] args) throws IOException {
        InputStreamReader isr = new InputStreamReader(new FileInputStream("myio\\gbkfile.txt"),"GBK");
        int ch;
        while((ch= isr.read())!=-1){
            System.out.print((char)ch);
        }
        isr.close();


        //这个方法是主流方法，上面那个方法已经被淘汰了
        FileReader fr = new FileReader("myio\\gbkfile.txt", Charset.forName("GBK"));


        OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream("myio\\gbkfile.txt"),"GBK");
        osw.write("你好，你好");
        osw.close();
        //替代方法
        FileWriter fw = new FileWriter("myio\\gbkfile.txt",Charset.forName("GBK"));
        fw.write("你好呀");
        fw.close();
        /*转换流可以实现在两个编码方式不同的软件中正确的传递和读写信息数据*/
        //比如，将本地文件中的GBK文件，转成UTF-8
        FileReader fr2 = new FileReader("myio\\gbkfile.txt",Charset.forName("GBK"));
        FileWriter fw2 = new FileWriter("myio\\gbkfile1.txt",Charset.forName("UTF-8"));
        char[] b = new char[1024];
        int len;
        while((len= fr2.read(b))!=-1){
            fw2.write(b,0,len);
        }
        fr2.close();
        fw2.close();

    }//转换流是字符流和字节流的桥梁
    //可以将字节流转换成字符流，这样就不会出现乱码了，这是inpustreamreader的用法
    //将字符流转换成字节流输出，这是outputstreamwriter的用法
    /*字节与字符的转换
    将原始字节流（如InputStream）转换为字符流（如Reader），方便处理文本数据。
    将字符流（如Writer）转换为字节流（如OutputStream），适应底层硬件的二进制传输。
    支持编码格式
    可指定字符编码（如 UTF-8、GBK、ISO-8859-1），解决不同系统间的字符集差异问题。*/
    /*InputStreamReader
作用：将字节输入流转换为字符输入流。
示例：
java
try (InputStreamReader reader = new InputStreamReader(new FileInputStream("file.txt"), StandardCharsets.UTF_8)) {
    int data;
    while ((data = reader.read()) != -1) {
        System.out.print((char) data);
    }
} catch (IOException e) {
    e.printStackTrace();
}


OutputStreamWriter
作用：将字符输出流转换为字节输出流。
示例：
java
try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream("output.txt"), StandardCharsets.UTF_8)) {
    writer.write("Hello, 世界！");
} catch (IOException e) {
    e.printStackTrace();
}*/



    /*三、实际用法场景
处理不同编码格式的文件
读取 GBK 编码的文件并按 UTF-8 保存：
java
try (InputStreamReader gbkReader = new InputStreamReader(new FileInputStream("gbk.txt"), StandardCharsets.GBK);
     OutputStreamWriter utf8Writer = new OutputStreamWriter(new FileOutputStream("utf8.txt"), StandardCharsets.UTF_8)) {
    int data;
    while ((data = gbkReader.read()) != -1) {
        utf8Writer.write(data);
    }
}

网络通信中的文本传输
通过 Socket 发送 / 接收文本数据时，将字节流转换为字符流：
java
// 客户端发送文本
try (Socket socket = new Socket("localhost", 8080);
     OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream())) {
    writer.write("请求数据");
    writer.flush();
}*/

}
