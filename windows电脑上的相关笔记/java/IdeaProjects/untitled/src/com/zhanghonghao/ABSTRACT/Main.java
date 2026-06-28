package com.zhanghonghao.ABSTRACT;

import com.zhanghonghao.bagfinalchunk.bag;

public class Main {
    public static void main(String[] args) {
        student student = new student(18,"张鸿昊");
        student.work();
        System.out.println(student);
        frog frog=new frog(2,"轻轻");
        System.out.println(frog.getName()+frog.getAge());
        frog.eat();
        frog.swim();
        bag bag=new bag();
        bag.setAge(17);
    }

}
