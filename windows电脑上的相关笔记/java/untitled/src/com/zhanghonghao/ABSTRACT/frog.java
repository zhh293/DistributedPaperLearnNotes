package com.zhanghonghao.ABSTRACT;

public class frog extends animal implements swim{
    public frog(){
        this(0,null);
    }
    public frog(int age,String name){
        super(name,age);
    }
    public void eat(){
        System.out.println("青蛙在吃虫子");
    }
    public void swim(){
        System.out.println("青蛙在蛙泳");
    }
}
