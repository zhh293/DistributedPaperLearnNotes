package com.zhanghonghao.internal;

public class partialinternal {
    //1.将内部类定义在方法里面叫做局部内部类，类似方法里面的局部变量
    //2.外界是无法直接使用局部内部类，需要在方法内部创建对象并使用
    //3.该类可以直接访问外部类成员，也可以访问方法内的局部变量
    String name;
    int age;
    public void show(){
        int a=10;
        class inner{
            public void show(){
                System.out.println(a);
            }
            public static void show1(){
                System.out.println("show1");
            }
        }
        inner inner1=new inner();
        inner1.show();
        inner.show1();
    }
public void show2(){
        System.out.println(name+age);
}

}
