package org.example.ClassDemo1.函数式编程;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Demo2 {
    public static void main(String[] args) {
        Function<String, Integer> loger=new Function<String,Integer>() {
            @Override
            public Integer apply(String s) {
                return s.length();
            }
        };
        Integer zhh = loger.apply("zhh");
        System.out.println(zhh);
    }
   /* static Logger logger=init(Level.INFO);
    public static void main(String[] args) {
        *//*if(logger.isDebugEnabled()){
            logger.debug("{}", expensive());
        }*//*
        //简化一下
        logger.debug("{}",()->expensive());//这个传的是函数对象
        //下面这个是上面的完整形式
        logger.debug("{}",new Supplier<Object>(){
            @Override
            public Object get() {
                return expensive();
            }
        });
        logger.debug("{}",expensive());//这个传的是一个函数结果
        // 函数式接口
        // 函数式接口：只有一个抽象方法
        // 函数式接口的实例：Lambda表达式
        // 函数式接口的实例：方法引用
        // 函数式接口的实例：构造方法引用
     //函数对象的表现形式

    }
    static String expensive(){
        System.out.println(" expensive() ");
        return " expensive() ";
    }*/
    //函数的延迟执行
}
/*
在 Java 里，要实现类似函数对象的功能，一般会借助以下几种方式。
        1. 匿名内部类
借助匿名内部类，能够创建一个实现了特定接口的对象实例，而且无需显式定义类名。这一方式适用于只使用一次的场合。

下面是一个简单示例：

java
interface Calculator {
    int calculate(int a, int b);
}

public class Main {
    public static void main(String[] args) {
        // 匿名内部类实现Calculator接口
        Calculator adder = new Calculator() {
            @Override
            public int calculate(int a, int b) {
                return a + b;
            }
        };

        Calculator subtractor = new Calculator() {
            @Override
            public int calculate(int a, int b) {
                return a - b;
            }
        };

        System.out.println(adder.calculate(5, 3));    // 输出: 8
        System.out.println(subtractor.calculate(5, 3)); // 输出: 2
    }
}





在这个例子中，Calculator接口定义了一个抽象方法calculate。通过匿名内部类，我们创建了adder和subtractor这两个对象，它们分别实现了加法和减法运算。*/


/*

上面的例子用 Lambda 表达式可以改写为：

java
@FunctionalInterface
interface Calculator {
    int calculate(int a, int b);
}

public class Main {
    public static void main(String[] args) {
        // Lambda表达式实现Calculator接口
        Calculator adder = (a, b) -> a + b;
        Calculator subtractor = (a, b) -> a - b;

        System.out.println(adder.calculate(5, 3));    // 输出: 8
        System.out.println(subtractor.calculate(5, 3)); // 输出: 2
    }
}




Lambda 表达式的语法结构是(参数) -> {方法体}。当方法体只有一条语句时，可以省略花括号和return关键字。*/

/*


3. 方法引用（Java 8+）
方法引用是 Lambda 表达式的一种简化形式，它能直接引用已有的方法或构造函数。

方法引用有四种类型：

静态方法引用：ClassName::staticMethod
实例方法引用：instance::instanceMethod
对象方法引用：ClassName::instanceMethod
构造方法引用：ClassName::new

下面是一个示例：

java
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<String> names = Arrays.asList("Alice", "Bob", "Charlie");

        // 使用方法引用替代Lambda表达式
        names.forEach(System.out::println);

        // 构造方法引用示例
        java.util.function.Supplier<List<String>> listSupplier = ArrayList::new;
        List<String> newList = listSupplier.get();
    }
}*/


/*
4. 函数式接口（Java 8+）
Java 8 在java.util.function包中提供了一系列标准的函数式接口，常见的有以下几种：

Function<T, R>：接收一个参数并返回一个结果。
Consumer<T>：接收一个参数但不返回结果（用于执行副作用操作）。
Supplier<T>：不接收参数但返回一个结果。
Predicate<T>：接收一个参数并返回布尔值。

下面是一个使用Function接口的示例：

java
import java.util.function.Function;

public class Main {
    public static void main(String[] args) {
        // Function接口示例：将字符串转换为其长度
        Function<String, Integer> strLength = s -> s.length();

        System.out.println(strLength.apply("Hello")); // 输出: 5

        // 组合函数示例
        Function<Integer, Integer> multiplyByTwo = x -> x * 2;
        Function<String, Integer> strLengthThenDouble = strLength.andThen(multiplyByTwo);

        System.out.println(strLengthThenDouble.apply("Hello")); // 输出: 10
    }
}*/
