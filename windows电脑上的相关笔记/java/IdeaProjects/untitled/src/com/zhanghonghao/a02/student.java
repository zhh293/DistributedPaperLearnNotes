package com.zhanghonghao.a02;

public class student extends person{
    public student(){}
    public student(String name,int age){
        super(name,age);
    }
    public void show(){
        System.out.println("学生的信息为:"+getName()+"年龄为"+getAge());


    } public void test(){
        System.out.println("学生在考试");}
}
