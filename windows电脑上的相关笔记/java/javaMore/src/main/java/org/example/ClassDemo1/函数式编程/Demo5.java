package org.example.ClassDemo1.函数式编程;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public class Demo5 {
    public static void main(String[] args) throws Exception {
        Callable<Thread> callable=()->{
            System.out.println("callable");
            return new Thread();
        };
        Thread call = callable.call();
        call.setName("callable");
        call.start();
        System.out.println(call.getName());
        Supplier<Thread> supplier=()->{
            System.out.println("supplier");
            return new Thread();
        };
        //这个supplier其实是一个匿名内部类对象 ，这个对象实现了Supplier接口，这个接口只有一个抽象方法get，
        //我们现在重写了Supplier接口的get方法，get方法返回了一个Thread对象，所以下面用对象调用一下就行。
        Thread thread = supplier.get();
        Show<String> show=()-> "hello world";
        String show1 = show.show();
        System.out.println(show1);
        Show<String> show2 = new Show<>() {
            @Override
            public String show() {
                return "hello world";
            }
        };
        String show3 = show2.show();
        System.out.println(show3);
    }
}

@FunctionalInterface
interface Show< T>{
    T show();
}

/*
通用性
Java 8 在java.util.function包中定义了四大核心函数式接口，它们的设计具有很强的通用性：

Supplier<T>：不接收任何参数，仅返回一个结果（提供数据）。
Consumer<T>：接收一个参数，但不返回结果（消费数据）。
Function<T, R>：接收一个参数，并返回一个结果（转换数据）。
Predicate<T>：接收一个参数，返回一个布尔值（判断数据）。

这四大接口通过泛型进行参数化，几乎可以涵盖各种函数式场景。而且，基于这四大核心接口，还衍生出了很多特殊变体，像IntSupplier、BiFunction<T, U, R>等。
实际用途
下面通过一些例子来说明 Supplier 接口的常见用法：
        1. 延迟计算
        java
import java.util.function.Supplier;

public class LazyEvaluation {
    public static void main(String[] args) {
        // 定义一个Supplier，但不立即执行
        Supplier<Double> lazyValue = () -> {
            System.out.println("计算中...");
            return Math.random() * 100;
        };

        // 只有在需要时才调用get()执行计算
        System.out.println("需要结果时: " + lazyValue.get());
    }
}
2. 工厂方法
        java
import java.util.function.Supplier;

class Car {
    private String brand;
    public Car(String brand) { this.brand = brand; }
    public String getBrand() { return brand; }
}

public class FactoryExample {
    // 接收一个Supplier作为工厂方法
    public static Car createCar(Supplier<Car> factory) {
        return factory.get();
    }

    public static void main(String[] args) {
        // 通过Supplier传递构造逻辑
        Car car = createCar(() -> new Car("Tesla"));
        System.out.println(car.getBrand());
    }
}*/
