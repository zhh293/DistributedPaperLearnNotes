package com.zhanghonghao.normalclass.IOstream;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class trycatch {
    public static void main(String [] args) throws IOException {
//trycatch中的finally块，这个块中的代码一定会被执行，除非JVM退出
        /*FileInputStream fis1=null;
        FileOutputStream fos=null;


        try {
            //文件拷贝
            fis1= new FileInputStream("C:\\Users\\92819\\c.txt");
          fos = new FileOutputStream("C:\\Users\\92819\\d.txt");
            //2.开始拷贝
        /*int c;
        while ((c = fis1.read()) != -1) {
            fos.write(c);
        }
        File file = new File("C:\\Users\\92819\\d.txt");
        System.out.println(file.lastModified());
        //先开的流，最后再关闭
        fos.close();
        fis1.close();*/
            //以上只适用于小文件，大文件会出现乱码
            //练习，统计一下拷贝时间，单位毫秒
            //下面来处理拷贝大文件时该怎么处理
            //一次读取一个字节数组的数据，每次读取会尽可能把数组填满
           /* byte[] buffer = new byte[1024];
            int c;
            //因为每次循环数组都会被覆盖一遍，所以打印时要限制打印的长度，就要用write的三个参数一起来限制
            while ((c = fis1.read(buffer)) != -1) {
                fos.write(buffer,0,c);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if(fos!=null){
                try {
                    fos.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if(fis1!=null){
                try {
                    fis1.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }*/
        //接口autocloseable，必须是实现了这个接口的类的对象才可以放在try括号里面

        //试用一下
        FileInputStream fis1=new FileInputStream("C:\\\\Users\\\\92819\\\\c.txt");
        FileOutputStream fos=new FileOutputStream("C:\\\\Users\\\\92819\\\\c.txt");
        try(fis1;fos){
            byte[] buffer = new byte[1024];
            int c;
            //因为每次循环数组都会被覆盖一遍，所以打印时要限制打印的长度，就要用write的三个参数一起来限制
            while ((c = fis1.read(buffer)) != -1) {
                fos.write(buffer,0,c);
            }
        }catch (IOException e){
            e.printStackTrace();
        }







    }
}
