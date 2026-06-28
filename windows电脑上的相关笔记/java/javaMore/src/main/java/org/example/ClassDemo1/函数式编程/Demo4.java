package org.example.ClassDemo1.函数式编程;

import java.util.concurrent.Flow;

public class Demo4 {
    public static void main(String[] args) {
        show((name -> System.out.println("hello" + name)), "张三");
        /*MyFunction myFunction=name -> System.out.println("hello" + name);
        myFunction.show("张三");
        Test test=new Test() {
            @Override
            public void show() {

            }

            @Override
            public void show1() {

            }
        };
        test.show();*/
        // 函数式接口
        // 函数式接口就是一个接口，接口中只有一个抽象方法
        // 函数式接口的实现类，必须实现抽象方法
        // 函数式接口的实现类
        //函数对象的类型
        //根据参数和返回值来确定，如果都像等价，那么就是函数式接口，归为一类
        //函数式接口仅含有一个抽象方法
        /*MyFunction myFunction1=( name)->{
            System.out.println("hello" + name);
            System.out.println("hello" + name);
            System.out.println("hello" + name);
        };

        MyFunction myFunction = (name) -> System.out.println("hello" + name);
        show(name -> System.out.println("hello" + name), "张三");
        MyFunction2 myFunction2 = name -> System.out.printf("hello" + name);
        show(myFunction, "张三");*/
    }
    @FunctionalInterface
    interface MyFunction{
        public void show(String name);
    }
    @FunctionalInterface
    interface MyFunction2{
        public void print(String name);
    }
    public static void show(MyFunction myFunction,String name){
        myFunction.show(name);
    }

}