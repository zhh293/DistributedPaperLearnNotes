package com.zhanghonghao.normalclass.reflect;

public class student {
    private String name;
    private int age;
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
        this.age = age;
    }
    public student() {}
    public String toString(){
         return "name:"+name+",age:"+age;
    }
    public String eat(String food){
        System.out.println(food);
        return "eat";
    }
}
