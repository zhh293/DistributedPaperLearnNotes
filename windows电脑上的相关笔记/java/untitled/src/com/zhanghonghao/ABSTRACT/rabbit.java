package com.zhanghonghao.ABSTRACT;

public class rabbit extends animal{
    public rabbit(String name,int age){
        super(name,age);
    }
    public rabbit(){
        this(null,0);
    }
    public void eat(){
        System.out.println("兔子在吃胡萝卜");
    }
}
