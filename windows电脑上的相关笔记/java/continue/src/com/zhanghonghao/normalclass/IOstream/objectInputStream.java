//package com.zhanghonghao.normalclass.IOstream;

import java.io.*;

//这个接口里面没有抽象方法，属于标记型接口
//一旦实现了这个接口，那么就表示当前的类可以被序列化
//public class objectInputStream implements Serializable {
  //  public static  void main(String[] args) throws IOException, ClassNotFoundException {
        //序列化流，可以把java对象写在本地文件里
        //使用序列化流，可以把对象写进文件后你看不懂，防止数据被修改
        // 序列化流也叫做对象操作输出流*/
        /*ObjectInputStream oo=new java.io.ObjectInputStream(new FileInputStream("myio\\a.txt"));
        Object object = oo.readObject();
        System.out.println(object);
        oo.close();
        Student student=new Student("zhangsan",17);
        ObjectOutputStream oos=new ObjectOutputStream(new FileOutputStream("myio\\a.txt"));
        oos.writeObject(student);
        oos.close();
        /*小细节
        * 想要不报错，必须实现serializable接口*/
        //反序列化流，对象操作输入流，可以把本地文件中的对象读取到程序中来
        //序列化流和反序列化流的小细节
        //将一个对象存入文件之后，如果对这个对象对应的类中的内容进行修改和处理，会更改这个javabean的版本号，导致和文件的版本号不一致
        //这是使用反序列化流读取对象就会报错
        //为了处理这种情况，我们在类中添加private static final long serialVersionUID=1L,来告诉系统我的版本号不变了
        //如果想挑选部分内容存入本地文件，则需要在不存入文件的内容前加入transient，这样这部分内容就不会被序列化进本地文件


        //现在，我将多个自定义对象序列到文件中，但是由于对象的个数不确定，反序列化流如何读取呢

    //}
//}
