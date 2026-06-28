package com.zhh.handsome.线程安全集合类;

public class 装饰器模式实操 {
    //实现接口，定义一个基础实现类
    //然后准备加其他的功能，但不能破坏开闭原则
    //那么就用一个抽象类装你想要装饰的基础实现类
    //让装饰类继承抽象类拿到基础实现类
    //然后既然要增强函数功能，那么必须拿到基础实现类中的方法
    //所以我们统一在抽象类中实现接口的方法
    //紧接着在增强类中重写方法，增强功能。
    //主方法调用：左边是接口名字(多态思想),右边new装饰类，然后在括号中传入基础实现类

    //进阶一下，如果只增加一种功能不满足你的需求
    //可以这么写 Calculator logAndUser = new LogCalculator(new AuthCalculator(basic, "user"));
    //这样子就增加了两种功能，我来解释一下
    //内层new对基础实现类的方法做了增强，得到的多态类的Calculator对象的方法都是增强后的方法
    //然后继续用外层的new增强这个多态类对象，于是就有两个功能了。。。。。。
    public static void main(String[] args) {
        Calculator calculator=new BasicCalculator();
        Calculator logAndUser = new LogCalculator(new AuthCalculator(calculator, "user"));
        logAndUser.add(1, 2);
        logAndUser.sub(1, 2);
    }
}
interface Calculator {
    int add(int a, int b);
    int sub(int a, int b);
}
class BasicCalculator implements Calculator {
    @Override
    public int add(int a, int b) {
        return a + b;
    }
    @Override
    public int sub(int a, int b) {
        return a - b;
    }
}
abstract class CalculatorDecorator implements Calculator {
    protected Calculator calculator;
    public CalculatorDecorator(Calculator calculator) {
        this.calculator = calculator;
    }
}
class LogCalculator extends CalculatorDecorator {
    public LogCalculator(Calculator calculator) {
        super(calculator);
    }
    @Override
    public int add(int a, int b) {
        System.out.println("日志功能");
        return calculator.add(a, b);
    }
    @Override
    public int sub(int a, int b) {
        System.out.println("日志功能");
        return calculator.sub(a, b);
    }
}
class AuthCalculator extends CalculatorDecorator {
    private String user;
    public AuthCalculator(Calculator calculator, String user) {
        super(calculator);
        this.user = user;
    }
    @Override
    public int add(int a, int b) {
        if ("user".equals(user)) {
            System.out.println("权限认证功能");
            return calculator.add(a, b);
        }
        return 0;
    }
    @Override
    public int sub(int a, int b) {
        if ("user".equals(user)) {
            System.out.println("权限认证功能");
            return calculator.sub(a, b);
        }
        return 0;
    }
}