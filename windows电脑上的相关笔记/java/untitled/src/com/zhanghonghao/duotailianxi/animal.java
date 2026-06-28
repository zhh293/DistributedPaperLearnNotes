package com.zhanghonghao.duotailianxi;

public class animal {
    int age;
    String color;
    public animal(int age, String color) {
        this.age = age;
        this.color = color;
    }
    public animal() {}
    public void setAge(int age) {
        this.age = age;
    }
    public void setColor(String color) {
        this.color = color;
    }
    public int getAge() {
        return age;
    }
    public String getColor() {
        return color;
    }
    public void eat(String food){
        System.out.println("正在吃"+food);
    }

}
