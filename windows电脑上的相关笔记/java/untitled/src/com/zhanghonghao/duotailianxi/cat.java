package com.zhanghonghao.duotailianxi;

public class cat extends animal{
    public cat(){}
    public void catchmouse(){
        System.out.println(super.getAge()+super.getColor()+"的猫"+"在抓老鼠");
    }
    public void eat(String food){
        System.out.println(super.getColor()+"的"+super.getAge()+"的猫"+"眯着眼睛侧着头吃"+food);

    }
    public void catchmouse(String name){
        System.out.println(super.getAge()+super.getColor()+"的猫"+"在抓"+name+"老鼠");
    }
}
