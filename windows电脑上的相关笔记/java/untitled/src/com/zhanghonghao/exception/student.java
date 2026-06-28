package com.zhanghonghao.exception;

public class student {
    String name;
    int age;
    public student() {}
    public student(String name, int age) {
        this.name = name;
        this.age = age;

    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public int getAge() {
        return age;
    }
    public void setAge(int age) {
        if(age<18||age>40){
            //System.out.println("年龄超出范围");
            throw new RuntimeException();
        }
        else{
            this.age = age;
        }
    }
}
