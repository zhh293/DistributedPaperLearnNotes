package com.zhanghonghao.normalclass.List;

public class lihuacat extends CAT{
    public lihuacat(String name, int age) {
        super(name, age);
    }

    @Override
    public void eat() {
        System.out.println("一只叫做"+this.getName()+"的"+this.getAge()+"岁的波斯猫，正在吃鱼");
    }
}
