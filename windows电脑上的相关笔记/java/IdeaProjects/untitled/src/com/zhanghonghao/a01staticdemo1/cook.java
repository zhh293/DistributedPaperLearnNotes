package com.zhanghonghao.a01staticdemo1;

public class cook extends worker{
    public cook(){}
    public cook(String name,int salary,String ID){
        super(name, ID, salary);
    }
    public void work(){
        System.out.println("炒菜");
    }
    public void eat(){
        System.out.println("吃米饭");
    }
}
