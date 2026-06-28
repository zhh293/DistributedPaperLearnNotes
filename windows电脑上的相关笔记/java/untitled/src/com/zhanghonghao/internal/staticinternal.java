package com.zhanghonghao.internal;

public class staticinternal {
    //静态内部类只能访问外部类中的静态变量和静态方法，如果想要访问非静态的需要创建对象
    //创建格式：1.外部类名.内部类名 对象名=new 外部类名.内部类名()
    //调用静态方法格式：外部类名.内部类名.方法名()
    static class inner{
        public void show(){
            System.out.println("hello");
        }
        public static void show2(){
            System.out.println("hello2");
        }
    }






}
