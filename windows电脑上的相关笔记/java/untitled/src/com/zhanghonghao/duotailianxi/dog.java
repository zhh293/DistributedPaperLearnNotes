package com.zhanghonghao.duotailianxi;

public class dog extends animal{
    public void lookhome(){
        System.out.println(super.getAge()+super.getColor()+"的狗"+"正在看家，最好别招惹他");
    }
    public void eat(String food){
        System.out.println(super.getColor()+"的"+super.getAge()+"的狗"+"两只前腿死死的抱住"+food);
    }
}
