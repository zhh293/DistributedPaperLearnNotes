package com.zhanghonghao.ABSTRACT;

public class dog extends animal implements swim{

    public dog(String name, int age) {
        super(name, age);
    }

    @Override
    public void eat() {
        System.out.println("狗在吃屎");
    }

    @Override
    public void swim() {
       System.out.println("狗刨");
    }
}
