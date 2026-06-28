package com.zhanghonghao.a02;

public class teacher extends person{
public teacher(){}
    public teacher(String name,int age){
     super(name,age);
    }
    public void show(){
    System.out.println("老师信息"+super.getName()+super.getAge());
    }
}
