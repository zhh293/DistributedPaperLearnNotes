package com.zhh.handsome.线程安全集合类;

public class 装饰器模式应用实例 {




/*

    先从 “为什么不用装饰器会很麻烦” 说起（学生常踩的坑）
    假设你写了一个简单的计算器程序，有加法、减法功能：
    java
            运行
    // 计算器接口
    interface Calculator {
        int add(int a, int b);
        int sub(int a, int b);
    }

    // 基础计算器实现
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
    现在需求变了：给计算器的每个运算加日志（打印 “谁调用了什么方法，参数是啥”）。如果不用装饰器，你可能会这么做：
    直接修改BasicCalculator的方法，在里面加日志代码 —— 但这违反 “开闭原则”（改了原代码，万一以后要去掉日志，又得改回去）。
    继承BasicCalculator，写一个LogCalculator—— 如果以后还要加 “权限校验”（比如只有管理员能调用），又得写AuthCalculator，如果要 “日志 + 权限”，还得写LogAndAuthCalculator…… 类越来越多，很麻烦。
    用装饰器模式解决：动态加功能，不改原代码
    装饰器模式的核心就是：把 “核心功能” 和 “附加功能” 分开，附加功能像 “插件” 一样随时加 / 卸。
    步骤 1：定义抽象组件（还是原来的Calculator接口）
    java
            运行
    interface Calculator {
        int add(int a, int b);
        int sub(int a, int b);
    }
    步骤 2：具体组件（原功能，BasicCalculator不变）
    java
            运行
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
    步骤 3：抽象装饰器（实现接口，持有被装饰对象）
    java
            运行
    // 所有“计算器装饰器”的父类
    abstract class CalculatorDecorator implements Calculator {
        protected Calculator calculator; // 被装饰的计算器

        public CalculatorDecorator(Calculator calculator) {
            this.calculator = calculator;
        }
    }
    步骤 4：具体装饰器（实现 “日志” 功能）
    java
            运行
    // 日志装饰器：给运算加日志
    class LogCalculator extends CalculatorDecorator {
        public LogCalculator(Calculator calculator) {
            super(calculator);
        }

        @Override
        public int add(int a, int b) {
            // 附加功能：打印日志
            System.out.println("调用add方法，参数：" + a + "," + b);
            // 调用被装饰对象的核心功能
            return calculator.add(a, b);
        }

        @Override
        public int sub(int a, int b) {
            System.out.println("调用sub方法，参数：" + a + "," + b);
            return calculator.sub(a, b);
        }
    }
    步骤 5：再加一个装饰器（比如 “权限校验”）
    java
            运行
    // 权限装饰器：只有管理员能调用
    class AuthCalculator extends CalculatorDecorator {
        private String userRole; // 用户角色

        public AuthCalculator(Calculator calculator, String userRole) {
            super(calculator);
            this.userRole = userRole;
        }

        @Override
        public int add(int a, int b) {
            if (!"admin".equals(userRole)) {
                throw new RuntimeException("没有权限调用add方法");
            }
            return calculator.add(a, b); // 有权限才调用核心功能
        }

        @Override
        public int sub(int a, int b) {
            if (!"admin".equals(userRole)) {
                throw new RuntimeException("没有权限调用sub方法");
            }
            return calculator.sub(a, b);
        }
    }
    步骤 6：使用装饰器（自由组合功能）
    java
            运行
    public class Test {
        public static void main(String[] args) {
            // 1. 基础计算器（无附加功能）
            Calculator basic = new BasicCalculator();
            System.out.println(basic.add(1, 2)); // 输出：3

            // 2. 加了日志的计算器
            Calculator withLog = new LogCalculator(basic);
            withLog.add(1, 2); // 输出：调用add方法，参数：1,2 → 3

            // 3. 加了日志+权限校验的计算器（管理员）
            Calculator logAndAdmin = new LogCalculator(new AuthCalculator(basic, "admin"));
            logAndAdmin.sub(5, 3); // 输出：调用sub方法，参数：5,3 → 2

            // 4. 加了日志+权限校验的计算器（非管理员）
            Calculator logAndUser = new LogCalculator(new AuthCalculator(basic, "user"));
            logAndUser.add(1, 2); // 抛异常：没有权限调用add方法
        }
    }*/









}
