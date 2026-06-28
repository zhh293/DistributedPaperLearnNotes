package com.zhanghonghao.duotailianxi;

public class test {
    public static void main(String[] args) {
        animal animal = new cat();
        animal.setColor("蓝色的");
        animal.setAge(20);
        animal animal1=new dog();
        animal1.setColor("红色的");
        animal1.setAge(20);
        feeder feeder=new feeder();
        feeder.setName("张鸿昊");
        feeder.setAge(20);
        feeder.keepPet1(animal,"鱼");
        feeder.keepPet1(animal1,"骨头");
    }
}
