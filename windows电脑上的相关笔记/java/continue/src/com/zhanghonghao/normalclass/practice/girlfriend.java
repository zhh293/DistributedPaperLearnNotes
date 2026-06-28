package com.zhanghonghao.normalclass.practice;

public class girlfriend {
    String name;
    int age;
    int height;
    public girlfriend(String name, int age, int height) {
        this.name = name;
        this.age = age;
        this.height = height;
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
        this.age = age;
    }
    public int getHeight() {
        return height;
    }
    public void setHeight(int height) {
        this.height = height;
    }
    @Override
    public String toString() {
        return  name + "\t" + age + "\t" + height + "\n";
    }
}
