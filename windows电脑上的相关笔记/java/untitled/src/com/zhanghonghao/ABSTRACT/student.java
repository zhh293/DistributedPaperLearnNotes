package com.zhanghonghao.ABSTRACT;

public class student extends person {

    @Override
    public void work() {
        System.out.println("学生的工作室学习");
    }
    public student() {}
    public student(int age, String name) {
        super(name, age);
    }
}
