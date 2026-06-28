package com.zhanghonghao.normalclass.file;

import java.io.File;
import java.io.IOException;

public class file1 {
    public static void main(String[] args) throws IOException {
        //路径分为绝对路径和相对路径
        //File对象就表示一个路径(可以是绝对也可以是相对路径)
        //创建对象
        String s1="\"C:\\Users\\92819\\.npmrc\"";
        File f=new File(s1);
        System.out.println(f);
        //父级路径，"\C:\\Users\\92819",子级路径就是上面那个再减去父级路径
        String parent="\\C:\\Users\\92819";
        String child=".npmrc";
        File f1=new File(parent,child);
        System.out.println(f1);
        //File三种构造方法的作用
        //1.File(String pathname)2.File(String parent,String child)3.File(File parent,String child)
        //File的成员方法
        //1.判断和获取方法
        //文件是否存在，如果存在，是文件还是文件夹
        System.out.println(f1.exists());
        System.out.println(f1.isDirectory());
        System.out.println(f1.isFile());
        //文件大小，单位是字节大小,这个方法无法获取文件夹的大小
        System.out.println(f1.length());
        //文件最后的修改时间
        System.out.println(f1.lastModified());
        //返回文件的绝对路径
        System.out.println(f1.getAbsoluteFile());
        //返回定义文件时使用的路径
         System.out.println(f1.getPath());
         //获取文件的名字
        System.out.println(f1.getName());
        //2.创建和删除
        //创建一个新的空的文件,如果父级路径是不存在的，那么会抛出异常，此方法创建的一定是文件，如果没有后缀名，就创建一个没有后缀的文件
        File f2=new File("C:\\Users\\92819\\c.txt");
        boolean b=f2.createNewFile();
        System.out.println(b);
        //创建单级文件夹,路径是唯一的，如果当前路径已经存在，则创建失败，而且只能创建单级文件夹
        File f3=new File("E:\\java\\lianxi");
        boolean b3=f3.mkdir();
        System.out.println(b3);
        //创建多级文件夹,当然也可以创建单级文件夹
        File f4=new File("E:\\java\\lianxi\\lianxi1\\lianxi2");
        boolean b4=f4.mkdirs();
        System.out.println(b4);
        //删除文件·空文件夹
        //如果删除的是文件，则直接删除，不走回收站
        //如果删除的是空文件夹，则直接删除，不走回收站，如果想要删除有内容的文件夹，则删除失败
        File f5=new File("C:\\Users\\92819\\c.txt");
        boolean b5=f5.delete();
        System.out.println(b5);
        File f6=new File("E:\\java\\lianxi");
        boolean b6=f6.delete();
        System.out.println(b6);

    }
}
