package com.zhanghonghao.bagfinalchunk;
public class final1 {
     private static final String add="1";
     private static final String sub="2";
     private static final String mul="3";
     private static final String div="4";
    public static void main(String[] args) {
        //方法：表明该方法是最终方式，不能被重写
        //类，表明是最终类，不能被继承
        //变量：叫做常量，只能被赋值一次,如果是基本数据类型，存储的数据值不能发生改变，如果是引用数据类型，记录的地址值不能发生改变，内部的属性值可以改变
     double value=Math.E;
     System.out.println(add);
     System.out.println(value);
     final bag bag=new bag();
     //bag=new bag();这样写是错误的
     bag.setAge(178);
     bag.setName("zhh");
     System.out.println(bag.getAge());
     System.out.println(bag.getName());
     final int []array=new int[10];
     array[0]=1;
     array[1]=2;
     //array=new int[2];这样也是错误的
        String arr="abcdefg";
        arr="abcfd";
        System.out.println(arr);
       /* 第一行代码 String arr = "abcdefg";：
        这行代码创建了一个 String 对象，其内容为 "abcdefg"，然后让引用变量 arr 指向这个对象。在 Java 里，当使用双引号创建字符串时，会先去字符串常量池中查找是否已经存在相同内容的字符串对象。如果存在，就直接让引用指向该对象；若不存在，则在常量池中创建新的字符串对象。
        第二行代码 arr = "abcfd";：
        这行代码并没有改变原来 "abcdefg" 这个字符串对象的内容。它实际上是在字符串常量池中创建了一个新的 String 对象，内容为 "abcfd"，然后让引用变量 arr 指向这个新的对象。而原来的 "abcdefg" 对象依然存在于常量池中，只是没有引用指向它了。*/

    }
}
class fu{
    public final void show(){
        System.out.println("show");
    }
}
class zi extends fu{
    /*public void show(){
        System.out.println("show");
    }*/
}