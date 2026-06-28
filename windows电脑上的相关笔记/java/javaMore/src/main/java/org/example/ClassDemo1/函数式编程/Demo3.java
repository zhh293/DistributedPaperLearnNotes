package org.example.ClassDemo1.函数式编程;

import io.micrometer.observation.Observation;

import java.util.concurrent.Flow;

public class Demo3 {
    public static void main(String[] args) {



        /*Lambda 表达式的完整语法结构为：

        java
                (参数列表) -> {方法体}


        其中：

        参数列表：用于声明输入参数，可以指定参数类型，也可以不指定（编译器会进行类型推断）。
        箭头符号：->是 Lambda 表达式的核心符号，它将参数列表和方法体分隔开来。
        方法体：可以是单个表达式，也可以是代码块。*/
        


    }
}
/*
语法简化规则
Lambda 表达式有多种简化形式，下面通过示例逐步说明：
        1. 参数类型可以省略
        java
// 完整形式（指定参数类型）
Function<Integer, Integer> add1 = (Integer x) -> x + 1;

// 简化形式（省略参数类型）
Function<Integer, Integer> add2 = (x) -> x + 1;
2. 单个参数可以省略括号
        java
// 带括号
Consumer<String> print1 = (s) -> System.out.println(s);

// 省略括号（仅适用于单个参数）
Consumer<String> print2 = s -> System.out.println(s);
3. 方法体只有一条语句时可以省略花括号和 return 关键字
        java
// 完整形式（代码块）
Function<String, Integer> len1 = (s) -> {
    return s.length();
};

// 简化形式（单表达式，省略花括号和return）
Function<String, Integer> len2 = s -> s.length();
4. 无参数时必须保留空括号
        java
// 无参数，必须有括号
Runnable task = () -> System.out.println("Hello");
5. 多个参数时不能省略括号，但可以省略参数类型
        java
// 完整形式
BiFunction<Integer, Integer, Integer> sum1 = (Integer a, Integer b) -> a + b;

// 简化形式（省略类型）
BiFunction<Integer, Integer, Integer> sum2 = (a, b) -> a + b;
语法限制与注意事项
1. 方法体的限制
单表达式：只能有一条语句，且会自动返回该表达式的值，不能使用return关键字。
java
// 正确（单表达式）
Supplier<Integer> num = () -> 42;

// 错误（单表达式不能有return）
// Supplier<Integer> num = () -> return 42;

代码块：如果有多条语句，必须使用花括号，并且需要显式使用return语句返回值（如果有返回值的话）。
java
// 正确（代码块）
Function<String, String> upper = s -> {
    System.out.println("Converting to uppercase...");
    return s.toUpperCase();
};

2. 函数式接口的要求
Lambda 表达式必须绑定到一个函数式接口（即只有一个抽象方法的接口）。例如：

java
@FunctionalInterface
interface MyFunction {
    String apply(String s); // 只有一个抽象方法
}

// 正确（匹配MyFunction接口）
MyFunction func = s -> s.toUpperCase();*/
/*
那如果一个匿名内部类中有多个必须重写的方法，是不是就不能用lambda表达式了
没错，如果一个匿名内部类需要重写多个抽象方法，就无法使用 Lambda 表达式。这是因为 Lambda 表达式的设计初衷是为了简化函数式接口（即只有一个抽象方法的接口）的实现，它只能代表一个单独的函数。*/
