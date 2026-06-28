package com.zhanghonghao.abstractlianxi1;

public class basketballathlete extends athlete implements playball{
    public basketballathlete(String name,int age){
        super(name,age);
    }


    @Override
    public void play() {
        System.out.println("学打篮球");
    }
}
