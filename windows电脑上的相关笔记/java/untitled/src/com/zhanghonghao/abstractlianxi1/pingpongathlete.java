package com.zhanghonghao.abstractlianxi1;

public class pingpongathlete extends athlete implements playball,english{
    public pingpongathlete(String name,int age){
        super(name,age);
    }

    public pingpongathlete() {
       this(null,0);
    }

    @Override
    public void say() {
        System.out.println("说英语");
    }

    @Override
    public void play() {
        System.out.println("学打乒乓球");
    }
}
