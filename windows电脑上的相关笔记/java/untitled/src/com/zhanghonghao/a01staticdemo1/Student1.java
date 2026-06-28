package com.zhanghonghao.a01staticdemo1;

public class Student1 extends Student{
    String birthday;
    public Student1(){
        this("张鸿昊",15,"20204");
    }
    public Student1(String name, int age, String birthday) {
        super(name,age,birthday);
    }

}
