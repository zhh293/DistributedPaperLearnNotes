package com.zhanghonghao.duotailianxi;

public class feeder {
    String name;
    int age;
    public feeder(){}
    public feeder(String name, int age){
        this.name = name;
        this.age = age;
    }
    public void setName(String name){
        this.name = name;
    }
    public void setAge(int age){
        this.age = age;
    }
    public String getName(){
        return name;
    }
    public int getAge(){
        return age;
    }
    public void keepPet(dog dog,String food){
        System.out.println("年龄为"+age+"的"+name+"养了一只"+dog.getColor()+dog.getAge()+"的狗");
    }

    public void keepPet(cat cat,String food){
        System.out.println("年龄为"+age+"的"+name+"养了一只"+cat.getColor()+cat.getAge()+"的猫");
    }
    public void keepPet1(animal animal,String food){
        if(animal instanceof dog dog){
            System.out.println("年龄为"+age+"的"+name+"养了一只"+dog.getColor()+dog.getAge()+"的狗");
            dog.eat(food);
        }
        else if(animal instanceof cat cat){
            System.out.println("年龄为"+age+"的"+name+"养了一只"+cat.getColor()+cat.getAge()+"的猫");
            cat.eat(food);
        }
    }

}
