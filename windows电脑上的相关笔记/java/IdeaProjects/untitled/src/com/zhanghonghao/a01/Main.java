package com.zhanghonghao.a01;

public class Main {
    public static void main(String[] args) {
       students p1=new students();
       p1.lunch();
       hashiqi p2=new hashiqi();
       p2.destroy();
       p2.eat();
       tianyuanquan p3=new tianyuanquan();
       p3.eat();
    }
}
class person{
    public void drink(){
        System.out.println("我要喝水");
    }
    public void eat(){
        System.out.println("我要吃饭");
    }
}
class students extends person{
    public students(){
        super();
        System.out.println("子类的无参构造");
    }
    public void lunch(){
        eat();
        drink();
        this.eat();
        this.drink();
        super.eat();
        super.drink();
    }@Override
    public void drink(){
        System.out.println("hahaha");
    }@Override
    public void eat(){
        System.out.println("lalala");
    }
}