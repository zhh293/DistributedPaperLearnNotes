package com.zhanghonghao.abstractlianxi1;

public class person {
    String name;
    int age;
    public person(String name, int age) {
        this.name = name;
        this.age = age;
    }
    public person() {}
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
        this.age = age;
    }
}
