package com.zhanghonghao.a02;

public class administractor extends person {
public administractor() {}
    public administractor(String name, int age) {
    super(name, age);
    }
    public void show(){
    System.out.println("管理员信息为"+super.getName()+super.getAge()+this.getName());
    }
}
