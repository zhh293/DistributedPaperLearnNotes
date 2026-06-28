package com.zhanghonghao.normalclass.List;

public class posscat extends CAT{
    public posscat(String name, int age) {
        super(name, age);
    }

    @Override
    public void eat() {
        System.out.println("一只叫做"+this.getName()+"的"+this.getAge()+"岁的波斯猫，正在吃饼干");
    }
}
